package com.castpro.zero.phone.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.view.Surface
import com.castpro.zero.protocol.*
import com.castpro.zero.phone.net.PhoneSenderTransport
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sender: PhoneSenderTransport? = null
    private val active = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CastPro-Zero").setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        ServiceCompat.startForeground(this, 41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consent = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val host = intent?.getStringExtra(EXTRA_TV_IP)
        val token = intent?.getStringExtra(EXTRA_PAIRING_TOKEN)
        val tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, 45670) ?: 45670
        val udpPort = intent?.getIntExtra(EXTRA_UDP_PORT, 45671) ?: 45671
        if (code == 0 || consent == null || host.isNullOrBlank() || token.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            sender = PhoneSenderTransport(host, tcpPort, udpPort, token,
                onPli = {}, onSyncFrameRequest = {
                    try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                    catch (e: Exception) { Log.w(TAG, "Sync-frame request failed", e) }
                }).also { it.connect() }
            // Fresh one-use consent data is required for every projection session.
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, consent)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopCapture()
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 1 && height > 1) display?.resize(width, height, resources.displayMetrics.densityDpi)
                }
            }, Handler(mainLooper))
            startEncoder(intent.getIntExtra(EXTRA_BITRATE, 20_000_000))
        } catch (e: Exception) { Log.e(TAG, "Projection startup failed", e); stopCapture() }
        return START_NOT_STICKY
    }

    private fun startEncoder(bitrate: Int) {
        val wifi = getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CPZ-low-latency").also { it.acquire() }
        val format = EncoderFormatPolicy.build(1920, 1080, bitrate.coerceIn(10_000_000, 30_000_000), 60, true)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        catch (_: IllegalArgumentException) {
            enc.configure(EncoderFormatPolicy.build(1920, 1080, bitrate, 60, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec = enc
        surface = enc.createInputSurface()
        enc.start()
        display = projection?.createVirtualDisplay("CastPro-Zero", 1920, 1080, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        sender?.sendStreamConfig(ControlMessage.StreamConfig(1, 1920, 1080, 60, bitrate, 1, 3, 2))
        active.set(true)
        Thread({ drain(enc) }, "CPZ-encoder-drain").start()
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
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            val sets = AnnexB.extract(data, VideoCodec.AVC)
                            if (sets != null) sender?.sendCsd(1, sets.annexB())
                        } else {
                            val keyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            sender?.sendVideoFrame(data, keyframe, info.presentationTimeUs * 1_000L)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Authenticated LAN frame transmission failed", e)
                    active.set(false)
                } finally {
                    enc.releaseOutputBuffer(index, false)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val format = enc.outputFormat
                    val csd0 = format.getByteBuffer("csd-0")?.duplicate()?.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                    val csd1 = format.getByteBuffer("csd-1")?.duplicate()?.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                    val annexB = listOfNotNull(csd0, csd1).fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }
                    val sets = AnnexB.extract(annexB, VideoCodec.AVC)
                    if (sets != null) sender?.sendCsd(1, sets.annexB())
                } catch (e: Exception) { Log.e(TAG, "Encoder format/CSD delivery failed", e); active.set(false) }
            }
        }
    }

    private fun stopCapture() {
        active.set(false)
        try { display?.release() } catch (_: Exception) {}; display = null
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}; codec = null
        try { surface?.release() } catch (_: Exception) {}; surface = null
        try { projection?.stop() } catch (_: Exception) {}; projection = null
        try { wifiLock?.release() } catch (_: Exception) {}; wifiLock = null
        try { sender?.sendControl(ControlMessage.Stop(0)) } catch (_: Exception) {}
        try { sender?.close() } catch (_: Exception) {}; sender = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW))
    }
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_TV_IP = "tv_ip"
        const val EXTRA_PAIRING_TOKEN = "pairing_token"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_UDP_PORT = "udp_port"
        private const val CHANNEL = "cpz_capture"
        private const val TAG = "CPZ-Capture"
    }
}
