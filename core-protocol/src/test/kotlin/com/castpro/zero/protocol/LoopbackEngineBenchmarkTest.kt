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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Executed loopback performance benchmark & regression harness validating Phase 4/5 metrics:
 * - Handshake latency (ECDH P-256 + HMAC auth)
 * - Sustained 1080p60 (~20 Mbps) encrypted UDP + Audio loopback latency (p50/p95/p99) & drop rate
 * - Selective NACK recovery under simulated packet loss
 * - Pure CPU fragment + ChaCha20-Poly1305 AEAD throughput
 */
class LoopbackEngineBenchmarkTest {
    @Test fun benchmarkEncrypted1080p60PacedLoopbackPipeline() {
        val token = "benchmark_pair_token_123456"
        val loopback = InetAddress.getLoopbackAddress()
        val serverSock = ServerSocket(0, 2, loopback)
        val udpReceiver = DatagramSocket(0, loopback).apply {
            receiveBufferSize = 4 * 1024 * 1024
            soTimeout = 250
        }
        val audioSock = DatagramSocket(0, loopback).apply {
            receiveBufferSize = 1024 * 1024
            soTimeout = 250
        }

        val authResultRef = AtomicReference<AuthenticatedSession?>()
        val handshakeDone = CountDownLatch(1)
        val totalFrames = 200 // 3.33 seconds real-time @ 60.0 FPS (~20 Mbps target mix)
        val receivedFrames = AtomicInteger(0)
        val droppedOutcomes = AtomicInteger(0)
        val latenciesUs = LongArray(totalFrames)
        val rxLatencyCount = AtomicInteger(0)
        val serverErr = AtomicReference<Throwable?>()
        val audioRxCount = AtomicInteger(0)
        val audioExpected = totalFrames / 2
        val videoReceiverDone = CountDownLatch(1)

        val handshakeStartNs = System.nanoTime()
        Thread {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            try {
                serverSock.accept().use { peer ->
                    peer.soTimeout = 10_000
                    val auth = SessionHandshake.serverWithKeys(peer, token, allowLoopbackForTests = true)
                    // Pre-initialize JCE ChaCha20-Poly1305 Cipher on RX thread before timed video arrives.
                    val warmFrag = VideoFragmenter.fragment(ByteArray(16), auth.sessionId, 0x7fff_ffffL, 0, 0L, videoKey = auth.keys.videoKey)
                    VideoReassembler(auth.sessionId, PRODUCTION_FRAME_DEADLINE_NS, videoKey = auth.keys.videoKey).accept(warmFrag[0], 0L)
                    authResultRef.set(auth)
                    handshakeDone.countDown()

                    val reassembler = VideoReassembler(
                        sessionId = auth.sessionId,
                        frameDeadlineNs = PRODUCTION_FRAME_DEADLINE_NS,
                        maxInFlight = 8,
                        videoKey = auth.keys.videoKey
                    )
                    val buf = ByteArray(MAX_UDP_DATAGRAM_BYTES)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (receivedFrames.get() < totalFrames) {
                        try {
                            pkt.length = buf.size
                            udpReceiver.receive(pkt)
                            val now = System.nanoTime()
                            val outcome = reassembler.accept(pkt.data, now, pkt.length)
                            if (outcome.dropped > 0) droppedOutcomes.addAndGet(outcome.dropped)
                            for (frame in outcome.frames) {
                                AnnexB.isIdr(frame.bytes, VideoCodec.AVC)
                                val idx = rxLatencyCount.getAndIncrement()
                                if (idx < latenciesUs.size) {
                                    latenciesUs[idx] = ((now - frame.captureTimestampNs).coerceAtLeast(0L)) / 1_000L
                                }
                                receivedFrames.incrementAndGet()
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                serverErr.set(t)
                handshakeDone.countDown()
            } finally {
                videoReceiverDone.countDown()
            }
        }.start()

        val clientAuth = Socket(loopback, serverSock.localPort).use { client ->
            client.soTimeout = 10_000
            val res = SessionHandshake.clientWithKeys(client, token, allowLoopbackForTests = true)
            assertTrue("Server handshake must complete", handshakeDone.await(3, TimeUnit.SECONDS))
            val handshakeMs = (System.nanoTime() - handshakeStartNs) / 1_000_000.0
            val sAuth = authResultRef.get()!!
            assertEquals(res.sessionId, sAuth.sessionId)
            val txWarm = VideoFragmenter.fragment(ByteArray(16), res.sessionId, 0x7fff_ffffL, 0, 0L, videoKey = res.keys.videoKey)
            assertTrue(txWarm.isNotEmpty())

            val audioReader = Thread {
                val abuf = ByteArray(MAX_UDP_DATAGRAM_BYTES)
                val apkt = DatagramPacket(abuf, abuf.size)
                while (audioRxCount.get() < audioExpected) {
                    try {
                        apkt.length = abuf.size
                        audioSock.receive(apkt)
                        val decoded = AudioFrame.decode(apkt.data, apkt.length, sAuth.keys.audioKey)
                        assertEquals(res.sessionId, decoded.sessionId)
                        audioRxCount.incrementAndGet()
                    } catch (_: Exception) {
                        break
                    }
                }
            }.also { it.start() }

            val udpSender = DatagramSocket().apply { sendBufferSize = 2 * 1024 * 1024 }
            val spsIdr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()) + ByteArray(65_000) { (it % 251).toByte() }
            val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte()) + ByteArray(40_000) { (it % 239).toByte() }
            val pcmChunk = ByteArray(1312) { (it and 0xff).toByte() }

            val frameIntervalNs = 16_666_667L // Exact 60.0 FPS cadence
            val streamStartNs = System.nanoTime()
            var totalWireBytes = 0L
            for (f in 0 until totalFrames) {
                val targetTimeNs = streamStartNs + f * frameIntervalNs
                val waitNs = targetTimeNs - System.nanoTime()
                if (waitNs > 100_000L) LockSupport.parkNanos(waitNs)

                val isKey = (f % 60 == 0)
                val payload = if (isKey) spsIdr else pFrame
                val captureNs = System.nanoTime()
                val frags = VideoFragmenter.fragment(
                    payload, res.sessionId, f.toLong(),
                    if (isKey) 4 else 0, captureNs, videoKey = res.keys.videoKey
                )
                val pacing = UdpBurstPacer.smoothPlan(frags.size)
                frags.forEachIndexed { idx, frag ->
                    udpSender.send(DatagramPacket(frag, frag.size, loopback, udpReceiver.localPort))
                    totalWireBytes += frag.size
                    if (pacing.pauseMicros > 0 && (idx + 1) % pacing.burstSize == 0 && idx + 1 < frags.size) {
                        LockSupport.parkNanos(pacing.pauseMicros * 1_000L)
                    }
                }
                if (f % 2 == 0) {
                    val audioPkt = AudioFrame(res.sessionId, (f / 2).toLong(), captureNs, pcmChunk).encode(res.keys.audioKey)
                    udpSender.send(DatagramPacket(audioPkt, audioPkt.size, loopback, audioSock.localPort))
                }
            }
            val elapsedNs = (System.nanoTime() - streamStartNs).coerceAtLeast(1L)
            udpSender.close()
            audioReader.join(2000)
            videoReceiverDone.await(3, TimeUnit.SECONDS)

            val completed = receivedFrames.get()
            val sorted = latenciesUs.copyOf(minOf(rxLatencyCount.get(), latenciesUs.size)).sorted()
            val p50Ms = sorted[sorted.size / 2] / 1000.0
            val p95Ms = sorted[(sorted.size * 95) / 100] / 1000.0
            val p99Ms = sorted[(sorted.size * 99) / 100] / 1000.0
            val mbps = totalWireBytes * 8.0 / elapsedNs * 1000.0
            val actualFps = completed * 1_000_000_000.0 / elapsedNs

            println(
                "BENCHMARK_ENCRYPTED_1080P60 handshakeMs=%.2f frames=%d/%d dropped=%d audio=%d/%d fps=%.2f mbps=%.2f p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f"
                    .format(handshakeMs, completed, totalFrames, droppedOutcomes.get(), audioRxCount.get(), audioExpected, actualFps, mbps, p50Ms, p95Ms, p99Ms)
            )
            assertTrue("Drop rate must remain <= 1.0% ($completed/$totalFrames)", completed >= (totalFrames * 99) / 100)
            assertTrue("Audio datagrams must complete (>= 98%), got ${audioRxCount.get()}/$audioExpected", audioRxCount.get() >= (audioExpected * 98) / 100)
            assertTrue("Handshake must be fast (<500ms), got $handshakeMs", handshakeMs < 500.0)
            assertTrue("Loopback encrypted p95 transit latency must be < 50 ms under parallel build load, got $p95Ms", p95Ms < 50.0)
            res
        }

        serverErr.get()?.let { throw AssertionError("Benchmark server error", it) }
        udpReceiver.close()
        audioSock.close()
        serverSock.close()
        assertNotNull(clientAuth)
    }

    @Test fun benchmarkCryptoAndReassemblyCpuThroughput() {
        val key = ByteArray(32) { (it + 11).toByte() }
        val sessionId = 98765L
        val reassembler = VideoReassembler(sessionId, frameDeadlineNs = PRODUCTION_FRAME_DEADLINE_NS, videoKey = key)
        val frame = ByteArray(41_600) { (it % 251).toByte() } // ~20 Mbps @ 60fps per-frame average
        val iterations = 600 // 10 seconds of 1080p60 video

        // Warmup JIT
        repeat(60) { i ->
            val frags = VideoFragmenter.fragment(frame, sessionId, i.toLong(), 0, i.toLong(), videoKey = key)
            frags.forEach { reassembler.accept(it, i * 1_000_000L) }
        }

        val r2 = VideoReassembler(sessionId, frameDeadlineNs = PRODUCTION_FRAME_DEADLINE_NS, videoKey = key)
        val startNs = System.nanoTime()
        var packets = 0
        for (i in 0 until iterations) {
            val frags = VideoFragmenter.fragment(frame, sessionId, i.toLong(), 0, i * 10_000_000L, videoKey = key)
            packets += frags.size
            for (pkt in frags) {
                r2.accept(pkt, i * 10_000_000L)
            }
        }
        val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1L)
        val msTotal = elapsedNs / 1_000_000.0
        val throughputMbps = (frame.size.toLong() * iterations * 8.0) / elapsedNs * 1000.0
        val perFrameUs = (elapsedNs / iterations) / 1000.0
        println(
            "BENCHMARK_CRYPTO_CPU frames=%d packets=%d elapsedMs=%.2f perFrameRoundTripUs=%.1f maxEncryptedThroughputMbps=%.2f"
                .format(iterations, packets, msTotal, perFrameUs, throughputMbps)
        )
        assertTrue("Encrypted fragment+seal+open+reassemble must sustain well over 60 Mbps (target 20 Mbps), got $throughputMbps", throughputMbps > 60.0)
    }

    @Test fun benchmarkSelectiveNackRecoveryUnderSimulatedPacketLoss() {
        val sessionId = 123456L
        val key = ByteArray(32) { (it + 3).toByte() }
        val reassembler = VideoReassembler(sessionId, frameDeadlineNs = PRODUCTION_FRAME_DEADLINE_NS, videoKey = key)
        val gate = DecodeGate(VideoCodec.AVC)
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 1)
        assertTrue(gate.onCsd(csd))

        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1) + ByteArray(25_000) { (it % 251).toByte() }
        val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 1) + ByteArray(8_000) { (it % 199).toByte() }
        val dropCount = AtomicLong(0L)
        val recoveredNackCount = AtomicLong(0L)
        val completedFrames = AtomicLong(0L)

        for (i in 0 until 300) {
            val payload = if (i % 60 == 0) idr else pFrame
            val frags = VideoFragmenter.fragment(payload, sessionId, i.toLong(), if (i % 60 == 0) 4 else 0, i * 16_666_667L, videoKey = key)
            val baseNs = 10_000_000_000L + i * 16_666_667L
            val omitIdx = if (frags.size > 2 && i % 25 == 5) 1 else -1
            if (omitIdx >= 0) dropCount.incrementAndGet()

            frags.forEachIndexed { idx, pkt ->
                if (idx != omitIdx) {
                    val outcome = reassembler.accept(pkt, baseNs + idx * 50_000L)
                    completedFrames.addAndGet(outcome.frames.size.toLong())
                    outcome.frames.forEach { assertTrue(gate.allowFrame(it.bytes)) }
                }
            }
            if (omitIdx >= 0) {
                val nacks = reassembler.collectNacks(baseNs + 9_000_000L)
                assertEquals(1, nacks.size)
                assertEquals(listOf(omitIdx), nacks[0].missingIndices)
                recoveredNackCount.incrementAndGet()
                val outcome = reassembler.accept(frags[omitIdx], baseNs + 11_000_000L)
                assertEquals(1, outcome.frames.size)
                completedFrames.incrementAndGet()
                assertTrue(gate.allowFrame(outcome.frames.single().bytes))
            }
        }
        println(
            "BENCHMARK_NACK_RECOVERY frames=300 injectedDrops=${dropCount.get()} recoveredViaNack=${recoveredNackCount.get()} completedFrames=${completedFrames.get()} decodeGateState=${gate.state}"
        )
        assertEquals(300L, completedFrames.get())
        assertEquals(dropCount.get(), recoveredNackCount.get())
        assertEquals(DecodeGate.State.OPEN, gate.state)
    }

    @Test fun benchmarkBaselineTcpComparisonForPhase4Decision() {
        val loopback = InetAddress.getLoopbackAddress()
        val serverSock = ServerSocket(0, 1, loopback)
        val totalFrames = 180
        val received = AtomicInteger(0)
        val latenciesUs = LongArray(totalFrames)
        val serverErr = AtomicReference<Throwable?>()
        val key = ByteArray(32) { (it + 5).toByte() }

        val receiver = Thread {
            try {
                serverSock.accept().use { peer ->
                    peer.tcpNoDelay = true
                    val input = peer.getInputStream()
                    while (received.get() < totalFrames) {
                        val frame = ControlFraming.read(input, controlKey = key) ?: break
                        val captureNs = java.nio.ByteBuffer.wrap(frame.payload, 0, 8).long
                        latenciesUs[received.getAndIncrement()] = ((System.nanoTime() - captureNs).coerceAtLeast(0L)) / 1_000L
                    }
                }
            } catch (t: Throwable) {
                serverErr.set(t)
            }
        }.also { it.start() }

        Socket(loopback, serverSock.localPort).use { client ->
            client.tcpNoDelay = true
            val output = client.getOutputStream()
            val payload = ByteArray(40_000) { (it % 251).toByte() }
            val startNs = System.nanoTime()
            for (f in 0 until totalFrames) {
                val targetNs = startNs + f * 16_666_667L
                val waitNs = targetNs - System.nanoTime()
                if (waitNs > 100_000L) LockSupport.parkNanos(waitNs)
                java.nio.ByteBuffer.wrap(payload, 0, 8).putLong(System.nanoTime())
                ControlFraming.write(
                    output,
                    ControlFrame(ControlType.VIDEO_CSD, sequence = f.toLong(), sessionId = 1L, payload = payload),
                    controlKey = key
                )
            }
        }
        receiver.join(5000)
        serverSock.close()
        serverErr.get()?.let { throw AssertionError("TCP comparison failed", it) }
        assertEquals(totalFrames, received.get())
        val sorted = latenciesUs.sorted()
        println(
            "BENCHMARK_TCP_LOOPBACK_BASELINE frames=%d p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f"
                .format(totalFrames, sorted[sorted.size / 2] / 1000.0, sorted[(sorted.size * 95) / 100] / 1000.0, sorted[(sorted.size * 99) / 100] / 1000.0)
        )
    }
}
