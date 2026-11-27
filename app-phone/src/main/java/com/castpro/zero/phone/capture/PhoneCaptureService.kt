package com.castpro.zero.phone.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.castpro.zero.phone.PhoneSessionBus
import com.castpro.zero.phone.net.LocalNetworkBinding
import com.castpro.zero.phone.net.PhoneSenderTransport
import com.castpro.zero.phone.net.SendFailure
import com.castpro.zero.phone.net.SendFailurePolicy
import com.castpro.zero.phone.net.SendOutcome
import com.castpro.zero.protocol.AnnexB
import com.castpro.zero.protocol.ControlMessage
import com.castpro.zero.protocol.VideoCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground MediaProjection sender. MediaCodec output buffers are copied into pooled
 * byte arrays and released immediately so blocking/paced UDP transmission never stalls
 * hardware encoder output slots.
 */
class PhoneCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sender: PhoneSenderTransport? = null
    private var audio: AudioCapture? = null
    private var frameQueue: BoundedFrameQueue? = null
    private val framePool = FrameBufferPool(maxPooled = 16)
    private var drainThread: Thread? = null
    private var txThread: Thread? = null
    private var statsThread: Thread? = null
    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val reconfiguring = AtomicBoolean(false)
    private val codecLock = Any()
    private val telemetry = SenderTelemetryState()
    private var abrController: AdaptiveBitrateController? = null
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var thermalLevel = 0
    @Volatile private var lastTxCongestionCount = 0L
    private val startup = OffThreadStartup(
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CPZ-capture-startup") }
    )
    private val shutdownExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CPZ-capture-stop") }
    private var frameLogCount = 0
    private var firstFrameLogged = false
    @Volatile private var activeWidth = WIDTH
    @Volatile private var activeHeight = HEIGHT
    @Volatile private var sourceDisplayWidth = WIDTH
    @Volatile private var sourceDisplayHeight = HEIGHT
    @Volatile private var maxLongSide = WIDTH
    @Volatile private var activeBitrateBps = BitrateOptions.toBps(BitrateOptions.DEFAULT_MBPS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureAsync()
            return START_NOT_STICKY
        }
        stopping.set(false)
        ensureNotificationChannel()
        val stopIntent = Intent(this, PhoneCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 42, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Stop casting",
            stopPendingIntent
        ).build()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CastPro-Zero")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(stopAction)
            .build()
        // Android 15/16: foreground promotion MUST precede getMediaProjection().
        ServiceCompat.startForeground(
            this, 41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consent = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        val host = intent?.getStringExtra(EXTRA_TV_IP)
        val token = intent?.getStringExtra(EXTRA_PAIRING_TOKEN)
        val tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT) ?: DEFAULT_TCP_PORT
        val udpPort = intent?.getIntExtra(EXTRA_UDP_PORT, DEFAULT_UDP_PORT) ?: DEFAULT_UDP_PORT
        if (code == 0 || consent == null || host.isNullOrBlank() || token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedBitrate = BitrateOptions.toBps(
            intent.getIntExtra(EXTRA_BITRATE, BitrateOptions.DEFAULT_MBPS)
        )
        // Network I/O and projection/encoder setup must not run on the main thread.
        startup.execute {
            if (stopping.get()) return@execute
            try {
                val binding = LocalNetworkBinding(this)
                sender = PhoneSenderTransport(
                    host, tcpPort, udpPort, token,
                    onPli = {
                        Log.i(TAG, "PLI received; cached CSD replayed and sync frame requested")
                        sender?.stats()?.pliCount?.let { telemetry.onPli(it) }?.let(PhoneSessionBus::publish)
                    },
                    onRtt = { measured ->
                        telemetry.onRtt(measured)?.let(PhoneSessionBus::publish)
                    },
                    onSyncFrameRequest = { requestEncoderSyncFrame(codec) },
                    onHealthFeedback = { feedback -> handleHealthFeedback(feedback) },
                    binding = binding
                ).also { it.connect() }
                if (stopping.get()) { teardown(); return@execute }

                // Fresh one-use consent data is required for every projection session.
                projection = getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(code, consent)
                // Callback must be registered before createVirtualDisplay().
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopCaptureAsync()
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        if (width > 1 && height > 1) {
                            sourceDisplayWidth = width
                            sourceDisplayHeight = height
                            val (newW, newH) = computeAspectFitSize(width, height, maxLongSide)
                            if (kotlin.math.abs(newW - activeWidth) > 16 || kotlin.math.abs(newH - activeHeight) > 16) {
                                startup.execute {
                                    reconfigureResolution(newW, newH, activeBitrateBps)
                                }
                            }
                        }
                    }
                }, Handler(mainLooper))
                abrController = AdaptiveBitrateController(requestedBitrate)
                activeBitrateBps = requestedBitrate
                maxLongSide = WIDTH
                val bounds = try {
                    getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
                } catch (_: Exception) {
                    null
                }
                val dm = resources.displayMetrics
                sourceDisplayWidth = (bounds?.width() ?: dm.widthPixels).coerceAtLeast(2)
                sourceDisplayHeight = (bounds?.height() ?: dm.heightPixels).coerceAtLeast(2)
                val (initW, initH) = computeAspectFitSize(sourceDisplayWidth, sourceDisplayHeight, maxLongSide)
                lastTxCongestionCount = 0L
                registerThermalListener()
                startEncoder(requestedBitrate, initW, initH)
                if (stopping.get()) { teardown(); return@execute }
                val current = sender
                val currentProjection = projection
                if (current != null && currentProjection != null && AudioCapturePolicy.shouldCapture(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                        true
                    )) {
                    try {
                        val capture = AudioCapture(currentProjection, current, PhoneSessionBus::publishAudio)
                        audio = capture
                        capture.start()
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio capture unavailable; continuing video cast", e)
                        try { audio?.close() } catch (_: Exception) {}
                        audio = null
                        PhoneSessionBus.publishAudio(false)
                    }
                } else {
                    Log.i(TAG, "Audio capture skipped (permission or projection unavailable); video cast continues")
                    PhoneSessionBus.publishAudio(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Projection startup failed", e)
                stopping.set(true)
                teardown()
            }
        }
        return START_NOT_STICKY
    }

    private fun startEncoder(bitrate: Int, targetWidth: Int = WIDTH, targetHeight: Int = HEIGHT) {
        if (wifiLock == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifi = getSystemService(WifiManager::class.java)
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CPZ-low-latency")
                .also { it.acquire() }
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        val aligned = if (caps != null) {
            EncoderFormatPolicy.alignedDimensions(caps, targetWidth, targetHeight)
        } else {
            EncoderFormatPolicy.Dimensions(targetWidth, targetHeight)
        }
        activeWidth = aligned.width
        activeHeight = aligned.height
        val appliedSpec = EncoderFormatPolicy.configureWithFallback(
            enc, EncoderFormatPolicy.specsFor(aligned.width, aligned.height, bitrate, FPS)
        )
        codec = enc
        val newSurface = enc.createInputSurface()
        surface = newSurface
        enc.start()
        Log.i(TAG, "Encoder started: ${enc.name} tier=${appliedSpec.tier} at ${aligned.width}x${aligned.height}@${FPS}, ${bitrate / 1_000_000} Mbps")
        val existingDisplay = display
        if (existingDisplay == null) {
            display = projection?.createVirtualDisplay(
                "CastPro-Zero", aligned.width, aligned.height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newSurface, null, null
            )
        } else {
            existingDisplay.resize(aligned.width, aligned.height, resources.displayMetrics.densityDpi)
            existingDisplay.setSurface(newSurface)
        }
        sender?.sendStreamConfig(
            ControlMessage.StreamConfig(1, aligned.width, aligned.height, FPS, bitrate, 1, 3, 2)
        )
        val queue = BoundedFrameQueue(capacity = 6, pool = framePool)
        frameQueue = queue
        frameLogCount = 0
        firstFrameLogged = false
        active.set(true)
        PhoneSessionBus.publishStreaming(true)
        txThread = Thread({ txLoop(enc, queue) }, "CPZ-video-tx").also { it.start() }
        drainThread = Thread({ drain(enc, queue) }, "CPZ-encoder-drain").also { it.start() }
        if (statsThread == null) {
            statsThread = Thread({ statsLoop() }, "CPZ-phone-stats").also { it.start() }
        }
    }

    private fun registerThermalListener() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        powerManager = pm
        updateThermalStatus(pm.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            updateThermalStatus(status)
        }
        thermalListener = listener
        pm.addThermalStatusListener(listener)
    }

    private fun updateThermalStatus(status: Int) {
        thermalLevel = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        if (thermalLevel >= 1) {
            Log.w(TAG, "Thermal status changed: status=$status level=$thermalLevel")
        }
        if (thermalLevel >= 2) {
            handleHealthFeedback(ControlMessage.HealthFeedback(0, 0, FPS, 0))
        }
    }

    private fun handleHealthFeedback(feedback: ControlMessage.HealthFeedback) {
        val controller = abrController ?: return
        val transport = sender ?: return
        val congestionNow = transport.congestionDropCount()
        val congestionDelta = (congestionNow - lastTxCongestionCount).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        lastTxCongestionCount = congestionNow
        val decision = controller.evaluate(
            HealthSample(
                nowNs = System.nanoTime(),
                rttMs = transport.stats().rttMs,
                lossDelta = feedback.reassemblyDropsDelta,
                backpressureDelta = feedback.backpressureDropsDelta,
                txCongestionDropsDelta = congestionDelta,
                thermalLevel = thermalLevel
            )
        )
        activeBitrateBps = decision.targetBitrateBps
        if (decision.resolutionChanged) {
            maxLongSide = maxOf(decision.targetProfile.width, decision.targetProfile.height)
            val (targetW, targetH) = computeAspectFitSize(sourceDisplayWidth, sourceDisplayHeight, maxLongSide)
            startup.execute { reconfigureResolution(targetW, targetH, decision.targetBitrateBps) }
        } else if (decision.bitrateChanged) {
            try {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, decision.targetBitrateBps)
                })
                Log.i(TAG, "ABR adjusted bitrate=${decision.targetBitrateBps / 1_000_000.0} Mbps")
            } catch (e: Exception) {
                Log.w(TAG, "ABR bitrate update failed", e)
            }
        }
    }

    private fun reconfigureResolution(targetWidth: Int, targetHeight: Int, bitrate: Int) = synchronized(codecLock) {
        if (stopping.get() || projection == null) return@synchronized
        reconfiguring.set(true)
        try {
            active.set(false)
            frameQueue?.close()
            frameQueue = null
            listOf(drainThread, txThread).forEach { thread ->
                thread?.interrupt()
                try { thread?.join(400) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            drainThread = null; txThread = null
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            codec = null
            try { surface?.release() } catch (_: Exception) {}
            surface = null
            startEncoder(bitrate, targetWidth, targetHeight)
            Log.i(TAG, "ABR/resize switched resolution to ${targetWidth}x${targetHeight} @ ${bitrate / 1_000_000.0} Mbps")
        } catch (e: Exception) {
            Log.e(TAG, "Resolution reconfiguration failed", e)
            stopCaptureAsync()
        } finally {
            reconfiguring.set(false)
        }
    }

    private fun drain(enc: MediaCodec, queue: BoundedFrameQueue) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        val info = MediaCodec.BufferInfo()
        while (active.get()) {
            val index = try { enc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            if (index >= 0) {
                var borrowed: ByteArray? = null
                var size = 0
                var flags = 0
                var ptsNs = 0L
                try {
                    val output = enc.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        size = info.size
                        flags = info.flags
                        // Use System.nanoTime() so video and AudioCapture share one monotonic clock.
                        ptsNs = System.nanoTime().coerceAtLeast(0L)
                        val buf = framePool.acquire(size)
                        borrowed = buf
                        val view = output.duplicate()
                        view.position(info.offset)
                        view.limit(info.offset + size)
                        view.get(buf, 0, size)
                    }
                } finally {
                    // Immediately return MediaCodec output buffer before CSD/network work.
                    try { enc.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                }
                val buf = borrowed ?: continue
                try {
                    val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyframe = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val classified = EncoderOutputPolicy.classify(buf, size, isConfig, isKeyframe, VideoCodec.AVC)
                    classified.csdAnnexB?.let { sender?.sendCsd(1, it) }
                    if (isConfig && classified.csdAnnexB == null) {
                        Log.w(TAG, "CODEC_CONFIG had no valid Annex-B parameter sets")
                    }
                    if (classified.emitVideoFrame && classified.videoBytes != null) {
                        val enqueueResult = queue.enqueue(buf, size, classified.keyframe, ptsNs)
                        if (enqueueResult.backlogClearedForSync) {
                            requestEncoderSyncFrame(enc)
                        }
                    } else {
                        framePool.release(buf)
                    }
                } catch (e: Exception) {
                    framePool.release(buf)
                    Log.e(TAG, "Encoder output processing failed", e)
                    active.set(false)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val format = enc.outputFormat
                    Log.i(TAG, "INFO_OUTPUT_FORMAT_CHANGED: $format")
                    val csd0 = format.getByteBuffer("csd-0")?.duplicate()
                        ?.let { ByteArray(it.remaining()).also(it::get) }
                    val csd1 = format.getByteBuffer("csd-1")?.duplicate()
                        ?.let { ByteArray(it.remaining()).also(it::get) }
                    val csd2 = format.getByteBuffer("csd-2")?.duplicate()
                        ?.let { ByteArray(it.remaining()).also(it::get) }
                    val totalSize = listOfNotNull(csd0, csd1, csd2).sumOf { 4 + it.size }
                    if (totalSize > 0) {
                        val annexB = ByteArray(totalSize)
                        var offset = 0
                        listOfNotNull(csd0, csd1, csd2).forEach { nal ->
                            annexB[offset] = 0; annexB[offset + 1] = 0
                            annexB[offset + 2] = 0; annexB[offset + 3] = 1
                            offset += 4
                            nal.copyInto(annexB, offset)
                            offset += nal.size
                        }
                        val sets = AnnexB.extract(annexB, VideoCodec.AVC)
                        if (sets != null) sender?.sendCsd(1, sets.annexB())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoder format/CSD delivery failed", e)
                    active.set(false)
                }
            }
        }
        if (!stopping.get() && !reconfiguring.get()) stopCaptureAsync()
    }

    private fun txLoop(enc: MediaCodec, queue: BoundedFrameQueue) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        while (active.get()) {
            val frame = queue.poll(100) ?: continue
            try {
                if (!frame.keyframe && System.nanoTime() - frame.captureTimestampNs > MAX_TX_FRAME_AGE_NS) {
                    requestEncoderSyncFrame(enc)
                    continue
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(TAG, "First transmitted frame wall-clock=${System.nanoTime()} ns, codec=${enc.name}")
                }
                when (val outcome = sender?.sendVideoFrame(
                    frame.buffer, frame.keyframe, frame.captureTimestampNs, frame.length
                ) ?: SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)) {
                    is SendOutcome.Sent -> {
                        if (frameLogCount < 10) {
                            frameLogCount++
                            val nalTypes = AnnexB.nalTypes(frame.buffer, VideoCodec.AVC, frame.length)
                            Log.i(TAG, "TX frameId=${outcome.frameId} fragCount=${outcome.fragmentCount} size=${outcome.byteCount} keyframe=${frame.keyframe} NAL=$nalTypes")
                        }
                    }
                    is SendOutcome.NotSent -> {
                        val message = SendFailurePolicy.message(outcome.failure)
                        if (!SendFailurePolicy.isFatal(outcome.failure)) {
                            Log.w(TAG, "$message; requesting sync frame recovery")
                            requestEncoderSyncFrame(enc)
                        } else {
                            if (SendFailurePolicy.isExpected(outcome.failure)) Log.w(TAG, message)
                            else Log.e(TAG, message, outcome.cause)
                            PhoneSessionBus.publishStatus(message)
                            active.set(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authenticated LAN frame transmission failed", e)
                active.set(false)
            } finally {
                framePool.release(frame.buffer)
            }
        }
        if (!stopping.get() && !reconfiguring.get()) stopCaptureAsync()
    }

    private fun requestEncoderSyncFrame(enc: MediaCodec?) {
        try {
            enc?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Sync-frame request failed", e)
        }
    }

    private fun statsLoop() {
        var last = System.nanoTime()
        var lastFrames = 0L
        var lastBytes = 0L
        while (!stopping.get()) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            val now = System.nanoTime()
            val elapsed = (now - last).coerceAtLeast(1)
            val transport = sender ?: continue
            val encName = codec?.name ?: "video/avc"
            val snapshot = transport.stats()
            val fps = ((snapshot.framesSent - lastFrames) * 1_000_000_000L / elapsed).toInt()
            val mbps = ((snapshot.bytesSent - lastBytes) * 8_000f / elapsed)
            last = now
            lastFrames = snapshot.framesSent
            lastBytes = snapshot.bytesSent
            PhoneSessionBus.publish(telemetry.publishMeasured(transport.stats(fps, mbps, encName).copy(
                resolution = "${activeWidth}×${activeHeight}", audioActive = audio?.isActive() == true
            )))
            Log.i(TAG, "PHONE_STATS: fps=$fps mbps=${"%.2f".format(mbps)} res=${activeWidth}x${activeHeight} cong=${transport.congestionDropCount()}")
        }
    }

    private fun stopCaptureAsync() {
        if (!stopping.compareAndSet(false, true)) return
        startup.cancel()
        telemetry.reset()
        shutdownExecutor.execute { teardown() }
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        startup.cancel()
        telemetry.reset()
        teardown()
    }

    /** Idempotent resource release; safe to call off the main thread. */
    @Synchronized
    private fun teardown() {
        active.set(false)
        thermalListener?.let { listener ->
            try { powerManager?.removeThermalStatusListener(listener) } catch (_: Exception) {}
        }
        thermalListener = null
        powerManager = null
        abrController = null
        frameQueue?.close()
        frameQueue = null
        listOf(drainThread, txThread, statsThread).forEach { thread ->
            thread?.interrupt()
            try { thread?.join(400) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        drainThread = null; txThread = null; statsThread = null
        framePool.clear()
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
        try { audio?.close() } catch (_: Exception) {}
        audio = null
        PhoneSessionBus.publishAudio(false)
        try { sender?.sendControl(ControlMessage.Stop(0)) } catch (_: Exception) {}
        try { sender?.close() } catch (_: Exception) {}
        sender = null
        PhoneSessionBus.publishStreaming(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        shutdownExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun computeAspectFitSize(sourceW: Int, sourceH: Int, maxLong: Int): Pair<Int, Int> {
        val w = sourceW.coerceAtLeast(2)
        val h = sourceH.coerceAtLeast(2)
        val longSide = maxOf(w, h)
        val scale = if (longSide > maxLong) maxLong.toDouble() / longSide.toDouble() else 1.0
        val outW = (((w * scale).toInt() + 1) and 1.inv()).coerceAtLeast(2)
        val outH = (((h * scale).toInt() + 1) and 1.inv()).coerceAtLeast(2)
        return outW to outH
    }

    companion object {
        private const val MAX_TX_FRAME_AGE_NS = 200_000_000L
        const val ACTION_STOP = "com.castpro.zero.phone.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BITRATE = "bitrate_mbps"
        const val EXTRA_TV_IP = "tv_ip"
        const val EXTRA_PAIRING_TOKEN = "pairing_token"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_UDP_PORT = "udp_port"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 60
        private const val DEFAULT_TCP_PORT = 45670
        private const val DEFAULT_UDP_PORT = 45671
        private const val CHANNEL = "cpz_capture"
        private const val TAG = "CPZ-Capture"
    }
}
