package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SessionSecurityAndCryptoTests {
    @Test fun sessionIdsAreNeverZero() {
        repeat(256) {
            assertNotEquals(0L, SessionIds.newSessionId())
        }
    }

    @Test fun handshakeNeverTransmitsRawPairingTokenOverTcpWire() {
        val secretToken = "SuperSecretToken_DoNotSendInClear_12345"
        val loopback = InetAddress.getLoopbackAddress()
        val serverSock = ServerSocket(0, 1, loopback)
        val wireCapture = java.io.ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        val serverKeysRef = AtomicReference<SessionKeys?>()
        val done = CountDownLatch(1)

        Thread {
            try {
                serverSock.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val teeInput = object : java.io.FilterInputStream(peer.getInputStream()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n > 0) wireCapture.write(b, off, n)
                            return n
                        }
                    }
                    // Handshake directly via streams/socket wrapper
                    val result = SessionHandshake.serverWithStreams(
                        peer.inetAddress, teeInput, peer.getOutputStream(),
                        secretToken, allowLoopbackForTests = true
                    )
                    serverKeysRef.set(result.keys)
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.start()

        val clientResult = Socket(loopback, serverSock.localPort).use { client ->
            client.soTimeout = 5_000
            SessionHandshake.clientWithStreams(
                client.inetAddress, client.getInputStream(), client.getOutputStream(),
                secretToken, allowLoopbackForTests = true
            )
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        serverSock.close()

        val capturedText = wireCapture.toString(Charsets.ISO_8859_1.name())
        assertFalse("Cleartext pairing token must NEVER appear on TCP wire!", capturedText.contains(secretToken))
        val sKeys = serverKeysRef.get()!!
        assertEquals(clientResult.sessionId, sKeys.sessionId)
        assertArrayEquals(clientResult.keys.videoKey, sKeys.videoKey)
        assertArrayEquals(clientResult.keys.audioKey, sKeys.audioKey)
        assertArrayEquals(clientResult.keys.controlKey, sKeys.controlKey)
    }

    @Test fun serverRejectsSessionIdZero() {
        val loopback = InetAddress.getLoopbackAddress()
        val serverSock = ServerSocket(0, 1, loopback)
        val rejected = AtomicReference(false)
        val done = CountDownLatch(1)
        Thread {
            try {
                serverSock.accept().use {
                    SessionHandshake.server(it, "Token_12345678901234", allowLoopbackForTests = true)
                }
            } catch (_: Exception) {
                rejected.set(true)
            } finally {
                done.countDown()
            }
        }.start()

        Socket(loopback, serverSock.localPort).use { client ->
            ControlFraming.write(
                client.getOutputStream(),
                ControlFrame(
                    ControlType.HELLO,
                    sequence = 0,
                    sessionId = 0L,
                    payload = ControlMessageCodec.encode(ControlMessage.Hello(1, ByteArray(16), 1))
                )
            )
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue("SessionId 0 must be rejected", rejected.get())
        serverSock.close()
    }

    @Test fun aeadPacketSealEncryptsAndRejectsTamperedOrSpoofedPayloads() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val aad = byteArrayOf(0x43, 0x5a, 1, 4, 0, 1, 2, 3)
        val plaintext = ByteArray(1200) { (it * 3).toByte() }
        val nonce = PacketSeal.videoNonce(frameId = 42L, fragmentIndex = 1, fragmentCount = 3)

        val sealed = PacketSeal.seal(key, nonce, aad, plaintext, plaintext.size)
        assertEquals(plaintext.size + PacketSeal.TAG_BYTES, sealed.size)
        assertFalse(sealed.copyOf(plaintext.size).contentEquals(plaintext))

        val opened = PacketSeal.open(key, nonce, aad, sealed, sealed.size)
        assertArrayEquals(plaintext, opened)

        // Tamper 1 bit of ciphertext -> must fail authentication
        val tampered = sealed.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        try {
            PacketSeal.open(key, nonce, aad, tampered, tampered.size)
            fail("Expected authentication failure on tampered ciphertext")
        } catch (_: ProtocolException) {}

        // Tamper AAD (spoofed frameId/header) -> must fail authentication
        val badAad = aad.copyOf()
        badAad[2] = 99
        try {
            PacketSeal.open(key, nonce, badAad, sealed, sealed.size)
            fail("Expected authentication failure on tampered header AAD")
        } catch (_: ProtocolException) {}
    }
}
