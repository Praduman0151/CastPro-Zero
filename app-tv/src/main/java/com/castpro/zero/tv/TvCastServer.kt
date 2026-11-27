package com.castpro.zero.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import com.castpro.zero.protocol.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class TvCastServer(
    private val callbacks: Callbacks = object : Callbacks {},
    val pairingToken: String = createPairingToken()
) {
    interface Callbacks {
        fun onStatus(value: String) {}
        fun onAddress(ip: String, token: String) {}
        fun onStreamConfig(config: ControlMessage.StreamConfig) {}
        fun onFirstFrameRendered() {}
        fun onTelemetry(value: String) {}
        fun onSessionIdle() {}
    }

    companion object {
        const val TCP_PORT = 45670
        const val UDP_PORT = 45671
        const val DISCOVERY_PORT = 45672
        private const val TAG = "CPZ-TV"
        private const val MAX_CONCURRENT_HANDSHAKES = 4
        private fun createPairingToken(): String = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var audio: DatagramSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    @Volatile private var control: Socket? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioBuffer: OrderedAudioPlaybackBuffer? = null
    private val clockOffset = ClockOffsetEstimator()
    private val avSync = AvSyncController(maxDriftNs = 25_000_000L)
    private val audioTimestamp = AudioTimestamp()
    private val audioFramesWritten = AtomicLong(0L)
    @Volatile private var latestAudioCaptureNs = 0L
    @Volatile private var latestVideoRenderedCaptureNs = 0L

    private val health = StreamHealthCounters()
    private val decoderLock = Any()
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var sessionId = 0L
    @Volatile private var authenticated = false
    @Volatile private var authenticatedPeer: InetAddress? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var pendingCsdForDecoder: ByteArray? = null
    @Volatile private var stream: ControlMessage.StreamConfig? = null
    private val gate = DecodeGate(VideoCodec.AVC)
    private val csdTracker = DecoderCsdTracker()
    private var videoQueue = TvVideoFrameQueue(capacity = 6)
    private val controlSendQueue = TvControlSendQueue(capacity = 64)
    private val fpsTracker = VsyncFpsTracker()

    private val dropped = AtomicLong(0L)
    private val bytesRenderedWindow = AtomicLong(0L)
    private val activeHandshakes = AtomicInteger(0)
    private val rttState = RttTelemetryState()
    @Volatile private var liveMbps = 0.0
    @Volatile private var renderedFps = 0L
    @Volatile private var tvLocalPipelineMs = 0L
    @Volatile private var decoderName = "idle"
    @Volatile private var resolution = "—"

    private val sequence = AtomicLong(0L)
    private val lastPliNs = AtomicLong(0L)
    private var firstTenFrames = 0
    @Volatile private var discoveryStarted = false
    @Volatile private var firstRenderedFrameLogged = false

    // Ring tracking input ptsUs -> local TV arrival time for decode+render latency telemetry
    private val ptsTrackLock = Any()
    private val ptsKeys = LongArray(64)
    private val ptsArrivalNs = LongArray(64)
    private var ptsIdx = 0

    private val lifecycleExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "CPZ-TV-lifecycle") }

    @Volatile private var controlThread: Thread? = null
    @Volatile private var telemetryThread: Thread? = null
    @Volatile private var controlTxThread: Thread? = null
    @Volatile private var videoRxThread: Thread? = null
    @Volatile private var audioRxThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        controlThread = Thread({ listenLoop() }, "CPZ-TV-control").also { it.start() }
        telemetryThread = Thread({ telemetryLoop() }, "CPZ-TV-telemetry").also { it.start() }
        controlTxThread = Thread({ controlTxLoop() }, "CPZ-TV-control-tx").also { it.start() }
    }

    fun refreshNetwork() {
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { audio?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
    }

    private data class LanAddress(val address: Inet4Address, val broadcast: Inet4Address?)

    private fun privateAddress(): LanAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    ni.interfaceAddresses.mapNotNull { ia ->
                        val a = ia.address
                        if (a is Inet4Address && LocalAddressPolicy.isAllowed(a.hostAddress ?: "")) {
                            LanAddress(a, ia.broadcast as? Inet4Address)
                        } else null
                    }
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun listenLoop() {
        while (running.get()) {
            try {
                val lan = privateAddress()
                if (lan == null) {
                    callbacks.onStatus("No private IPv4 network. Waiting for Wi-Fi / Ethernet…")
                    Thread.sleep(1200)
                    continue
                }
                val ip = lan.address.hostAddress ?: continue
                val tcp = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(lan.address, TCP_PORT), 4)
                }
                val video = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(lan.address, UDP_PORT))
                    receiveBufferSize = 4 * 1024 * 1024
                    soTimeout = 5
                    try { trafficClass = 0x88 } catch (_: Exception) {}
                }
                val audioSocket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(lan.address, TvReceiverPolicy.audioPortFor(UDP_PORT)))
                        receiveBufferSize = 2 * 1024 * 1024
                        soTimeout = 20
                        try { trafficClass = 0xB8 } catch (_: Exception) {}
                    }.also { audio = it }
                } catch (e: Exception) {
                    if (TvReceiverPolicy.audioFailureIsNonFatal()) {
                        Log.w(TAG, "Audio receiver unavailable; video cast continues", e)
                    }
                    null
                }
                Log.i(
                    TAG,
                    "UDP receive buffer requested=4 MiB actual=${video.receiveBufferSize}; audio port=${TvReceiverPolicy.audioPortFor(UDP_PORT)} bound=${audioSocket != null}"
                )
                tcpServer = tcp
                udp = video
                callbacks.onAddress(ip, pairingToken)
                callbacks.onStatus("Receiver ready · $ip")
                startDiscoveryResponder()
                videoRxThread = Thread({ receiveVideo(video) }, "CPZ-TV-udp").also { it.start() }
                if (audioSocket != null) {
                    audioRxThread = Thread({ receiveAudio(audioSocket) }, "CPZ-TV-udp-audio").also { it.start() }
                }
                while (running.get() && !tcp.isClosed) {
                    try {
                        val incoming = tcp.accept()
                        if (activeHandshakes.get() >= MAX_CONCURRENT_HANDSHAKES) {
                            try { incoming.close() } catch (_: Exception) {}
                            continue
                        }
                        activeHandshakes.incrementAndGet()
                        Thread({ handleClientSession(incoming) }, "CPZ-TV-session").start()
                    } catch (e: SocketException) {
                        if (running.get()) Log.i(TAG, "Network listener rebinding", e)
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "LAN listener unavailable; retrying", e)
                    callbacks.onStatus("Network unavailable · retrying")
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            } finally {
                try { tcpServer?.close() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                try { audio?.close() } catch (_: Exception) {}
                try { videoRxThread?.join(300) } catch (_: InterruptedException) {}
                try { audioRxThread?.join(300) } catch (_: InterruptedException) {}
                tcpServer = null
                udp = null
                audio = null
            }
        }
    }

    private fun handleClientSession(socket: Socket) {
        var handshakeCounted = true
        try {
            socket.soTimeout = 4_000
            socket.tcpNoDelay = true
            try { socket.trafficClass = 0x10 } catch (_: Exception) {}
            val sid = SessionHandshake.server(socket, pairingToken)
            activeHandshakes.decrementAndGet()
            handshakeCounted = false

            // Swap out any stale previous TCP session immediately on valid auth so reconnects never wait 15s.
            val oldSocket = synchronized(this) {
                val prev = control
                control = socket
                sessionId = sid
                authenticatedPeer = socket.inetAddress
                authenticated = true
                firstTenFrames = 0
                firstRenderedFrameLogged = false
                lastPliNs.set(0L)
                latestAudioCaptureNs = 0L
                latestVideoRenderedCaptureNs = 0L
                clockOffset.reset()
                audioBuffer = OrderedAudioPlaybackBuffer(sid)
                health.reset()
                dropped.set(0L)
                fpsTracker.reset()
                controlSendQueue.clear()
                prev
            }
            if (oldSocket != null && oldSocket !== socket) {
                try { oldSocket.close() } catch (_: Exception) {}
            }

            socket.soTimeout = 12_000
            callbacks.onStatus("Authenticated phone session")
            val input = socket.getInputStream()
            while (running.get() && !socket.isClosed && authenticated && control === socket) {
                val frame = ControlFraming.read(input) ?: break
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.StreamConfig -> {
                        if (message.codec != 1 || message.width !in 1..8192 || message.height !in 1..8192) {
                            Log.w(TAG, "Rejected invalid stream config")
                            continue
                        }
                        val previous = stream
                        if (previous != null &&
                            (previous.width != message.width || previous.height != message.height || previous.codec != message.codec)
                        ) {
                            stopDecoder()
                            csd = null
                            pendingCsdForDecoder = null
                            gate.onDecoderRecreated()
                        }
                        stream = message
                        resolution = "${message.width}×${message.height}"
                        callbacks.onStreamConfig(message)
                        lifecycleExecutor.execute { configureDecoderIfReady() }
                    }
                    is ControlMessage.VideoCsd -> applyControlCsd(message)
                    is ControlMessage.AudioConfig -> {
                        if (TvReceiverPolicy.isSupportedAudioConfig(
                                message.sampleRateHz, message.channelCount, message.bitDepth, message.encoding
                            )
                        ) {
                            configureAudioTrack(message)
                        } else {
                            Log.w(TAG, "Rejected unsupported audio config: $message")
                        }
                    }
                    is ControlMessage.Ping -> controlSendQueue.offer(
                        ControlMessage.Pong(message.timestampNs, message.pingSequence)
                    )
                    is ControlMessage.Pong -> rttState.acceptPong(message, System.nanoTime())
                    is ControlMessage.RttReport -> rttState.acceptPhoneReport(message.rttMs)
                    is ControlMessage.Stop -> {
                        clearSession("Casting stopped")
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.i(TAG, "Control peer disconnected", e)
        } finally {
            if (handshakeCounted) activeHandshakes.decrementAndGet()
            if (control === socket) {
                control = null
                clearSession("Phone disconnected")
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun controlTxLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        while (running.get()) {
            val msg = try {
                controlSendQueue.poll(100) ?: continue
            } catch (_: InterruptedException) {
                continue
            }
            val peer = control ?: continue
            if (peer.isClosed || !authenticated) continue
            try {
                ControlFraming.write(
                    peer.getOutputStream(),
                    ControlFrame(
                        type = ControlMessageCodec.typeOf(msg),
                        sequence = sequence.getAndIncrement(),
                        sessionId = sessionId,
                        payload = ControlMessageCodec.encode(msg)
                    )
                )
            } catch (_: Exception) {
                // Socket errors are cleaned up by the session control reader.
            }
        }
    }

    private fun startDiscoveryResponder() {
        if (discoveryStarted) return
        discoveryStarted = true
        Thread({
            var localSocket: DatagramSocket? = null
            var lastAdvertiseNs = 0L
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 250
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                localSocket = socket
                discovery = socket
                while (running.get()) {
                    val request = DatagramPacket(ByteArray(256), 256)
                    try {
                        socket.receive(request)
                        if (!authenticated) {
                            val text = String(request.data, 0, request.length, Charsets.UTF_8).trim()
                            if (text == Discovery.REQUEST_MAGIC) {
                                val lanForReply = privateAddress()
                                val replyIp = lanForReply?.address?.hostAddress
                                if (replyIp != null) {
                                    val reply = advertisement(replyIp)
                                    socket.send(DatagramPacket(reply, reply.size, request.address, request.port))
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Allows periodic advertisement and shutdown checks.
                    }
                    // Stop interface polling & LAN broadcasts while actively casting to save TV CPU & radio airtime.
                    if (authenticated) continue
                    val now = System.nanoTime()
                    if (now - lastAdvertiseNs < 1_500_000_000L) continue
                    val lan = privateAddress() ?: continue
                    val ip = lan.address.hostAddress ?: continue
                    val payload = advertisement(ip)
                    val broadcastAddress = lan.broadcast ?: InetAddress.getByName("255.255.255.255")
                    socket.send(DatagramPacket(payload, payload.size, broadcastAddress, DISCOVERY_PORT))
                    lastAdvertiseNs = now
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Discovery responder unavailable", e)
            } finally {
                discoveryStarted = false
                try { localSocket?.close() } catch (_: Exception) {}
                if (discovery === localSocket) discovery = null
            }
        }, "CPZ-TV-discovery").start()
    }

    /** Same wire format as the offline QR code, so phone discovery and scanning share one parser. */
    private fun advertisement(ip: String): ByteArray = PairingPayloadCodec.encode(
        TvPairingPayload(
            ipAddress = ip,
            tcpPort = TCP_PORT,
            udpPort = UDP_PORT,
            pairingToken = pairingToken,
            expiryEpochSeconds = System.currentTimeMillis() / 1000L + 3600L
        )
    ).toByteArray(Charsets.UTF_8)

    private fun applyControlCsd(message: ControlMessage.VideoCsd) {
        val data = message.annexB
        val valid = message.codec == 1 && AnnexB.extract(data, VideoCodec.AVC) != null
        synchronized(decoderLock) {
            when (csdTracker.actionForControlCsd(decoder != null, valid)) {
                CsdAction.REJECT -> Log.w(TAG, "Rejected invalid Annex-B AVC CSD")
                CsdAction.CONFIGURE -> {
                    csd = data.copyOf()
                    lifecycleExecutor.execute { configureDecoderIfReady() }
                }
                CsdAction.QUEUE_NOW -> {
                    csd = data.copyOf()
                    pendingCsdForDecoder = csd
                    videoQueue.signalWakeup()
                }
                CsdAction.NONE -> {
                    csd = data.copyOf()
                    if (gate.state != DecodeGate.State.OPEN) {
                        pendingCsdForDecoder = csd
                        videoQueue.signalWakeup()
                    }
                }
            }
        }
    }

    @Synchronized private fun configureAudioTrack(config: ControlMessage.AudioConfig) {
        stopAudio()
        try {
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(config.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                Log.w(TAG, "AudioTrack minimum buffer unavailable: $min")
                return
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(config.sampleRateHz)
                .setChannelMask(channelMask)
                .build()
            val targetBufferBytes = maxOf(min, 4 * 3840) // ~80ms ceiling or hardware min
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(targetBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()
            audioFramesWritten.set(0L)
            track.play()
            audioTrack = track
            Log.i(TAG, "AudioTrack active: ${config.sampleRateHz} Hz stereo PCM16, minBuffer=$min targetBuffer=$targetBufferBytes")
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack setup failed", e)
            stopAudio()
        }
    }

    @Synchronized private fun stopAudio() {
        val track = audioTrack
        audioTrack = null
        audioFramesWritten.set(0L)
        if (track != null) try { track.stop() } catch (_: Exception) {}
        if (track != null) try { track.release() } catch (_: Exception) {}
    }

    /** Authenticated audio datagrams reordered and paced against rendered video timestamps. */
    private fun receiveAudio(socket: DatagramSocket) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val bytes = ByteArray(MAX_UDP_DATAGRAM_BYTES)
        val packet = DatagramPacket(bytes, bytes.size)
        var lastSession = 0L
        while (running.get() && !socket.isClosed) {
            try {
                packet.length = bytes.size
                socket.receive(packet)
                if (!authenticated) continue
                if (packet.address != authenticatedPeer || !LocalAddressPolicy.isAllowed(packet.address.hostAddress ?: "")) {
                    continue
                }
                val frame = try {
                    AudioFrame.decode(packet.data.copyOf(packet.length))
                } catch (_: Exception) {
                    continue
                }
                if (frame.sessionId != sessionId) continue
                val track = audioTrack ?: continue
                val buf = audioBuffer ?: continue
                if (lastSession != sessionId) {
                    lastSession = sessionId
                    buf.clear()
                }
                val arrivalNs = System.nanoTime()
                clockOffset.observe(frame.timestampNs, arrivalNs)
                val senderNowNs = clockOffset.toSenderTimeNs(arrivalNs)
                buf.offer(frame, senderNowNs)
                drainAudioBuffer(track, buf, senderNowNs)
            } catch (_: SocketTimeoutException) {
                val track = audioTrack ?: continue
                val buf = audioBuffer ?: continue
                drainAudioBuffer(track, buf, clockOffset.toSenderTimeNs(System.nanoTime()))
            } catch (e: SocketException) {
                if (running.get() && !socket.isClosed) Log.w(TAG, "Audio UDP socket error", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Audio UDP receive error", e)
            }
        }
    }

    private fun drainAudioBuffer(track: AudioTrack, buf: OrderedAudioPlaybackBuffer, senderNowNs: Long) {
        while (running.get() && audioTrack === track) {
            val head = buf.peekPlayable(senderNowNs) ?: break
            val queuedPlaybackDelayNs = computeQueuedAudioLatencyNs(track)
            val audioPlayheadCaptureNs = (latestAudioCaptureNs - queuedPlaybackDelayNs).coerceAtLeast(0L)
            when (avSync.evaluate(latestVideoRenderedCaptureNs, audioPlayheadCaptureNs)) {
                AvSyncAction.DROP_STALE_AUDIO -> {
                    if (buf.queuedFrames() > 1 && buf.dropHead()) continue
                }
                AvSyncAction.HOLD_AUDIO -> return
                AvSyncAction.PLAY_NORMAL -> Unit
            }
            val written = try {
                track.write(head.frame.pcm, head.offset, head.remaining, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Exception) {
                return
            }
            if (written > 0) {
                audioFramesWritten.addAndGet((written / 4).toLong())
                latestAudioCaptureNs = head.frame.timestampNs
                buf.advanceHead(written)
                if (written < head.remaining) break // Track buffer saturated; keep remaining tail for next turn.
            } else {
                break
            }
        }
    }

    private fun computeQueuedAudioLatencyNs(track: AudioTrack): Long {
        val totalFrames = audioFramesWritten.get()
        if (totalFrames <= 0L) return 0L
        return try {
            if (track.getTimestamp(audioTimestamp)) {
                val bufferedFrames = (totalFrames - audioTimestamp.framePosition).coerceIn(0L, 48_000L)
                bufferedFrames * 1_000_000_000L / 48_000L
            } else {
                40_000_000L
            }
        } catch (_: Exception) {
            40_000_000L
        }
    }

    private fun configureDecoderIfReady() {
        val codecToStart: MediaCodec
        val cfg: ControlMessage.StreamConfig
        val data: ByteArray
        val target: Surface
        synchronized(decoderLock) {
            cfg = stream ?: return
            data = csd ?: return
            target = surface ?: return
            if (!target.isValid || decoder != null) return
        }
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            configureDecoderTiers(codec, cfg, target)
            codec.start()
            codec.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                fpsTracker.onFrameRendered(nanoTime)
                val captureNs = presentationTimeUs * 1_000L
                latestVideoRenderedCaptureNs = captureNs
                val arrivalNs = lookupPtsArrival(presentationTimeUs)
                if (arrivalNs > 0L) {
                    val sampleMs = ((System.nanoTime() - arrivalNs).coerceAtLeast(0L)) / 1_000_000L
                    tvLocalPipelineMs = if (tvLocalPipelineMs == 0L) sampleMs else ((tvLocalPipelineMs * 3 + sampleMs) / 4)
                }
                if (!firstRenderedFrameLogged) {
                    firstRenderedFrameLogged = true
                    Log.i(TAG, "FIRST_RENDERED_FRAME elapsedRealtimeNs=$nanoTime presentationTimeUs=$presentationTimeUs")
                    callbacks.onFirstFrameRendered()
                }
            }, null)
            codecToStart = codec
        } catch (e: Exception) {
            Log.e(TAG, "Decoder setup failed", e)
            health.onSetupFailureOnly()
            requestPliAsync(PLI_REASON_DECODER_SETUP)
            return
        }

        synchronized(decoderLock) {
            val currentTarget = surface
            if (!running.get() || currentTarget == null || !currentTarget.isValid || decoder != null) {
                try { codecToStart.stop(); codecToStart.release() } catch (_: Exception) {}
                return
            }
            gate.onDecoderRecreated()
            csdTracker.onDecoderCreated()
            pendingCsdForDecoder = csd?.copyOf() ?: data.copyOf()
            decoder = codecToStart
            decoderName = codecToStart.name
            videoQueue = TvVideoFrameQueue(capacity = 6)
            Log.i(TAG, "Active decoder: $decoderName at ${cfg.width}x${cfg.height}@${cfg.fps}")
            Thread({ feedDecoder(codecToStart, videoQueue) }, "CPZ-TV-decoder-feed").start()
            Thread({ drainOutput(codecToStart) }, "CPZ-TV-output-drain").start()
        }
    }

    private fun configureDecoderTiers(codec: MediaCodec, cfg: ControlMessage.StreamConfig, target: Surface) {
        val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val lowLatencySupported = Build.VERSION.SDK_INT >= 30 &&
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)

        fun buildFormat(useOperatingRate: Boolean, usePriority: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height).apply {
                if (lowLatencySupported) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (usePriority) setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (useOperatingRate) setInteger(MediaFormat.KEY_OPERATING_RATE, maxOf(cfg.fps * 2, 120))
                setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }

        try {
            codec.configure(buildFormat(useOperatingRate = true, usePriority = true), target, null, 0)
        } catch (_: Exception) {
            try {
                codec.configure(buildFormat(useOperatingRate = false, usePriority = true), target, null, 0)
            } catch (_: Exception) {
                val base = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height).apply {
                    if (lowLatencySupported) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                codec.configure(base, target, null, 0)
            }
        }
    }

    /** Single owner of MediaCodec input buffers (both CSD and video access units). */
    private fun feedDecoder(codec: MediaCodec, queue: TvVideoFrameQueue) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        while (running.get() && decoder === codec) {
            val csdToQueue = synchronized(decoderLock) {
                val pending = pendingCsdForDecoder
                pendingCsdForDecoder = null
                pending
            }
            if (csdToQueue != null) {
                queueCsdOnFeedThread(codec, csdToQueue)
            }
            val frame = queue.poll(50) ?: continue
            if (decoder !== codec) break
            if (!gate.allowFrame(frame.bytes)) continue
            try {
                val index = codec.dequeueInputBuffer(8_000)
                if (index < 0) {
                    dropped.incrementAndGet()
                    health.onDecoderBackpressure()
                    gate.resetForDiscontinuity()
                    continue
                }
                val input = codec.getInputBuffer(index) ?: continue
                if (frame.bytes.size > input.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    dropped.incrementAndGet()
                    health.onSetupFailureOnly()
                    gate.resetForDiscontinuity()
                    requestPliAsync(5)
                    continue
                }
                input.clear()
                input.put(frame.bytes)
                val ptsUs = frame.captureTimestampNs / 1000L
                recordPtsArrival(ptsUs, System.nanoTime())
                codec.queueInputBuffer(index, 0, frame.bytes.size, ptsUs, 0)
                bytesRenderedWindow.addAndGet(frame.bytes.size.toLong())
            } catch (e: Exception) {
                if (running.get() && decoder === codec) {
                    Log.w(TAG, "Decoder input failed; resetting decoder", e)
                    gate.resetForDiscontinuity()
                    health.onSetupFailureOnly()
                    lifecycleExecutor.execute { stopDecoder() }
                    requestPliAsync(PLI_REASON_DECODER_SETUP)
                }
                break
            }
        }
    }

    private fun queueCsdOnFeedThread(codec: MediaCodec, data: ByteArray) {
        if (AnnexB.extract(data, VideoCodec.AVC) == null) {
            Log.w(TAG, "Refusing invalid Annex-B AVC CSD after codec.start()")
            health.onSetupFailureOnly()
            requestPliAsync(7)
            return
        }
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                Log.w(TAG, "No input buffer available for CSD")
                health.onSetupFailureOnly()
                requestPliAsync(7)
                return
            }
            val input = codec.getInputBuffer(index) ?: run {
                health.onSetupFailureOnly()
                requestPliAsync(7)
                return
            }
            if (data.size > input.capacity()) {
                health.onSetupFailureOnly()
                requestPliAsync(7)
                return
            }
            input.clear()
            input.put(data)
            codec.queueInputBuffer(index, 0, data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            gate.onCsd(data)
            csdTracker.onCsdQueued()
        } catch (e: Exception) {
            Log.w(TAG, "queueCsd failed", e)
            health.onSetupFailureOnly()
            requestPliAsync(7)
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        val info = MediaCodec.BufferInfo()
        while (running.get() && decoder === codec) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                if (running.get() && decoder === codec) {
                    Log.w(TAG, "Decoder output drain failed; restarting decoder", e)
                    health.onSetupFailureOnly()
                    lifecycleExecutor.execute { stopDecoder() }
                    requestPliAsync(PLI_REASON_DECODER_SETUP)
                }
                break
            }
            if (index >= 0) {
                try {
                    codec.releaseOutputBuffer(index, true)
                } catch (_: Exception) {
                    dropped.incrementAndGet()
                    gate.resetForDiscontinuity()
                    requestPliAsync(3)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val hasCrop = format.containsKey("crop-left") && format.containsKey("crop-right") &&
                    format.containsKey("crop-top") && format.containsKey("crop-bottom")
                val cropLeft = if (hasCrop) format.getInteger("crop-left") else 0
                val cropTop = if (hasCrop) format.getInteger("crop-top") else 0
                val cropRight = if (hasCrop) format.getInteger("crop-right") else width - 1
                val cropBottom = if (hasCrop) format.getInteger("crop-bottom") else height - 1
                val displayWidth = (cropRight - cropLeft + 1).coerceAtLeast(1)
                val displayHeight = (cropBottom - cropTop + 1).coerceAtLeast(1)
                val crop = if (hasCrop) " crop=$cropLeft,$cropTop-$cropRight,$cropBottom" else ""
                Log.i(
                    TAG,
                    "INFO_OUTPUT_FORMAT_CHANGED width=$width height=$height$crop color-format=${if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) format.getInteger(MediaFormat.KEY_COLOR_FORMAT) else -1}"
                )
                resolution = "$displayWidth×$displayHeight"
                val baseConfig = stream ?: ControlMessage.StreamConfig(1, displayWidth, displayHeight, 60, 20_000_000, 1, 3, 2)
                callbacks.onStreamConfig(baseConfig.copy(width = displayWidth, height = displayHeight))
            }
        }
    }

    /** Dedicated UDP socket receiver; never blocks on MediaCodec or TCP writes. */
    private fun receiveVideo(socket: DatagramSocket) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        var reassembler: VideoReassembler? = null
        var reassemblerSession = 0L
        val bytes = ByteArray(MAX_UDP_DATAGRAM_BYTES)
        val packet = DatagramPacket(bytes, bytes.size)
        while (running.get() && !socket.isClosed) {
            try {
                packet.length = bytes.size
                socket.receive(packet)
                if (!authenticated) continue
                if (packet.address != authenticatedPeer || !LocalAddressPolicy.isAllowed(packet.address.hostAddress ?: "")) {
                    dropped.incrementAndGet()
                    continue
                }
                if (reassembler == null || reassemblerSession != sessionId) {
                    reassembler = VideoReassembler(sessionId, frameDeadlineNs = PRODUCTION_FRAME_DEADLINE_NS, maxInFlight = 6)
                    reassemblerSession = sessionId
                }
                val nowNs = System.nanoTime()
                val result = reassembler.accept(packet.data, nowNs, packet.length)
                if (result.dropped > 0) {
                    dropped.addAndGet(result.dropped.toLong())
                    health.onReassemblyDrop()
                    gate.resetForDiscontinuity()
                    videoQueue.markDiscontinuity()
                }
                if (result.requestPli) {
                    gate.resetForDiscontinuity()
                    requestPliAsync(PLI_REASON_REASSEMBLY)
                }
                val nacks = reassembler.collectNacks(nowNs)
                nacks.forEach { controlSendQueue.offer(it) }

                for (frame in result.frames) {
                    clockOffset.observe(frame.captureTimestampNs, nowNs)
                    if (firstTenFrames < 10) {
                        firstTenFrames++
                        val nalTypes = AnnexB.nalTypes(frame.bytes, VideoCodec.AVC)
                        val keyframe = AnnexB.isIdr(frame.bytes, VideoCodec.AVC)
                        Log.i(
                            TAG,
                            "RX frameId=${frame.frameId} fragCount=${(frame.bytes.size + MAX_VIDEO_PAYLOAD_BYTES - 1) / MAX_VIDEO_PAYLOAD_BYTES} size=${frame.bytes.size} keyframe=$keyframe NAL=$nalTypes"
                        )
                    }
                    val offered = videoQueue.offer(frame)
                    if (offered.discontinuityRequired) {
                        dropped.incrementAndGet()
                        health.onDecoderBackpressure()
                        gate.resetForDiscontinuity()
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (authenticated && reassembler != null) {
                    val nowNs = System.nanoTime()
                    reassembler.collectNacks(nowNs).forEach { controlSendQueue.offer(it) }
                    val (expCount, needPli) = reassembler.expireWithCount(nowNs)
                    if (expCount > 0) {
                        dropped.addAndGet(expCount.toLong())
                        health.onReassemblyDrop()
                        gate.resetForDiscontinuity()
                        videoQueue.markDiscontinuity()
                    }
                    if (needPli) {
                        requestPliAsync(PLI_REASON_REASSEMBLY)
                    }
                }
            } catch (e: SocketException) {
                if (running.get() && !socket.isClosed) Log.w(TAG, "UDP socket error", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "UDP receive error", e)
            }
        }
    }

    /** Non-blocking PLI enqueue coalesced at 100 ms so neither UI nor UDP threads block on TCP. */
    private fun requestPliAsync(reason: Int) {
        if (!authenticated || control == null) return
        val now = System.nanoTime()
        val prev = lastPliNs.get()
        if (now - prev < PLI_COALESCE_NS) return
        if (!lastPliNs.compareAndSet(prev, now)) return
        health.onPli(reason)
        controlSendQueue.offer(ControlMessage.Pli(reason))
    }

    fun attachSurface(target: Surface) {
        surface = target
        lifecycleExecutor.execute {
            configureDecoderIfReady()
            if (authenticated && csd != null && stream != null) {
                gate.resetForDiscontinuity()
                requestPliAsync(PLI_REASON_SURFACE)
            }
        }
    }

    fun detachSurface() {
        surface = null
        gate.resetForDiscontinuity()
        val toStop = synchronized(decoderLock) {
            val current = decoder
            decoder = null
            pendingCsdForDecoder = null
            videoQueue.clear()
            csdTracker.onDecoderStopped()
            decoderName = "idle"
            current
        }
        if (toStop != null) {
            lifecycleExecutor.execute {
                try { toStop.stop() } catch (_: Exception) {}
                try { toStop.release() } catch (_: Exception) {}
            }
        }
    }

    private fun clearSession(message: String) {
        authenticated = false
        authenticatedPeer = null
        sessionId = 0L
        stream = null
        csd = null
        pendingCsdForDecoder = null
        gate.onDecoderRecreated()
        rttState.reset()
        clockOffset.reset()
        stopAudio()
        audioBuffer = null
        health.reset()
        dropped.set(0L)
        bytesRenderedWindow.set(0L)
        liveMbps = 0.0
        renderedFps = 0L
        tvLocalPipelineMs = 0L
        resolution = "—"
        fpsTracker.reset()
        controlSendQueue.clear()
        videoQueue.clear()
        stopDecoder()
        callbacks.onSessionIdle()
        callbacks.onStatus(message)
    }

    fun telemetry(): String {
        val h = health.snapshot()
        return "Rendered FPS: $renderedFps · TCP RTT: ${if (rttState.rttMs >= 0) "${rttState.rttMs}ms" else "n/a"} · TV Latency: ${tvLocalPipelineMs}ms · UDP dropped: ${dropped.get()} (${TvReceiverPolicy.formatHealth(h)}) · PLI: ${h.pliCount} · ${"%.2f".format(liveMbps)} Mbps · $resolution · $decoderName"
    }

    private fun telemetryLoop() {
        var previous = System.nanoTime()
        var lastLoss = 0L
        var lastBusy = 0L
        while (running.get()) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                if (!running.get()) break
            }
            val now = System.nanoTime()
            val elapsed = (now - previous).coerceAtLeast(1L)
            val bytesWindow = bytesRenderedWindow.getAndSet(0L)
            liveMbps = bytesWindow * 8.0 / elapsed * 1000.0
            renderedFps = fpsTracker.sampleFps(previous, now)
            previous = now
            if (authenticated) {
                val pingSequence = sequence.getAndIncrement()
                rttState.recordPing(now, pingSequence)
                controlSendQueue.offer(ControlMessage.Ping(now, pingSequence))
                val snap = health.snapshot()
                val lossDelta = (snap.reassemblyDrops - lastLoss).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val busyDelta = (snap.backpressureDrops - lastBusy).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                lastLoss = snap.reassemblyDrops
                lastBusy = snap.backpressureDrops
                controlSendQueue.offer(
                    ControlMessage.HealthFeedback(
                        reassemblyDropsDelta = lossDelta,
                        backpressureDropsDelta = busyDelta,
                        renderedFps = renderedFps.toInt(),
                        tvPipelineMs = tvLocalPipelineMs.toInt()
                    )
                )
            } else {
                lastLoss = 0L
                lastBusy = 0L
            }
            callbacks.onTelemetry(telemetry())
        }
    }

    private fun recordPtsArrival(ptsUs: Long, arrivalNs: Long) {
        synchronized(ptsTrackLock) {
            ptsKeys[ptsIdx] = ptsUs
            ptsArrivalNs[ptsIdx] = arrivalNs
            ptsIdx = (ptsIdx + 1) and 63
        }
    }

    private fun lookupPtsArrival(ptsUs: Long): Long {
        synchronized(ptsTrackLock) {
            for (i in 0 until 64) {
                if (ptsKeys[i] == ptsUs) return ptsArrivalNs[i]
            }
            return 0L
        }
    }

    private fun stopDecoder() {
        val current = synchronized(decoderLock) {
            val c = decoder
            decoder = null
            pendingCsdForDecoder = null
            videoQueue.clear()
            csdTracker.onDecoderStopped()
            decoderName = "idle"
            c
        }
        if (current != null) try { current.stop() } catch (_: Exception) {}
        if (current != null) try { current.release() } catch (_: Exception) {}
    }

    fun stop() {
        running.set(false)
        videoQueue.close()
        clearSession("Receiver stopped")
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { audio?.close() } catch (_: Exception) {}
        try { discovery?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
        listOf(controlThread, telemetryThread, controlTxThread, videoRxThread, audioRxThread).forEach { t ->
            t?.interrupt()
            try { t?.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        lifecycleExecutor.shutdownNow()
    }
}
