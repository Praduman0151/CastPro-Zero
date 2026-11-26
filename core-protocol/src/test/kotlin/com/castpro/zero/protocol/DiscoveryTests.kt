package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTests {
    @Test fun discoveryResponseCarriesOnlyPrivatePairingInformation() {
        val payload = TvPairingPayload(
            ipAddress = "192.168.43.7",
            tcpPort = 45670,
            udpPort = 45671,
            pairingToken = "OfflineToken_123456",
            expiryEpochSeconds = 10_000
        )
        val encoded = Discovery.encodeResponse(payload)
        assertEquals(payload, Discovery.decodeResponse(encoded, 9_000))
        assertFalse(encoded.contains("http"))
        assertFalse(encoded.contains("google"))
    }

    @Test fun discoveryRejectsExpiredAndMalformedResponses() {
        val payload = TvPairingPayload(
            ipAddress = "10.0.0.5",
            tcpPort = 45670,
            udpPort = 45671,
            pairingToken = "Token_123456789012",
            expiryEpochSeconds = 100
        )
        val encoded = Discovery.encodeResponse(payload)
        try { Discovery.decodeResponse(encoded, 100); fail("Expected expiry") } catch (_: ProtocolException) {}
        try { Discovery.decodeResponse("not-a-payload"); fail("Expected invalid payload") } catch (_: ProtocolException) {}
    }

    @Test fun discoveryRequestMagicIsStable() {
        assertArrayEquals("CPZ0-DISCOVER".toByteArray(Charsets.UTF_8), Discovery.request())
    }
}
