package com.castpro.zero.protocol

/**
 * LAN-only discovery used while the TV is idle. The phone sends a small magic
 * datagram to the local broadcast address; the TV answers with the same pairing
 * payload that its offline QR code encodes. No internet, cloud relay or
 * third-party discovery service is involved.
 */
object Discovery {
    const val DISCOVERY_PORT = 45672
    const val REQUEST_MAGIC = "CPZ0-DISCOVER"

    fun request(): ByteArray = REQUEST_MAGIC.toByteArray(Charsets.UTF_8)

    fun encodeResponse(payload: TvPairingPayload): String = PairingPayloadCodec.encode(payload)

    fun decodeResponse(value: String, nowEpochSeconds: Long? = null): TvPairingPayload =
        PairingPayloadCodec.decode(value.trim(), nowEpochSeconds)
}
