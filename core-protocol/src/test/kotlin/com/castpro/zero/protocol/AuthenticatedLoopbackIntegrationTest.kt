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
        val loopback = InetAddress.getLoopbackAddress()
        val tcpServer = ServerSocket(0, 2, loopback)
        val udpReceiver = DatagramSocket(0, loopback).apply { receiveBufferSize = 4 * 1024 * 1024 }
        assertTrue(udpReceiver.receiveBufferSize >= 4 * 1024 * 1024)
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val server = Thread {
            try {
                tcpServer.accept().use { peer ->
                    val sid = SessionHandshake.server(peer, token, allowLoopbackForTests = true)
                    val input = peer.getInputStream()
                    val output = peer.getOutputStream()
                    val config = ControlFraming.read(input)!!
                    assertEquals(ControlType.STREAM_CONFIG, config.type)
                    assertEquals(sid, config.sessionId)
                    val csdFrame = ControlFraming.read(input)!!
                    assertEquals(ControlType.VIDEO_CSD, csdFrame.type)
                    val gate = DecodeGate(VideoCodec.AVC)
                    assertTrue(gate.onCsd((ControlMessageCodec.decode(csdFrame.type, csdFrame.payload) as ControlMessage.VideoCsd).annexB))
                    val reassembler = VideoReassembler(sid)
                    val first = receiveFrame(udpReceiver, reassembler, 4)
                    assertNotNull(first)
                    assertTrue(gate.allowFrame(first!!.bytes))

                    // One fragment of frame 2 is intentionally absent.
                    udpReceiver.soTimeout = 180
                    repeat(3) {
                        val packet = DatagramPacket(ByteArray(1360), 1360)
                        udpReceiver.receive(packet)
                        reassembler.accept(packet.data.copyOf(packet.length), System.nanoTime())
                    }
                    assertTrue(reassembler.expire(System.nanoTime() + DEFAULT_FRAME_DEADLINE_NS + 1))
                    ControlFraming.write(output, ControlFrame(ControlType.PLI, sequence = 3, sessionId = sid,
                        payload = ControlMessageCodec.encode(ControlMessage.Pli(1))))

                    gate.resetForDiscontinuity()
                    val recovery = receiveFrame(udpReceiver, reassembler, 4)
                    assertNotNull(recovery)
                    assertTrue(gate.allowFrame(recovery!!.bytes))
                    val stop = ControlFraming.read(input)!!
                    assertEquals(ControlType.STOP, stop.type)
                    assertEquals(sid, stop.sessionId)
                }
            } catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        server.start()

        Socket(loopback, tcpServer.localPort).use { client ->
            val sid = SessionHandshake.client(client, token, allowLoopbackForTests = true)
            val output = client.getOutputStream()
            fun send(m: ControlMessage) = ControlFraming.write(output, ControlFrame(
                ControlMessageCodec.typeOf(m), sequence = 2, sessionId = sid,
                payload = ControlMessageCodec.encode(m)))
            send(ControlMessage.StreamConfig(1, 640, 360, 30, 10_000_000, 1, 3, 2))
            send(ControlMessage.VideoCsd(1, csd))

            val sender = DatagramSocket().apply { sendBufferSize = 2 * 1024 * 1024 }
            assertTrue(sender.sendBufferSize >= 2 * 1024 * 1024)
            fun sendFrame(id: Long, omitFragment: Int? = null) {
                val fragments = VideoFragmenter.fragment(idr, sid, id, 4, System.nanoTime())
                assertTrue(fragments.all { it.size <= 1360 })
                fragments.forEachIndexed { index, bytes ->
                    if (index != omitFragment) sender.send(DatagramPacket(bytes, bytes.size, loopback, udpReceiver.localPort))
                }
            }
            sendFrame(1)
            sendFrame(2, omitFragment = 1)
            val pliFrame = ControlFraming.read(client.getInputStream())!!
            assertEquals(ControlType.PLI, pliFrame.type)
            assertEquals(sid, pliFrame.sessionId)
            assertTrue(ControlMessageCodec.decode(pliFrame.type, pliFrame.payload) is ControlMessage.Pli)
            sendFrame(3)
            send(ControlMessage.Stop(0))
            sender.close()
        }
        assertTrue("Loopback server did not finish", finished.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Loopback integration failed", it) }
        udpReceiver.close()
        tcpServer.close()
    }

    @Test fun unauthenticatedPeerCannotSendStreamConfig() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val done = CountDownLatch(1)
        val rejected = AtomicReference<Boolean>(false)
        val worker = Thread {
            try { listener.accept().use { SessionHandshake.server(it, token, allowLoopbackForTests = true) } }
            catch (_: Exception) { rejected.set(true) }
            finally { done.countDown() }
        }
        worker.start()
        Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
            ControlFraming.write(socket.getOutputStream(), ControlFrame(ControlType.STREAM_CONFIG,
                sequence = 0, sessionId = 99,
                payload = ControlMessageCodec.encode(ControlMessage.StreamConfig(1,1,1,1,1,1,1,1))))
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(rejected.get())
        listener.close()
    }

    private fun receiveFrame(socket: DatagramSocket, reassembler: VideoReassembler, count: Int): ReassembledFrame? {
        socket.soTimeout = 1000
        var result: ReassembledFrame? = null
        repeat(count) {
            val packet = DatagramPacket(ByteArray(1360), 1360)
            socket.receive(packet)
            val outcome = reassembler.accept(packet.data.copyOf(packet.length), System.nanoTime())
            outcome.frames.firstOrNull()?.let { result = it }
        }
        return result
    }
}
