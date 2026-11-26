package com.castpro.zero.phone

import com.castpro.zero.protocol.Discovery
import com.castpro.zero.protocol.PairingPayloadCodec
import com.castpro.zero.protocol.TvPairingPayload
import org.junit.Assert.*
import org.junit.Test

class PhoneSessionPolicyTests {
    private val token = "abcdefghijklmnop_1234567890"

    @Test fun manualTargetAcceptsOnlyPrivateIpv4AndValidPorts() {
        val target = PhoneTargetValidator.manual("192.168.1.40", "45670", "45671", token)
        assertEquals("192.168.1.40", target.ipAddress)
        assertEquals(45670, target.tcpPort)
        assertEquals(45671, target.udpPort)
    }

    @Test(expected = Exception::class)
    fun manualTargetRejectsPublicAddress() {
        PhoneTargetValidator.manual("8.8.8.8", "45670", "45671", token)
    }

    @Test fun qrTargetValidatesPayloadAndExpiry() {
        val payload = PairingPayloadCodec.encode(TvPairingPayload(
            ipAddress = "10.0.0.5", tcpPort = 45670, udpPort = 45671,
            pairingToken = token, expiryEpochSeconds = 20_000
        ))
        val target = PhoneTargetValidator.qr(payload, nowEpochSeconds = 10_000)
        assertEquals("10.0.0.5", target.ipAddress)
        assertEquals(token, target.pairingToken)
    }

    @Test(expected = Exception::class)
    fun expiredQrIsRejected() {
        val payload = PairingPayloadCodec.encode(TvPairingPayload(
            ipAddress = "10.0.0.5", tcpPort = 45670, udpPort = 45671,
            pairingToken = token, expiryEpochSeconds = 100
        ))
        PhoneTargetValidator.qr(payload, nowEpochSeconds = 100)
    }

    @Test fun bitrateAndSessionStateTransitionsAreDeterministic() {
        val initial = BitrateSessionState()
        assertEquals(20, initial.bitrateMbps)
        assertFalse(initial.connected)
        val connected = initial.select(30).connectedNow()
        assertEquals(30, connected.bitrateMbps)
        assertTrue(connected.connected)
        assertFalse(connected.stopped().connected)
    }
}

class PhoneDiscoveryParsingTests {
    @Test fun udpDiscoveryResponseUsesTheSameQrPayloadParser() {
        val original = TvPairingPayload("TV", "192.168.1.9", 45670, 45671,
            "abcdefghijklmnop1234", 5000)
        val decoded = Discovery.decodeResponse(Discovery.encodeResponse(original), nowEpochSeconds = 4000)
        assertEquals(original, decoded)
    }

    @Test(expected = Exception::class)
    fun malformedUdpDiscoveryResponseIsRejected() {
        Discovery.decodeResponse("not-a-cpz-packet", nowEpochSeconds = 4000)
    }
}
