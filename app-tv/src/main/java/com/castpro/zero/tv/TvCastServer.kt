package com.castpro.zero.tv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.castpro.zero.protocol.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Local-only control listener and UDP receiver. */
class TvCastServer(private val status: (String) -> Unit = {}, val pairingToken: String = createPairingToken()) {
    private val running = AtomicBoolean(false)
    private var tcpServer: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var control: Socket? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    @Volatile private var sessionId = 0L
    @Volatile private var authenticated = false
    private var sequence = 0L
    private var csd: ByteArray? = null
    private var stream: ControlMessage.StreamConfig? = null
    private var gate = DecodeGate(VideoCodec.AVC)
    private var rendered = 0L
    private var dropped = 0L

    fun start(target: Surface) {
        surface = target
        if (!running.compareAndSet(false, true)) return
        Thread({ listen() }, "CPZ-TV-control").start()
    }

    private fun privateAddress(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { LocalAddressPolicy.isAllowed(it.hostAddress ?: "") }

    private fun listen() {
        try {
            val ip = privateAddress() ?: error("No private IPv4 LAN interface")
            tcpServer = ServerSocket(45670, 4, ip)
            udp = DatagramSocket(45671, ip).apply { receiveBufferSize = 4 * 1024 * 1024 }
            status("Offline receiver ready at " + ip.hostAddress)
            Thread({ receiveVideo() }, "CPZ-TV-udp").start()
            while (running.get()) handle(tcpServer!!.accept())
        } catch (e: Exception) { if (running.get()) Log.e("CPZ-TV", "Listener failed", e); status("Receiver error: " + e.message) }
    }

    private fun handle(socket: Socket) {
        try {
            val sid = SessionHandshake.server(socket, pairingToken)
            control = socket
            sessionId = sid
            authenticated = true
            status("Authenticated phone session " + java.lang.Long.toUnsignedString(sid))
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (running.get() && !socket.isClosed && authenticated) {
                val frame = ControlFraming.read(input) ?: break
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.StreamConfig -> { stream = message; configureDecoder() }
                    is ControlMessage.VideoCsd -> {
                        if (message.codec == 1 && gate.onCsd(message.annexB)) {
                            csd = message.annexB
                            decoder?.let { queueCsd(it, message.annexB) }
                        }
                    }
                    is ControlMessage.Ping -> ControlFraming.write(output, ControlFrame(
                        type = ControlType.PONG, sequence = sequence++, sessionId = sessionId,
                        payload = ControlMessageCodec.encode(ControlMessage.Pong(System.nanoTime(), message.pingSequence))))
                    is ControlMessage.Stop -> {
                        authenticated = false
                        sessionId = 0L
                        stream = null; csd = null; gate = DecodeGate(VideoCodec.AVC)
                        stopDecoder(); status("Casting stopped")
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w("CPZ-TV", "Unauthenticated or disconnected peer", e)
        } finally {
            authenticated = false
            if (control === socket) control = null
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun configureDecoder() {
        val cfg = stream ?: return
        val target = surface ?: return
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (android.os.Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            c.configure(fmt, target, null, 0); c.start(); decoder = c
            csd?.let { queueCsd(c, it) }
            Thread({ drain(c) }, "CPZ-TV-output-drain").start()
        } catch (e: Exception) { Log.e("CPZ-TV", "Decoder setup failed", e); requestPli(2) }
    }

    private fun queueCsd(c: MediaCodec, data: ByteArray) {
        val index = c.dequeueInputBuffer(10_000)
        if (index >= 0) c.getInputBuffer(index)?.let { b ->
            b.clear(); b.put(data); c.queueInputBuffer(index, 0, data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        gate.resetForDiscontinuity()
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get() && decoder === c) {
            val index = try { c.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            if (index >= 0) try { c.releaseOutputBuffer(index, true); rendered++ } catch (_: Exception) { dropped++; requestPli(3) }
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) Log.i("CPZ-TV", "Decoder format: " + c.outputFormat)
        }
    }

    private fun receiveVideo() {
        val socket = udp ?: return
        var reassembler: VideoReassembler? = null
        val bytes = ByteArray(1360)
        while (running.get()) try {
            val packet = DatagramPacket(bytes, bytes.size); socket.receive(packet)
            if (!authenticated) continue
            val activeReassembler = reassembler ?: VideoReassembler(sessionId).also { reassembler = it }
            val fragment = try { VideoFragment.decode(packet.data.copyOf(packet.length)) } catch (_: Exception) { dropped++; continue }
            if (fragment.sessionId != sessionId) { dropped++; continue }
            val result = activeReassembler.accept(packet.data.copyOf(packet.length), System.nanoTime())
            dropped += result.dropped
            if (result.requestPli) requestPli(1)
            result.frames.forEach { frame ->
                val c = decoder ?: return@forEach
                if (!gate.allowFrame(frame.bytes)) return@forEach
                val index = c.dequeueInputBuffer(0)
                if (index < 0) { dropped++; requestPli(4); return@forEach }
                val input = c.getInputBuffer(index) ?: return@forEach
                if (frame.bytes.size > input.capacity()) { dropped++; requestPli(5); return@forEach }
                input.clear(); input.put(frame.bytes)
                c.queueInputBuffer(index, 0, frame.bytes.size, frame.captureTimestampNs / 1000L, 0)
            }
        } catch (e: Exception) { if (running.get()) Log.w("CPZ-TV", "UDP receive error", e) }
    }

    private fun requestPli(reason: Int) {
        try { val s = control ?: return; ControlFraming.write(s.getOutputStream(),
            ControlFrame(type=ControlType.PLI, sequence=sequence++, sessionId=sessionId, payload=ControlMessageCodec.encode(ControlMessage.Pli(reason)))) } catch (_: Exception) {}
    }

    fun onSurfaceRecreated(target: Surface) {
        surface = target
        stopDecoder()
        gate.resetForDiscontinuity()
        configureDecoder()
        requestPli(6)
    }

    fun telemetry(): String = "Rendered frames: " + rendered + " · dropped: " + dropped + " · codec: " + (decoder?.name ?: "idle")

    private fun stopDecoder() { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}; decoder = null }
    fun stop() {
        running.set(false)
        authenticated = false
        sessionId = 0L
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
        stopDecoder()
    }

    companion object {
        private fun createPairingToken(): String = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
