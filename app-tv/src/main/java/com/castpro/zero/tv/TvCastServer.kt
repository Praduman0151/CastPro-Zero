package com.castpro.zero.tv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.castpro.zero.protocol.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class TvCastServer(private val callbacks: Callbacks = object : Callbacks {}, val pairingToken: String = createPairingToken()) {
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
        private fun createPairingToken(): String = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    @Volatile private var control: Socket? = null
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var sessionId = 0L
    @Volatile private var authenticated = false
    @Volatile private var authenticatedPeer: InetAddress? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var stream: ControlMessage.StreamConfig? = null
    @Volatile private var gate = DecodeGate(VideoCodec.AVC)
    @Volatile private var rendered = 0L
    @Volatile private var dropped = 0L
    @Volatile private var pliCount = 0L
    @Volatile private var rttMs = -1L
    @Volatile private var bytesRenderedWindow = 0L
    @Volatile private var liveMbps = 0.0
    @Volatile private var renderedFps = 0L
    
    @Volatile private var decoderName = "idle"
    @Volatile private var resolution = "—"
    private var sequence = 0L
    private var firstTenFrames = 0
    @Volatile private var discoveryStarted = false
    @Volatile private var firstRenderedFrameLogged = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ listenLoop() }, "CPZ-TV-control").start()
        Thread({ telemetryLoop() }, "CPZ-TV-telemetry").start()
    }

    fun refreshNetwork() {
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
    }

    private data class LanAddress(val address: Inet4Address, val broadcast: Inet4Address?)
    private fun privateAddress(): LanAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address
                    if (a is Inet4Address && LocalAddressPolicy.isAllowed(a.hostAddress ?: "")) LanAddress(a, ia.broadcast as? Inet4Address) else null
                } }
                .firstOrNull()
        } catch (_: Exception) { null }
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
                val tcp = ServerSocket(TCP_PORT, 4, lan.address)
                val video = DatagramSocket(UDP_PORT, lan.address).apply { receiveBufferSize = 4 * 1024 * 1024; soTimeout = 100 }
                tcpServer = tcp; udp = video
                callbacks.onAddress(ip, pairingToken)
                callbacks.onStatus("Receiver ready · $ip")
                startDiscoveryResponder()
                Thread({ receiveVideo(video) }, "CPZ-TV-udp").start()
                while (running.get() && !tcp.isClosed) {
                    try { handle(tcp.accept()) }
                    catch (e: SocketException) { if (running.get()) Log.i("CPZ-TV", "Network listener rebinding", e); break }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w("CPZ-TV", "LAN listener unavailable; retrying", e)
                    callbacks.onStatus("Network unavailable · retrying")
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                }
            } finally {
                try { tcpServer?.close() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                tcpServer = null; udp = null
            }
        }
    }

    private fun startDiscoveryResponder() {
        if (discoveryStarted) return
        discoveryStarted = true
        Thread({
            var localSocket: DatagramSocket? = null
            try {
                val socket = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT)) }
                localSocket = socket
                discovery = socket
                while (running.get()) {
                    val lan = privateAddress()
                    if (lan == null) { Thread.sleep(1000); continue }
                    val ip = lan.address.hostAddress
                    if (ip == null) { Thread.sleep(1000); continue }
                    val payload = TvDiscoveryPacketCodec.encode(TvDiscoveryPacket(
                        name = Build.MODEL ?: "CastPro-Zero TV",
                        ipAddress = ip, tcpPort = TCP_PORT, udpPort = UDP_PORT, pairingToken = pairingToken,
                        expiryEpochSeconds = System.currentTimeMillis() / 1000L + 3600L
                    ))
                    val broadcastAddress = lan.broadcast ?: InetAddress.getByName("255.255.255.255")
                    socket.send(DatagramPacket(payload, payload.size, broadcastAddress, DISCOVERY_PORT))
                    Thread.sleep(1500)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w("CPZ-TV", "Discovery responder unavailable", e)
            } finally {
                discoveryStarted = false
                try { localSocket?.close() } catch (_: Exception) {}
                if (discovery === localSocket) discovery = null
            }
        }, "CPZ-TV-discovery").start()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val sid = SessionHandshake.server(socket, pairingToken)
            control = socket; sessionId = sid; authenticatedPeer = socket.inetAddress; authenticated = true
            firstTenFrames = 0
            firstRenderedFrameLogged = false
            callbacks.onStatus("Authenticated phone session")
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            while (running.get() && !socket.isClosed && authenticated) {
                val frame = ControlFraming.read(input) ?: break
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.StreamConfig -> {
                        if (message.codec != 1 || message.width !in 1..8192 || message.height !in 1..8192) {
                            Log.w("CPZ-TV", "Rejected invalid stream config"); continue
                        }
                        val previous = stream
                        if (previous != null && (previous.width != message.width || previous.height != message.height || previous.codec != message.codec)) {
                            stopDecoder(); csd = null; gate = DecodeGate(VideoCodec.AVC)
                        }
                        stream = message; resolution = "${message.width}×${message.height}"
                        callbacks.onStreamConfig(message); configureDecoderIfReady()
                    }
                    is ControlMessage.VideoCsd -> {
                        if (message.codec == 1 && gate.onCsd(message.annexB)) {
                            csd = message.annexB.copyOf()
                            configureDecoderIfReady()
                        } else Log.w("CPZ-TV", "Rejected invalid Annex-B AVC CSD")
                    }
                    is ControlMessage.Ping -> ControlFraming.write(output, ControlFrame(
                        type = ControlType.PONG, sequence = sequence++, sessionId = sessionId,
                        payload = ControlMessageCodec.encode(ControlMessage.Pong(message.timestampNs, message.pingSequence))))
                    is ControlMessage.Pong -> Unit
                    is ControlMessage.Stop -> {
                        clearSession("Casting stopped")
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.i("CPZ-TV", "Control peer disconnected", e)
        } finally {
            if (control === socket) {
                control = null
                clearSession("Phone disconnected")
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @Synchronized private fun configureDecoderIfReady() {
        val cfg = stream ?: return
        val data = csd ?: return
        val target = surface ?: return
        if (!target.isValid || decoder != null) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (Build.VERSION.SDK_INT >= 30 && capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Annex-B CSD is queued as a codec-config input buffer only after start(); no csd-0/csd-1 keys.
            codec.configure(format, target, null, 0)
            codec.start()
            decoder = codec
            decoderName = codec.name
            Log.i("CPZ-TV", "Active decoder: $decoderName")
            codec.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                if (!firstRenderedFrameLogged) {
                    firstRenderedFrameLogged = true
                    Log.i("CPZ-TV", "FIRST_RENDERED_FRAME elapsedRealtimeNs=$nanoTime presentationTimeUs=$presentationTimeUs")
                    callbacks.onFirstFrameRendered()
                }
            }, null)
            queueCsd(codec, data)
            Thread({ drainOutput(codec) }, "CPZ-TV-output-drain").start()
        } catch (e: Exception) {
            Log.e("CPZ-TV", "Decoder setup failed", e)
            stopDecoder(); requestPli(2)
        }
    }

    private fun queueCsd(codec: MediaCodec, data: ByteArray) {
        val index = codec.dequeueInputBuffer(10000)
        if (index < 0) { Log.w("CPZ-TV", "No input buffer available for CSD"); requestPli(7); return }
        val input = codec.getInputBuffer(index) ?: run { requestPli(7); return }
        if (data.size > input.capacity()) { requestPli(7); return }
        input.clear(); input.put(data)
        codec.queueInputBuffer(index, 0, data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        gate.resetForDiscontinuity()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get() && decoder === codec) {
            val index = try { codec.dequeueOutputBuffer(info, 10000) } catch (_: Exception) { break }
            if (index >= 0) {
                try {
                    codec.releaseOutputBuffer(index, true)
                    rendered++

                } catch (e: Exception) {
                    dropped++; requestPli(3)
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
                Log.i("CPZ-TV", "INFO_OUTPUT_FORMAT_CHANGED width=$width height=$height$crop color-format=${if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) format.getInteger(MediaFormat.KEY_COLOR_FORMAT) else -1}")
                resolution = "$displayWidth×$displayHeight"
                callbacks.onStreamConfig((stream ?: return).copy(width = displayWidth, height = displayHeight))
            }
        }
    }

    private fun receiveVideo(socket: DatagramSocket) {
        var reassembler: VideoReassembler? = null
        var reassemblerSession = 0L
        val bytes = ByteArray(MAX_UDP_DATAGRAM_BYTES)
        while (running.get() && !socket.isClosed) try {
            val packet = DatagramPacket(bytes, bytes.size); socket.receive(packet)
            if (!authenticated) continue
            if (packet.address != authenticatedPeer || !LocalAddressPolicy.isAllowed(packet.address.hostAddress ?: "")) {
                dropped++; continue
            }
            if (reassembler == null || reassemblerSession != sessionId) {
                reassembler = VideoReassembler(sessionId); reassemblerSession = sessionId
            }
            val raw = packet.data.copyOf(packet.length)
            val fragment = try { VideoFragment.decode(raw) } catch (_: Exception) { dropped++; continue }
            if (fragment.sessionId != sessionId) { dropped++; continue }
            val result = reassembler.accept(raw, System.nanoTime())
            dropped += result.dropped
            if (result.requestPli) requestPli(1)
            result.frames.forEach { frame ->
                val nalTypes = AnnexB.nalUnits(frame.bytes).mapNotNull { it.firstOrNull()?.toInt()?.and(31) }
                val keyframe = AnnexB.isIdr(frame.bytes, VideoCodec.AVC)
                if (firstTenFrames < 10) {
                    firstTenFrames++
                    Log.i("CPZ-TV", "RX frameId=${frame.frameId} fragCount=${ceil(frame.bytes.size.toDouble() / MAX_VIDEO_PAYLOAD_BYTES).toInt()} size=${frame.bytes.size} keyframe=$keyframe NAL=$nalTypes")
                }
                val codec = decoder ?: return@forEach
                if (!gate.allowFrame(frame.bytes)) return@forEach
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) { dropped++; requestPli(4); return@forEach }
                val input = codec.getInputBuffer(index) ?: return@forEach
                if (frame.bytes.size > input.capacity()) { dropped++; requestPli(5); return@forEach }
                input.clear(); input.put(frame.bytes)
                codec.queueInputBuffer(index, 0, frame.bytes.size, frame.captureTimestampNs / 1000L, 0)
                bytesRenderedWindow += frame.bytes.size
            }
        } catch (_: SocketTimeoutException) {
            if (authenticated && reassembler?.expire(System.nanoTime()) == true) requestPli(1)
        } catch (e: SocketException) {
            if (running.get() && !socket.isClosed) Log.w("CPZ-TV", "UDP socket error", e)
            break
        } catch (e: Exception) {
            if (running.get()) Log.w("CPZ-TV", "UDP receive error", e)
        }
    }

    private fun requestPli(reason: Int) {
        pliCount++
        try {
            val s = control ?: return
            ControlFraming.write(s.getOutputStream(), ControlFrame(type = ControlType.PLI,
                sequence = sequence++, sessionId = sessionId,
                payload = ControlMessageCodec.encode(ControlMessage.Pli(reason))))
        } catch (_: Exception) {}
    }

    fun attachSurface(target: Surface) {
        surface = target
        configureDecoderIfReady()
        if (csd != null && stream != null) requestPli(6)
    }

    fun detachSurface() {
        surface = null
        stopDecoder()
    }

    private fun clearSession(message: String) {
        authenticated = false; authenticatedPeer = null; sessionId = 0L
        stream = null; csd = null; gate = DecodeGate(VideoCodec.AVC)
        stopDecoder()
        callbacks.onSessionIdle()
        callbacks.onStatus(message)
    }

    fun telemetry(): String = "Rendered FPS: $renderedFps · TCP RTT: ${if (rttMs >= 0) "${rttMs}ms" else "n/a"} · UDP dropped: $dropped · PLI: $pliCount · ${"%.2f".format(liveMbps)} Mbps · $resolution · $decoderName"

    private fun telemetryLoop() {
        var previous = System.nanoTime()
        var previousRendered = rendered
        while (running.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
            val now = System.nanoTime()
            val elapsed = (now - previous).coerceAtLeast(1L)
            liveMbps = bytesRenderedWindow * 8.0 / elapsed * 1000.0
            renderedFps = rendered - previousRendered
            previousRendered = rendered
            bytesRenderedWindow = 0L; previous = now
            callbacks.onTelemetry(telemetry())
        }
    }

    @Synchronized private fun stopDecoder() {
        val current = decoder; decoder = null
        if (current != null) try { current.stop() } catch (_: Exception) {}
        if (current != null) try { current.release() } catch (_: Exception) {}
        decoderName = "idle"
    }

    fun stop() {
        running.set(false)
        clearSession("Receiver stopped")
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { discovery?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
    }
}
