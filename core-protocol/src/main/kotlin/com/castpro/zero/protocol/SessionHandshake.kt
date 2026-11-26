package com.castpro.zero.protocol

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * TCP authentication shared by the Android sender/receiver and JVM loopback tests.
 * The same 64-bit SessionId is then carried by every TCP control frame and UDP fragment.
 */
object SessionHandshake {
    private val random = SecureRandom()

    fun server(socket: Socket, pairingToken: String, allowLoopbackForTests: Boolean = false): Long {
        require(pairingToken.length in 16..128)
        requirePeer(socket.inetAddress, allowLoopbackForTests)
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val hello = ControlFraming.read(input) ?: throw ProtocolException("Missing HELLO")
        if (hello.type != ControlType.HELLO) throw ProtocolException("HELLO required first")
        val message = ControlMessageCodec.decode(hello.type, hello.payload) as? ControlMessage.Hello
            ?: throw ProtocolException("Invalid HELLO")
        if (message.role != PHONE_ROLE) throw ProtocolException("Unsupported peer role")
        val sessionId = hello.sessionId
        val ack = ControlMessage.HelloAck(true, ByteArray(16).also(random::nextBytes), CAPABILITIES)
        ControlFraming.write(output, ControlFrame(ControlType.HELLO_ACK, sequence = 0, sessionId = sessionId,
            payload = ControlMessageCodec.encode(ack)))
        val confirm = ControlFraming.read(input) ?: throw ProtocolException("Missing PAIR_CONFIRM")
        if (confirm.type != ControlType.PAIR_CONFIRM || confirm.sessionId != sessionId)
            throw ProtocolException("PAIR_CONFIRM required")
        val pair = ControlMessageCodec.decode(confirm.type, confirm.payload) as? ControlMessage.PairConfirm
            ?: throw ProtocolException("Invalid PAIR_CONFIRM")
        if (!constantTimeEquals(pair.token, pairingToken)) throw ProtocolException("Pairing token rejected")
        return sessionId
    }

    fun client(socket: Socket, pairingToken: String, sessionId: Long = SessionIds.newSessionId(), allowLoopbackForTests: Boolean = false): Long {
        requirePeer(socket.inetAddress, allowLoopbackForTests)
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val hello = ControlMessage.Hello(PHONE_ROLE, ByteArray(16).also(random::nextBytes), CAPABILITIES)
        ControlFraming.write(output, ControlFrame(ControlType.HELLO, sequence = 0, sessionId = sessionId,
            payload = ControlMessageCodec.encode(hello)))
        val ackFrame = ControlFraming.read(input) ?: throw ProtocolException("Missing HELLO_ACK")
        if (ackFrame.type != ControlType.HELLO_ACK || ackFrame.sessionId != sessionId)
            throw ProtocolException("Invalid HELLO_ACK")
        val ack = ControlMessageCodec.decode(ackFrame.type, ackFrame.payload) as? ControlMessage.HelloAck
            ?: throw ProtocolException("Invalid HELLO_ACK payload")
        if (!ack.accepted) throw ProtocolException("Receiver rejected HELLO")
        ControlFraming.write(output, ControlFrame(ControlType.PAIR_CONFIRM, sequence = 1, sessionId = sessionId,
            payload = ControlMessageCodec.encode(ControlMessage.PairConfirm(pairingToken))))
        return sessionId
    }

    private fun requirePeer(address: InetAddress, allowLoopback: Boolean) {
        val host = address.hostAddress ?: throw ProtocolException("Missing peer address")
        if (allowLoopback && address.isLoopbackAddress) return
        LocalAddressPolicy.requireAllowed(host)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        var diff = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) diff = diff or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 0 }.toInt()))
        return diff == 0
    }

    const val PHONE_ROLE = 1
    const val CAPABILITIES = 1
}
