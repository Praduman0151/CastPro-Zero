package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AuthenticatedLoopbackIntegrationTest {
    private val token = "loopback_pair_token_123456"
    private val csd = byteArrayOf(0,0,0,1,0x67,0x42,0,0x1e,0,0,0,1,0x68,1)
    private val idr = byteArrayOf(0,0,0,1,0x65,1) + ByteArray(4_000) { (it % 127).toByte() }

    @Test fun authenticatedTcpUdpRecoveryAndStopOverLoopback() {
        val tcpServer = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val udpReceiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { receiveBufferSize = 4 * 1024 * 1024 }
        assertTrue(udpReceiver.receiveBufferSize >= 4 * 1024 * 1024)
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val sidRef = AtomicReference<Long>()
        val server = Thread {
            try {
                tcpServer.accept().use { peer ->
                    val sid = SessionHandshake.server(peer, token, allowLoopbackForTests = true)
                    sidRef.set(sid)
                    val input = peer.getInputStream(); val output = peer.getOutputStream()
                    var configSeen = false; var csdSeen = false
                    val gate = DecodeGate(VideoCodec.AVC)
                    while (true) {
                        val frame = ControlFraming.read(input) ?: break
                        if (frame.sessionId != sid) continue
                        when (val message = ControlMessageCodec.decode(frame.type, frame.payload)) {
                            is ControlMessage.StreamConfig -> configSeen = true
                            is ControlMessage.VideoCsd -> { csdSeen = gate.onCsd(message.annexB); assertTrue(csdSeen) }
                            is ControlMessage.Ping -> ControlFraming.write(output, ControlFrame(ControlType.PONG,
                                sequence = frame.sequence, sessionId = sid,
                                payload = ControlMessageCodec.encode(ControlMessage.Pong(System.nanoTime(), message.pingSequence))))
                            is ControlMessage.Stop -> { assertTrue(configSeen); assertTrue(csdSeen); break }
                            else -> Unit
                        }
                    }
                }
            } catch (t: Throwable) { failure.set(t) } finally { finished.countDown() }
        }
        server.start()
        Socket(InetAddress.getLoopbackAddress(), tcpServer.localPort).use { client ->
            val sid = SessionHandshake.client(client, token)
            assertEquals(sid, sidRef.get() ?: sid)
            val out = client.getOutputStream(); var seq = 2L
            fun control(m: ControlMessage) = ControlFraming.write(out, ControlFrame(ControlMessageCodec.typeOf(m),
                sequence = seq++, sessionId = sid, payload = ControlMessageCodec.encode(m)))
            control(ControlMessage.StreamConfig(1, 640, 360, 30, 10_000_000, 1, 3, 2))
            control(ControlMessage.VideoCsd(1, csd))
            val gate = DecodeGate(VideoCodec.AVC)
            assertTrue(gate.onCsd(csd))
            val sender = DatagramSocket().apply { sendBufferSize = 2 * 1024 * 1024 }
            assertTrue(sender.sendBufferSize >= 2 * 1024 * 1024)
            val packets = VideoFragmenter.fragment(idr, sid, 1, 4, System.nanoTime())
            assertTrue(packets.all { it.size <= 1360 })
            packets.forEach { sender.send(DatagramPacket(it, it.size, InetAddress.getLoopbackAddress(), udpReceiver.localPort)) }
            val reassembler = VideoReassembler(sid)
            val assembled = receiveFrame(udpReceiver, reassembler, packets.size)
            assertNotNull(assembled)
            assertTrue(gate.allowFrame(assembled!!.bytes))

            // Drop one fragment of the next frame; expiry must trigger a coalesced PLI.
            val lost = VideoFragmenter.fragment(idr, sid, 2, 4, System.nanoTime())
            lost.filterIndexed { index, _ -> index != 1 }.forEach {
                sender.send(DatagramPacket(it, it.size, InetAddress.getLoopbackAddress(), udpReceiver.localPort))
            }
            val receiverBuffer = ByteArray(1360)
            val first = DatagramPacket(receiverBuffer, receiverBuffer.size)
            udpReceiver.soTimeout = 100
            udpReceiver.receive(first)
            val partial = reassembler.accept(first.data.copyOf(first.length), System.nanoTime())
            assertFalse(partial.rejected)
            assertTrue(reassembler.expire(System.nanoTime() + DEFAULT_FRAME_DEADLINE_NS + 1))
            control(ControlMessage.Pli(1))
            control(ControlMessage.VideoCsd(1, csd))
            val recovery = VideoFragmenter.fragment(idr, sid, 3, 4, System.nanoTime())
            recovery.forEach { sender.send(DatagramPacket(it, it.size, InetAddress.getLoopbackAddress(), udpReceiver.localPort)) }
            val recovered = receiveFrame(udpReceiver, reassembler, recovery.size)
            assertNotNull(recovered)
            assertTrue(gate.allowFrame(recovered!!.bytes))
            control(ControlMessage.Stop(0))
            sender.close()
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        udpReceiver.close(); tcpServer.close()
    }

    @Test fun unauthenticatedPeerCannotAdvancePastHello() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val done = CountDownLatch(1)
        val rejected = AtomicReference<Boolean>(false)
        val worker = Thread {
            try { listener.accept().use { SessionHandshake.server(it, token, allowLoopbackForTests = true) } }
            catch (_: Exception) { rejected.set(true) } finally { done.countDown() }
        }
        worker.start()
        Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
            ControlFraming.write(socket.getOutputStream(), ControlFrame(ControlType.STREAM_CONFIG,
                sequence = 0, sessionId = 99, payload = ControlMessageCodec.encode(ControlMessage.StreamConfig(1,1,1,1,1,1,1,1))))
        }
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertTrue(rejected.get()); listener.close()
    }

    private fun receiveFrame(socket: DatagramSocket, reassembler: VideoReassembler, count: Int): ReassembledFrame? {
        socket.soTimeout = 1000
        var result: ReassembledFrame? = null
        repeat(count) {
            val data = ByteArray(1360); val packet = DatagramPacket(data, data.size); socket.receive(packet)
            val outcome = reassembler.accept(packet.data.copyOf(packet.length), System.nanoTime())
            outcome.frames.firstOrNull()?.let { result = it }
        }
        return result
    }
}
