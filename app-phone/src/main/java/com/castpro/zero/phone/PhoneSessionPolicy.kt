package com.castpro.zero.phone

import com.castpro.zero.protocol.LocalAddressPolicy
import com.castpro.zero.protocol.PairingPayloadCodec
import com.castpro.zero.protocol.TvPairingPayload

data class PhoneTarget(val ipAddress: String, val tcpPort: Int, val udpPort: Int, val pairingToken: String)

object PhoneTargetValidator {
    fun manual(ip: String, tcpPort: String, udpPort: String, token: String): PhoneTarget {
        LocalAddressPolicy.requireAllowed(ip)
        val tcp = tcpPort.toIntOrNull() ?: throw IllegalArgumentException("TCP port must be a number")
        val udp = udpPort.toIntOrNull() ?: throw IllegalArgumentException("UDP port must be a number")
        require(tcp in 1..65535 && udp in 1..65535) { "Ports must be between 1 and 65535" }
        require(token.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Pairing token is invalid" }
        return PhoneTarget(ip, tcp, udp, token)
    }

    fun qr(payload: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): PhoneTarget {
        val decoded: TvPairingPayload = PairingPayloadCodec.decode(payload, nowEpochSeconds)
        return PhoneTarget(decoded.ipAddress, decoded.tcpPort, decoded.udpPort, decoded.pairingToken)
    }
}

data class BitrateSessionState(val bitrateMbps: Int = 20, val connected: Boolean = false) {
    init { require(bitrateMbps in setOf(10, 20, 30)) }
    fun select(mbps: Int) = copy(bitrateMbps = mbps)
    fun connectedNow() = copy(connected = true)
    fun stopped() = copy(connected = false)
}
