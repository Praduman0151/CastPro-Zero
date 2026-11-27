package com.castpro.zero.protocol

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Unauthenticated LAN beacon carrying ONLY endpoint identity/ports — NEVER secret pairing tokens.
 * Paired phones verify or obtain the secret pairing token visually via offline QR or on-TV display.
 */
data class LanDiscoveryBeacon(
    val ipAddress: String,
    val tcpPort: Int,
    val udpPort: Int,
    val deviceName: String = "CastPro-Zero TV",
    val version: Int = 2
)

/**
 * LAN-only discovery used while the TV is idle. No internet, cloud relay or
 * third-party discovery service is involved.
 */
object Discovery {
    const val DISCOVERY_PORT = 45672
    const val REQUEST_MAGIC = "CPZ0-DISCOVER"
    private const val BEACON_PREFIX = "CPZ0-LAN"

    fun request(): ByteArray = REQUEST_MAGIC.toByteArray(Charsets.UTF_8)

    fun encodeBeacon(beacon: LanDiscoveryBeacon): String {
        LocalAddressPolicy.requireAllowed(beacon.ipAddress)
        if (beacon.tcpPort !in 1..65535 || beacon.udpPort !in 1..65535) {
            throw ProtocolException("Invalid discovery ports")
        }
        if (beacon.deviceName.isBlank() || beacon.deviceName.length > 64) {
            throw ProtocolException("Invalid discovery device name")
        }
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(beacon.deviceName.toByteArray(StandardCharsets.UTF_8))
        val raw = listOf(
            BEACON_PREFIX,
            beacon.version.toString(),
            beacon.ipAddress,
            beacon.tcpPort.toString(),
            beacon.udpPort.toString(),
            encodedName
        ).joinToString("\n")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeBeacon(encoded: String): LanDiscoveryBeacon {
        val trimmed = encoded.trim()
        if (trimmed.length > 2048) throw ProtocolException("Discovery beacon too large")
        val raw = try {
            String(Base64.getUrlDecoder().decode(trimmed), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw ProtocolException("Invalid beacon encoding", e)
        }
        val p = raw.split('\n')
        if (p.size != 6 || p[0] != BEACON_PREFIX) throw ProtocolException("Invalid CPZ0-LAN beacon")
        return try {
            val version = p[1].toInt()
            if (version != 2) throw ProtocolException("Unsupported beacon version")
            val ip = LocalAddressPolicy.requireAllowed(p[2])
            val tcp = p[3].toInt()
            val udp = p[4].toInt()
            if (tcp !in 1..65535 || udp !in 1..65535) throw ProtocolException("Invalid beacon ports")
            val name = String(Base64.getUrlDecoder().decode(p[5]), StandardCharsets.UTF_8)
            if (name.isBlank() || name.length > 64) throw ProtocolException("Invalid beacon device name")
            LanDiscoveryBeacon(ip, tcp, udp, name, version)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            throw ProtocolException("Malformed discovery beacon", e)
        }
    }

    fun isAuthenticSource(sourceIp: String, beacon: LanDiscoveryBeacon): Boolean =
        LocalAddressPolicy.isAllowed(sourceIp) && sourceIp == beacon.ipAddress

    fun encodeResponse(payload: TvPairingPayload): String = PairingPayloadCodec.encode(payload)

    fun decodeResponse(value: String, nowEpochSeconds: Long? = null): TvPairingPayload =
        PairingPayloadCodec.decode(value.trim(), nowEpochSeconds)
}
