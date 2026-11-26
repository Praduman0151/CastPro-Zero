package com.castpro.zero.phone.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.ServiceCompat
import com.castpro.zero.phone.PhoneSessionBus
import com.castpro.zero.phone.net.LocalNetworkBinding
import com.castpro.zero.phone.net.PhoneSenderTransport
import com.castpro.zero.protocol.AnnexB
import com.castpro.zero.protocol.ControlMessage
import com.castpro.zero.protocol.VideoCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground MediaProjection sender. Every encoded access unit is transmitted;
 * releaseOutputBuffer(false) is the mandatory encoder buffer return after
 * transmission, never a frame discard loop.
 */
class PhoneCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sender: PhoneSenderTransport? = null
    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var frameLogCount = 0
    private var firstFrameLogged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); return START_NOT_STICKY }
        ensureNotificationChannel()
        val stopIntent = Intent(this, PhoneCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 42, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CastPro-Zero")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Stop casting", stopPendingIntent)
            .build()
        // Android 15/16: foreground promotion MUST precede getMediaProjection().
        ServiceCompat.startForeground(
            this, 41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consent = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val host = intent?.getStringExtra(EXTRA_TV_IP)
        val token = intent?.getStringExtra(EXTRA_PAIRING_TOKEN)
        val tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT) ?: DEFAULT_TCP_PORT
        val udpPort = intent?.getIntExtra(EXTRA_UDP_PORT, DEFAULT_UDP_PORT) ?: DEFAULT_UDP_PORT
        if (code == 0 || consent == null || host.isNullOrBlank() || token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val binding = LocalNetworkBinding(this)
            sender = PhoneSenderTransport(host, tcpPort, udpPort, token,
                onPli = {
                    Log.i(TAG, "PLI received; cached CSD replayed and sync frame requested")
                    sender?.stats()?.copy(resolution = "${WIDTH}×${HEIGHT}")?.let(PhoneSessionBus::publish)
                },
                onRtt = { measured ->
                    sender?.stats()?.copy(rttMs = measured, resolution = "${WIDTH}×${HEIGHT}")?.let(PhoneSessionBus::publish)
                },
                onSyncFrameRequest = {
                    try {
                        codec?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync-frame request failed", e)
                    }
                },
                binding = binding
            ).also { it.connect() }

            // Fresh one-use consent data is required for every projection session.
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(code, consent)
            // Callback must be registered before createVirtualDisplay().
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopCapture()
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 1 && height > 1) {
                        display?.let { virtualDisplay ->
                            virtualDisplay.resize(width, height, resources.displayMetrics.densityDpi)
                            virtualDisplay.setSurface(surface)
                        }
                    }
                }
            }, Handler(mainLooper))
            startEncoder(BitrateOptions.toBps(intent.getIntExtra(EXTRA_BITRATE, BitrateOptions.DEFAULT_MBPS)))
        } catch (e: Exception) {
            Log.e(TAG, "Projection startup failed", e)
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startEncoder(bitrate: Int) {
        val wifi = getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CPZ-low-latency")
                .also { it.acquire() }
        }
        val format = EncoderFormatPolicy.build(WIDTH, HEIGHT, bitrate, FPS, true)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        EncoderFormatPolicy.configureWithFallback(
            enc, format, EncoderFormatPolicy.build(WIDTH, HEIGHT, bitrate, FPS, false)
        )
        codec = enc
        surface = enc.createInputSurface()
        enc.start()
        Log.i(TAG, "Encoder started: ${enc.name} at ${WIDTH}x${HEIGHT}@${FPS}, ${bitrate / 1_000_000} Mbps")
        // Exactly one VirtualDisplay is created; resizing goes through resize().
        display = projection?.createVirtualDisplay(
            "CastPro-Zero", WIDTH, HEIGHT, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        sender?.sendStreamConfig(
            ControlMessage.StreamConfig(1, WIDTH, HEIGHT, FPS, bitrate, 1, 3, 2)
        )
        active.set(true)
        PhoneSessionBus.publishStreaming(true)
        Thread({ drain(enc) }, "CPZ-encoder-drain").start()
        Thread({ statsLoop(enc) }, "CPZ-phone-stats").start()
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (active.get()) {
            val index = try { enc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            if (index >= 0) {
                try {
                    val output = enc.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        val view = output.duplicate()
                        view.position(info.offset)
                        view.limit(info.offset + info.size)
                        view.get(data)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (isConfig) {
                            val sets = AnnexB.extract(data, VideoCodec.AVC)
                            if (sets != null) sender?.sendCsd(1, sets.annexB())
                            else Log.w(TAG, "CODEC_CONFIG had no valid Annex-B parameter sets")
                        } else {
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                Log.i(TAG, "First transmitted frame wall-clock=${System.nanoTime()} ns, codec=${enc.name}")
                            }
                            // Every encoded access unit is sent; no drop path exists.
                            val sent = sender?.sendVideoFrame(data, isKeyframe, info.presentationTimeUs * 1_000L)
                                ?: error("Sender transport unavailable")
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                Log.i(TAG, "First transmitted frame from ${enc.name} at ${info.presentationTimeUs}us")
                            }
                            if (frameLogCount < 10) {
                                frameLogCount++
                                val nalTypes = AnnexB.nalUnits(data).mapNotNull { it.firstOrNull()?.toInt()?.and(31) }
                                Log.i(TAG, "TX frameId=${sent.frameId} fragCount=${sent.fragmentCount} size=${sent.byteCount} keyframe=$isKeyframe NAL=$nalTypes")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Authenticated LAN frame transmission failed", e)
                    active.set(false)
                } finally {
                    // Mandatory encoder buffer return after the transmit attempt.
                    enc.releaseOutputBuffer(index, false)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val format = enc.outputFormat
                    Log.i(TAG, "INFO_OUTPUT_FORMAT_CHANGED: $format")
                    val csd0 = format.getByteBuffer("csd-0")?.duplicate()
                        ?.let { ByteArray(it.remaining()).also(it::get) }
                    val csd1 = format.getByteBuffer("csd-1")?.duplicate()
                        ?.let { ByteArray(it.remaining()).also(it::get) }
                    val annexB = listOfNotNull(csd0, csd1).fold(ByteArray(0)) { acc, nal ->
                        acc + byteArrayOf(0, 0, 0, 1) + nal
                    }
                    val sets = AnnexB.extract(annexB, VideoCodec.AVC)
                    if (sets != null) sender?.sendCsd(1, sets.annexB())
                } catch (e: Exception) {
                    Log.e(TAG, "Encoder format/CSD delivery failed", e)
                    active.set(false)
                }
            }
        }
        if (!stopping.get()) Handler(mainLooper).post { stopCapture() }
    }

    private fun statsLoop(enc: MediaCodec) {
        var last = System.nanoTime()
        var lastFrames = 0L
        var lastBytes = 0L
        while (active.get()) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            val now = System.nanoTime()
            val elapsed = (now - last).coerceAtLeast(1)
            val transport = sender ?: continue
            val snapshot = transport.stats()
            val fps = ((snapshot.framesSent - lastFrames) * 1_000_000_000L / elapsed).toInt()
            val mbps = ((snapshot.bytesSent - lastBytes) * 8_000f / elapsed)
            last = now
            lastFrames = snapshot.framesSent
            lastBytes = snapshot.bytesSent
            PhoneSessionBus.publish(transport.stats(fps, mbps, enc.name).copy(resolution = "${WIDTH}×${HEIGHT}"))
        }
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        active.set(false)
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        try { sender?.sendControl(ControlMessage.Stop(0)) } catch (_: Exception) {}
        try { sender?.close() } catch (_: Exception) {}
        sender = null
        PhoneSessionBus.publishStreaming(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

