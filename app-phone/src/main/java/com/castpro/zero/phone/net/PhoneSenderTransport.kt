package com.castpro.zero.phone.net

import android.util.Log
import com.castpro.zero.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Bounded ring cache of recently sent UDP video fragments for <5ms selective NACK recovery. */
class SentFragmentCache(private val maxFrames: Int = 8) {
    private val map = LinkedHashMap<Long, Array<ByteArray>>(maxFrames + 1, 0.75f, false)

    @Synchronized
    fun put(frameId: Long, fragments: Array<ByteArray>) {
        map[frameId] = fragments
        while (map.size > maxFrames) {
            val oldestKey = map.keys.firstOrNull() ?: break
            map.remove(oldestKey)
        }
    }

    @Synchronized
    fun get(frameId: Long, index: Int): ByteArray? {
        val frags = map[frameId] ?: return null
        return if (index in frags.indices) frags[index] else null
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/**
 * Authenticated LAN sender with decoupled locks for TCP control, video UDP, and audio UDP so
 * inter-packet video burst pacing never stalls audio frames or control/NACK/PONG responses.
 */
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
    private val connectLock = Any()
    private val controlLock = Any()
    private val videoLock = Any()
    private val audioLock = Any()
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    private var audioUdp: DatagramSocket? = null
    @Volatile private var sessionId = 0L
    @Volatile private var target: InetAddress? = null
    private var sequence = 2L
    private var frameId = 0L
    private val fragmentCache = SentFragmentCache(maxFrames = 8)
    private val framesSent = AtomicLong()
    private val bytesSent = AtomicLong()
    private val pliCount = AtomicLong()
    private val audioFramesSent = AtomicLong()
    @Volatile private var rttMs = -1L
    @Volatile private var cachedCsd: Pair<Int, ByteArray>? = null
    @Volatile private var pingThread: Thread? = null

    fun connect(): Long = synchronized(connectLock) {
        check(!running.get()) { "Transport already connected" }
        LocalAddressPolicy.requireAllowed(host)
        val address = InetAddress.getByName(host)
        target = address
        val control = Socket()
        var video: DatagramSocket? = null
        var audioSocket: DatagramSocket? = null
        try {
            if (binding != null) binding.bindTcp(control) else control.bind(InetSocketAddress(0))
            control.connect(InetSocketAddress(address, tcpPort), 5_000)
            binding?.validateTcp(control)
            control.tcpNoDelay = true
            control.keepAlive = true
            control.soTimeout = 5_000
            try { control.trafficClass = 0x10 } catch (_: Exception) {}
            val sid = SessionHandshake.client(control, pairingToken)

            video = DatagramSocket(null).apply {
                if (binding != null) binding.bindUdp(this) else bind(InetSocketAddress(0))
                sendBufferSize = 2 * 1024 * 1024
                try { trafficClass = 0x88 } catch (_: Exception) {}
            }
            audioSocket = DatagramSocket(null).apply {
                if (binding != null) binding.bindUdp(this) else bind(InetSocketAddress(0))
                sendBufferSize = 256 * 1024
                try { trafficClass = 0xB8 } catch (_: Exception) {}
            }
            Log.i(TAG, "UDP send buffer requested=2 MiB actual=${video.sendBufferSize}")
            tcp = control
            udp = video
            audioUdp = audioSocket
            sessionId = sid
            running.set(true)
            Thread({ readControl() }, "CPZ-phone-control").start()
            pingThread = Thread({
                var pingSequence = 0L
                while (running.get()) {
                    sendPing(pingSequence++)
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                }
            }, "CPZ-phone-ping").also { it.start() }
            return sid
        } catch (t: Throwable) {
            try { control.close() } catch (_: Exception) {}
            try { video?.close() } catch (_: Exception) {}
            try { audioSocket?.close() } catch (_: Exception) {}
            throw t
        }
    }

    /**
     * Control writes after teardown are routine (PLI/CSD replies, ping, stop).
     * Never shares lock with paced video UDP transmissions.
     */
    fun sendControl(message: ControlMessage): Boolean = synchronized(controlLock) {
        val socket = tcp
        if (!running.get() || socket == null) return@synchronized false
        try {
            ControlFraming.write(
                socket.getOutputStream(),
                ControlFrame(
                    type = ControlMessageCodec.typeOf(message),
                    sequence = sequence++,
                    sessionId = sessionId,
                    payload = ControlMessageCodec.encode(message)
                )
            )
            true
        } catch (e: Exception) {
            val failure = SendFailurePolicy.classify(e)
            if (SendFailurePolicy.isExpected(failure) || !running.get()) {
                Log.w(TAG, SendFailurePolicy.message(failure))
            } else {
                Log.e(TAG, SendFailurePolicy.message(failure), e)
            }
            false
        }
    }

    fun sendStreamConfig(config: ControlMessage.StreamConfig) = sendControl(config)

    fun sendCsd(codec: Int, annexB: ByteArray) {
        cachedCsd = codec to annexB.copyOf()
        sendControl(ControlMessage.VideoCsd(codec, annexB))
    }

    fun sendPing(pingSequence: Long = System.nanoTime()) =
        sendControl(ControlMessage.Ping(System.nanoTime(), pingSequence))

    fun sendRttReport(rttMs: Long) = sendControl(ControlMessage.RttReport(rttMs))

    fun sendVideoFrame(
        bytes: ByteArray,
        keyframe: Boolean,
        timestampNs: Long = System.nanoTime(),
        length: Int = bytes.size
    ): SendOutcome = synchronized(videoLock) {
        val socket = udp ?: return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        val destination = target ?: return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        if (!running.get()) return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        val currentFrameId = frameId++
        val fragments = VideoFragmenter.fragment(
            bytes, sessionId, currentFrameId, if (keyframe) 4 else 0, timestampNs, length
        )
        fragmentCache.put(currentFrameId, fragments.toTypedArray())
        val pacing = UdpBurstPacer.smoothPlan(fragments.size)
        val packet = DatagramPacket(ByteArray(1), 1, destination, udpPort)
        fragments.forEachIndexed { index, data ->
            check(data.size <= MAX_UDP_DATAGRAM_BYTES)
            packet.setData(data, 0, data.size)
            try {
                socket.send(packet)
            } catch (e: Exception) {
                return@synchronized SendOutcome.NotSent(SendFailurePolicy.classify(e), e)
            }
            bytesSent.addAndGet(data.size.toLong())
            if (pacing.pauseMicros > 0 && (index + 1) % pacing.burstSize == 0 && index + 1 < fragments.size) {
                LockSupport.parkNanos(pacing.pauseMicros * 1_000L)
            }
        }
        framesSent.incrementAndGet()
        SendOutcome.Sent(currentFrameId, fragments.size, length)
    }

    private fun retransmitMissing(missingFrameId: Long, indices: List<Int>) = synchronized(videoLock) {
        val socket = udp ?: return@synchronized
        val destination = target ?: return@synchronized
        if (!running.get()) return@synchronized
        val packet = DatagramPacket(ByteArray(1), 1, destination, udpPort)
        for (idx in indices) {
            val data = fragmentCache.get(missingFrameId, idx) ?: continue
            packet.setData(data, 0, data.size)
            try {
                socket.send(packet)
                bytesSent.addAndGet(data.size.toLong())
            } catch (_: Exception) {
                break
            }
        }
    }

    /** Device audio travels on its own authenticated datagram channel, derived from the video port. */
    val audioPort: Int get() = udpPort + 2

    fun sessionId(): Long = sessionId

    fun sendAudioConfig(config: ControlMessage.AudioConfig) = sendControl(config)

    fun sendAudioFrame(frame: AudioFrame): SendOutcome = synchronized(audioLock) {
        if (!running.get()) return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        val socket = audioUdp ?: return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        val destination = target ?: return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        if (frame.sessionId != sessionId) return@synchronized SendOutcome.NotSent(SendFailure.TRANSPORT_CLOSED)
        return@synchronized try {
            val data = frame.encode()
            socket.send(DatagramPacket(data, data.size, destination, audioPort))
            bytesSent.addAndGet(data.size.toLong())
            audioFramesSent.incrementAndGet()
            SendOutcome.Sent(frame.sequence, 1, data.size)
        } catch (e: Exception) {
            SendOutcome.NotSent(SendFailurePolicy.classify(e), e)
        }
    }

    private fun readControl() {
        val socket = tcp ?: return
        var lastControlRxNs = System.nanoTime()
        try {
            val input = socket.getInputStream()
            while (running.get()) {
                val frame = try {
                    ControlFraming.read(input) ?: break
                } catch (_: SocketTimeoutException) {
                    if (System.nanoTime() - lastControlRxNs >= PEER_DEAD_TIMEOUT_NS) {
                        Log.w(TAG, "Control peer silent for >7s; closing stale transport")
                        break
                    }
                    continue
                }
                lastControlRxNs = System.nanoTime()
                if (frame.sessionId != sessionId) continue
                when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                    is ControlMessage.Pli -> {
                        pliCount.incrementAndGet()
                        cachedCsd?.let { (codec, data) -> sendCsd(codec, data) }
                        onSyncFrameRequest()
                        onPli()
                    }
                    is ControlMessage.FragmentNack -> retransmitMissing(message.frameId, message.missingIndices)
                    is ControlMessage.Ping -> sendControl(ControlMessage.Pong(message.timestampNs, message.pingSequence))
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
        audioFramesSent = audioFramesSent.get(),
        rttMs = rttMs,
        fps = fps,
        mbps = mbps,
        codec = codec
    )

    override fun close() {
        if (!running.getAndSet(false)) return
        pingThread?.interrupt()
        pingThread = null
        fragmentCache.clear()
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { audioUdp?.close() } catch (_: Exception) {}
        tcp = null
        udp = null
        audioUdp = null
        target = null
    }

    private companion object {
        const val TAG = "CPZ-Phone-TCP"
        const val PEER_DEAD_TIMEOUT_NS = 7_000_000_000L
    }
}
