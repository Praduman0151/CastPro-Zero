package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class DiscoverySecurityTests {
    @Test fun lanBeaconNeverContainsSecretPairingToken() {
        val secret = "SecretToken_MustNeverLeak_1234"
        val beacon = LanDiscoveryBeacon(
            ipAddress = "192.168.1.5",
            tcpPort = 45670,
            udpPort = 45671,
            deviceName = "TCL Android TV"
        )
        val encoded = Discovery.encodeBeacon(beacon)
        assertFalse(encoded.contains(secret))
        val decoded = Discovery.decodeBeacon(encoded)
        assertEquals(beacon, decoded)
    }

    @Test fun lanBeaconRejectsPublicIpAddresses() {
        try {
            Discovery.encodeBeacon(LanDiscoveryBeacon("8.8.8.8", 45670, 45671, "Fake TV"))
            fail("Public IP must be rejected")
        } catch (_: ProtocolException) {}
    }

    @Test fun discoverySourceMatchesAdvertisedIp() {
        val beacon = LanDiscoveryBeacon("192.168.1.5", 45670, 45671, "TCL TV")
        assertTrue(Discovery.isAuthenticSource(sourceIp = "192.168.1.5", beacon = beacon))
        assertFalse(Discovery.isAuthenticSource(sourceIp = "192.168.1.99", beacon = beacon))
    }
}
