package com.castpro.zero.protocol

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

data class AuthenticatedSession(
    val sessionId: Long,
    val keys: SessionKeys
)

/**
 * TCP authentication shared by the Android sender/receiver and JVM loopback tests.
 * Never sends the pairing token in cleartext on the wire; verifies knowledge via
 * HMAC-SHA256 over session nonces and optionally completes ephemeral ECDH P-256
 * key exchange for forward-secret AEAD media/control encryption.
 */
object SessionHandshake {
    private val random = SecureRandom()

    fun server(socket: Socket, pairingToken: String, allowLoopbackForTests: Boolean = false): Long {
        val (sid, _, _, _) = serverVerifyAuth(
            socket.inetAddress, socket.getInputStream(), socket.getOutputStream(),
            pairingToken, socket, allowLoopbackForTests
        )
        return sid
    }

    fun client(
        socket: Socket,
        pairingToken: String,
        sessionId: Long = SessionIds.newSessionId(),
        allowLoopbackForTests: Boolean = false
    ): Long {
        val (sid, _, _) = clientVerifyAuth(
            socket.inetAddress, socket.getInputStream(), socket.getOutputStream(),
            pairingToken, sessionId, socket, allowLoopbackForTests
        )
        return sid
    }

    fun serverWithKeys(
        socket: Socket,
        pairingToken: String,
        allowLoopbackForTests: Boolean = false
    ): AuthenticatedSession {
        socket.tcpNoDelay = true
        return serverWithStreams(
            socket.inetAddress, socket.getInputStream(), socket.getOutputStream(),
            pairingToken, allowLoopbackForTests
        )
    }

    fun clientWithKeys(
        socket: Socket,
        pairingToken: String,
        sessionId: Long = SessionIds.newSessionId(),
        allowLoopbackForTests: Boolean = false
    ): AuthenticatedSession {
        socket.tcpNoDelay = true
        return clientWithStreams(
            socket.inetAddress, socket.getInputStream(), socket.getOutputStream(),
            pairingToken, sessionId, allowLoopbackForTests
        )
    }

    fun serverWithStreams(
        peerAddress: InetAddress,
        input: InputStream,
        output: OutputStream,
        pairingToken: String,
        allowLoopbackForTests: Boolean = false
    ): AuthenticatedSession {
        val (sessionId, clientNonce, serverNonce, _) = serverVerifyAuth(
            peerAddress, input, output, pairingToken, null, allowLoopbackForTests
        )
        val kp = PacketSeal.generateEcdhKeyPair()
        val peerKexFrame = ControlFraming.read(input) ?: throw ProtocolException("Missing KEY_EXCHANGE")
        if (peerKexFrame.type != ControlType.KEY_EXCHANGE || peerKexFrame.sessionId != sessionId) {
            throw ProtocolException("KEY_EXCHANGE required")
        }
        val peerKex = ControlMessageCodec.decode(peerKexFrame.type, peerKexFrame.payload) as? ControlMessage.KeyExchange
            ?: throw ProtocolException("Invalid KEY_EXCHANGE")
        ControlFraming.write(
            output,
            ControlFrame(
                ControlType.KEY_EXCHANGE, sequence = 1, sessionId = sessionId,
                payload = ControlMessageCodec.encode(ControlMessage.KeyExchange(kp.public.encoded))
            )
        )
        val shared = PacketSeal.ecdhSharedSecret(kp.private, peerKex.publicKey)
        val keys = PacketSeal.deriveSessionKeys(sessionId, shared, pairingToken, clientNonce, serverNonce)
        return AuthenticatedSession(sessionId, keys)
    }

    fun clientWithStreams(
        peerAddress: InetAddress,
        input: InputStream,
        output: OutputStream,
        pairingToken: String,
        sessionId: Long = SessionIds.newSessionId(),
        allowLoopbackForTests: Boolean = false
    ): AuthenticatedSession {
        val (sid, clientNonce, serverNonce) = clientVerifyAuth(
            peerAddress, input, output, pairingToken, sessionId, null, allowLoopbackForTests
        )
        val kp = PacketSeal.generateEcdhKeyPair()
        ControlFraming.write(
            output,
            ControlFrame(
                ControlType.KEY_EXCHANGE, sequence = 2, sessionId = sid,
                payload = ControlMessageCodec.encode(ControlMessage.KeyExchange(kp.public.encoded))
            )
        )
        val peerKexFrame = ControlFraming.read(input) ?: throw ProtocolException("Missing server KEY_EXCHANGE")
        if (peerKexFrame.type != ControlType.KEY_EXCHANGE || peerKexFrame.sessionId != sid) {
            throw ProtocolException("Invalid server KEY_EXCHANGE")
        }
        val peerKex = ControlMessageCodec.decode(peerKexFrame.type, peerKexFrame.payload) as? ControlMessage.KeyExchange
            ?: throw ProtocolException("Invalid server KEY_EXCHANGE payload")
        val shared = PacketSeal.ecdhSharedSecret(kp.private, peerKex.publicKey)
        val keys = PacketSeal.deriveSessionKeys(sid, shared, pairingToken, clientNonce, serverNonce)
        return AuthenticatedSession(sid, keys)
    }

    private data class ServerAuthState(
        val sessionId: Long,
        val clientNonce: ByteArray,
        val serverNonce: ByteArray,
        val capabilities: Int
    )

    private fun serverVerifyAuth(
        peerAddress: InetAddress,
        input: InputStream,
        output: OutputStream,
        pairingToken: String,
        socket: Socket?,
        allowLoopbackForTests: Boolean
    ): ServerAuthState {
        require(pairingToken.length in 16..128)
        requirePeer(peerAddress, allowLoopbackForTests)
        socket?.tcpNoDelay = true
        val hello = ControlFraming.read(input) ?: throw ProtocolException("Missing HELLO")
        if (hello.type != ControlType.HELLO) throw ProtocolException("HELLO required first")
        if (hello.sessionId == 0L) throw ProtocolException("SessionId cannot be zero")
        val message = ControlMessageCodec.decode(hello.type, hello.payload) as? ControlMessage.Hello
            ?: throw ProtocolException("Invalid HELLO")
        if (message.role != PHONE_ROLE) throw ProtocolException("Unsupported peer role")
        val sessionId = hello.sessionId
        val serverNonce = ByteArray(16).also(random::nextBytes)
        val ack = ControlMessage.HelloAck(true, serverNonce, CAPABILITIES)
        ControlFraming.write(
            output,
            ControlFrame(
                ControlType.HELLO_ACK, sequence = 0, sessionId = sessionId,
                payload = ControlMessageCodec.encode(ack)
            )
        )
        val confirm = ControlFraming.read(input) ?: throw ProtocolException("Missing PAIR_CONFIRM")
        if (confirm.type != ControlType.PAIR_CONFIRM || confirm.sessionId != sessionId) {
            throw ProtocolException("PAIR_CONFIRM required")
        }
        val pair = ControlMessageCodec.decode(confirm.type, confirm.payload) as? ControlMessage.PairConfirm
            ?: throw ProtocolException("Invalid PAIR_CONFIRM")
        val expectedProof = PacketSeal.pairProofHex(pairingToken, sessionId, message.nonce, serverNonce)
        if (!constantTimeEquals(pair.token, expectedProof)) throw ProtocolException("Pairing token rejected")
        return ServerAuthState(sessionId, message.nonce, serverNonce, message.capabilities)
    }

    private fun clientVerifyAuth(
        peerAddress: InetAddress,
        input: InputStream,
        output: OutputStream,
        pairingToken: String,
        sessionId: Long,
        socket: Socket?,
        allowLoopbackForTests: Boolean
    ): Triple<Long, ByteArray, ByteArray> {
        require(sessionId != 0L) { "SessionId cannot be zero" }
        requirePeer(peerAddress, allowLoopbackForTests)
        socket?.tcpNoDelay = true
        val clientNonce = ByteArray(16).also(random::nextBytes)
        val hello = ControlMessage.Hello(PHONE_ROLE, clientNonce, CAPABILITIES)
        ControlFraming.write(
            output,
            ControlFrame(
                ControlType.HELLO, sequence = 0, sessionId = sessionId,
                payload = ControlMessageCodec.encode(hello)
            )
        )
        val ackFrame = ControlFraming.read(input) ?: throw ProtocolException("Missing HELLO_ACK")
        if (ackFrame.type != ControlType.HELLO_ACK || ackFrame.sessionId != sessionId) {
            throw ProtocolException("Invalid HELLO_ACK")
        }
        val ack = ControlMessageCodec.decode(ackFrame.type, ackFrame.payload) as? ControlMessage.HelloAck
            ?: throw ProtocolException("Invalid HELLO_ACK payload")
        if (!ack.accepted) throw ProtocolException("Receiver rejected HELLO")
        val proof = PacketSeal.pairProofHex(pairingToken, sessionId, clientNonce, ack.nonce)
        ControlFraming.write(
            output,
            ControlFrame(
                ControlType.PAIR_CONFIRM, sequence = 1, sessionId = sessionId,
                payload = ControlMessageCodec.encode(ControlMessage.PairConfirm(proof))
            )
        )
        return Triple(sessionId, clientNonce, ack.nonce)
    }

    private fun requirePeer(address: InetAddress, allowLoopback: Boolean) {
        val host = address.hostAddress ?: throw ProtocolException("Missing peer address")
        if (allowLoopback && address.isLoopbackAddress) return
        LocalAddressPolicy.requireAllowed(host)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        var diff = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) {
            diff = diff or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 0 }.toInt()))
        }
        return diff == 0
    }

    const val PHONE_ROLE = 1
    const val CAPABILITIES = 3 // HMAC proof + ECDH-AEAD capable
}
