package com.castpro.zero.phone.net

import android.util.Log
import com.castpro.zero.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Authenticated LAN sender; no packet leaves the phone before TCP pairing. */
class PhoneSenderTransport(
    private val host: String,
    private val tcpPort: Int,
    private val udpPort: Int,
    private val pairingToken: String,
    private val onPli: () -> Unit,
    private val onRtt: (Long) -> Unit = {},
    private val onSyncFrameRequest: () -> Unit = {},
    private val binding: LocalNetworkBinding? = null
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    private var sessionId = 0L
    private var sequence = 2L
    private var frameId = 0L
    private val framesSent = AtomicLong()
    private val bytesSent = AtomicLong()
    private val pliCount = AtomicLong()
    @Volatile private var rttMs = -1L
    @Volatile private var cachedCsd: Pair<Int, ByteArray>? = null
    @Volatile private var pingThread: Thread? = null

    @Synchronized fun connect(): Long {
        check(!running.get()) { "Transport already connected" }
        LocalAddressPolicy.requireAllowed(host)
        val address = InetAddress.getByName(host)
        val control = Socket()
        try {
            if (binding != null) binding.bindTcp(control) else control.bind(InetSocketAddress(0))
            control.connect(InetSocketAddress(address, tcpPort), 5_000)
            control.tcpNoDelay = true
            val sid = SessionHandshake.client(control, pairingToken)
            val video = DatagramSocket(null)
            if (binding != null) binding.bindUdp(video) else video.bind(InetSocketAddress(0))
            video.sendBufferSize = 2 * 1024 * 1024
            Log.i(TAG, "UDP send buffer requested=2 MiB actual=${video.sendBufferSize}")
            tcp = control
            udp = video
            sessionId = sid
            running.set(true)
            Thread({ readControl() }, "CPZ-phone-control").start()
            pingThread = Thread({
                var pingSequence = 0L
                while (running.get()) {
                    try { sendPing(pingSequence++) } catch (_: Exception) { break }
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                }
            }, "CPZ-phone-ping").also { it.start() }
            return sid
        } catch (t: Throwable) {
            control.close()
            throw t
        }
    }

    @Synchronized fun sendControl(message: ControlMessage) {
        check(running.get()) { "Pairing is required before control messages" }
        val socket = tcp ?: error("TCP unavailable")
        ControlFraming.write(socket.getOutputStream(), ControlFrame(
            type = ControlMessageCodec.typeOf(message),
            sequence = sequence++,
            sessionId = sessionId,
            payload = ControlMessageCodec.encode(message)
        ))
    }

    fun sendStreamConfig(config: ControlMessage.StreamConfig) = sendControl(config)

    fun sendCsd(codec: Int, annexB: ByteArray) {
        cachedCsd = codec to annexB.copyOf()
        sendControl(ControlMessage.VideoCsd(codec, annexB))
    }

    fun sendPing(pingSequence: Long = System.nanoTime()) =
        sendControl(ControlMessage.Ping(System.nanoTime(), pingSequence))

    fun sendRttReport(rttMs: Long) = sendControl(ControlMessage.RttReport(rttMs))

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
            bytesSent.addAndGet(data.size.toLong())
            if (pacing.pauseMicros > 0 && (index + 1) % pacing.burstSize == 0 && index + 1 < fragments.size)
                LockSupport.parkNanos(pacing.pauseMicros * 1_000L)
        }
        framesSent.incrementAndGet()
    }

    private fun readControl() {
        val socket = tcp ?: return
        try {
            while (running.get()) {
                val frame = ControlFraming.read(socket.getInputStream()) ?: break
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.Pli -> {
                        pliCount.incrementAndGet()
                        cachedCsd?.let { (codec, data) -> sendCsd(codec, data) }
                        onSyncFrameRequest()
                        onPli()
                    }
                    is ControlMessage.Pong -> {
                        val measured = (System.nanoTime() - message.timestampNs).coerceAtLeast(0) / 1_000_000
                        rttMs = measured
                        onRtt(measured)
                        sendRttReport(measured)
                    }
                    is ControlMessage.Stop -> { close(); break }
                    else -> Unit
                }
            }
        } catch (_: Exception) { /* Socket teardown ends the control reader. */ }
        finally { close() }
    }

    fun stats(fps: Int = 0, mbps: Float = 0f, codec: String = "video/avc") = SenderStats(
        framesSent = framesSent.get(),
        bytesSent = bytesSent.get(),
        pliCount = pliCount.get(),
        rttMs = rttMs,
        fps = fps,
        mbps = mbps,
        codec = codec
    )

    override fun close() {
        if (!running.getAndSet(false)) return
        pingThread?.interrupt()
        pingThread = null
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        tcp = null
        udp = null
    }

    private companion object { const val TAG = "CPZ-Phone-TCP" }
}
