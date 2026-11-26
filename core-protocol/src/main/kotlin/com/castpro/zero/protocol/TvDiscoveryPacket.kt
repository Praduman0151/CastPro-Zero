package com.castpro.zero.protocol

import java.nio.charset.StandardCharsets
import java.util.Base64

data class TvDiscoveryPacket(
    val name: String,
    val ipAddress: String,
    val tcpPort: Int,
    val udpPort: Int,
    val pairingToken: String,
    val expiryEpochSeconds: Long
)

object TvDiscoveryPacketCodec {
    private const val PREFIX = "CPZ-DISCOVERY-1"
    fun encode(value: TvDiscoveryPacket): ByteArray {
        LocalAddressPolicy.requireAllowed(value.ipAddress)
        require(value.name.isNotBlank() && value.name.length <= 64)
        require(value.tcpPort in 1..65535 && value.udpPort in 1..65535)
        require(value.pairingToken.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        require(value.expiryEpochSeconds > 0)
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(value.name.toByteArray(StandardCharsets.UTF_8))
        return listOf(PREFIX, name, value.ipAddress, value.tcpPort.toString(), value.udpPort.toString(),
            value.pairingToken, value.expiryEpochSeconds.toString()).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }
    fun decode(bytes: ByteArray, nowEpochSeconds: Long? = null): TvDiscoveryPacket {
        if (bytes.size !in 1..2048) throw ProtocolException("Invalid discovery packet size")
        val p = String(bytes, StandardCharsets.UTF_8).split('\n')
        if (p.size != 7 || p[0] != PREFIX) throw ProtocolException("Invalid discovery packet")
        return try {
            val value = TvDiscoveryPacket(
                String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8), p[2],
                p[3].toInt(), p[4].toInt(), p[5], p[6].toLong()
            )
            LocalAddressPolicy.requireAllowed(value.ipAddress)
            if (value.name.isBlank() || value.name.length > 64 || value.tcpPort !in 1..65535 ||
                value.udpPort !in 1..65535 || !value.pairingToken.matches(Regex("[A-Za-z0-9_-]{16,128}")) ||
                value.expiryEpochSeconds <= 0) throw ProtocolException("Invalid discovery fields")
            if (nowEpochSeconds != null && value.expiryEpochSeconds <= nowEpochSeconds)
                throw ProtocolException("Discovery advertisement expired")
            value
        } catch (e: ProtocolException) { throw e }
        catch (e: Exception) { throw ProtocolException("Malformed discovery packet", e) }
    }
}
