package com.castpro.zero.phone.net

import com.castpro.zero.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated LAN sender transport. No packet can be sent before the TCP pairing exchange. */
class PhoneSenderTransport(
    private val host: String,
    private val tcpPort: Int,
    private val udpPort: Int,
    private val pairingToken: String,
    private val onPli: () -> Unit,
    private val onRtt: (Long) -> Unit = {},
    private val onSyncFrameRequest: () -> Unit = {}
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    private var sessionId: Long = 0
    private var sequence = 2L
    private var frameId = 0L
    @Volatile private var cachedCsd: Pair<Int, ByteArray>? = null

    @Synchronized fun connect(): Long {
        check(!running.get()) { "Transport already connected" }
        LocalAddressPolicy.requireAllowed(host)
        val address = InetAddress.getByName(host)
        val control = Socket(address, tcpPort).apply { tcpNoDelay = true }
        try {
            val sid = SessionHandshake.client(control, pairingToken)
            val video = DatagramSocket().apply { sendBufferSize = 2 * 1024 * 1024 }
            check(video.sendBufferSize >= 2 * 1024 * 1024) { "UDP send buffer below 2 MiB" }
            tcp = control; udp = video; sessionId = sid; running.set(true)
            Thread({ readControl() }, "CPZ-phone-control").start()
            return sid
        } catch (t: Throwable) { control.close(); throw t }
    }

    @Synchronized fun sendControl(message: ControlMessage) {
        check(running.get()) { "Pairing is required before control messages" }
        val socket = tcp ?: error("TCP unavailable")
        ControlFraming.write(socket.getOutputStream(), ControlFrame(ControlMessageCodec.typeOf(message),
            sequence = sequence++, sessionId = sessionId, payload = ControlMessageCodec.encode(message)))
    }

    fun sendStreamConfig(config: ControlMessage.StreamConfig) = sendControl(config)
    fun sendCsd(codec: Int, annexB: ByteArray) {
        cachedCsd = codec to annexB.copyOf()
        sendControl(ControlMessage.VideoCsd(codec, annexB))
    }

    fun sendPing(pingSequence: Long = System.nanoTime()) = sendControl(ControlMessage.Ping(System.nanoTime(), pingSequence))

    @Synchronized fun sendVideoFrame(bytes: ByteArray, keyframe: Boolean, timestampNs: Long = System.nanoTime()) {
        check(running.get()) { "Pairing is required before UDP video" }
        val fragments = VideoFragmenter.fragment(bytes, sessionId, frameId++,
            if (keyframe) 4 else 0, timestampNs)
        val socket = udp ?: error("UDP unavailable")
        val target = InetAddress.getByName(host)
        val pacing = UdpBurstPacer.plan(fragments.size)
        fragments.forEachIndexed { index, data ->
            check(data.size <= MAX_UDP_DATAGRAM_BYTES)
            socket.send(DatagramPacket(data, data.size, target, udpPort))
            if (pacing.pauseMicros > 0 && (index + 1) % pacing.burstSize == 0 && index + 1 < fragments.size)
                java.util.concurrent.locks.LockSupport.parkNanos(pacing.pauseMicros * 1_000L)
        }
    }

    private fun readControl() {
        val socket = tcp ?: return
        try {
            while (running.get()) {
                val frame = ControlFraming.read(socket.getInputStream()) ?: break
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.Pli -> {
                        cachedCsd?.let { (codec, data) -> sendCsd(codec, data) }
                        onSyncFrameRequest()
                        onPli()
                    }
                    is ControlMessage.Pong -> onRtt((System.nanoTime() - message.timestampNs).coerceAtLeast(0) / 1_000_000)
                    is ControlMessage.Stop -> { close(); break }
                    else -> Unit
                }
            }
        } catch (_: Exception) { /* Socket teardown ends the control reader. */ }
        finally { close() }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        tcp = null; udp = null
    }
}
