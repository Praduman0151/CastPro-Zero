package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class LocalAddressPolicyTests {
    @Test fun allowsOnlyRequestedPrivateAndLinkLocalRanges() {
        listOf("10.0.0.1","10.255.255.254","172.16.0.1","172.31.255.254","192.168.1.2","169.254.12.3").forEach { assertTrue(it, LocalAddressPolicy.isAllowed(it)) }
    }
    @Test fun rejectsPublicHostnamesAndMalformedAddresses() {
        listOf("8.8.8.8","1.1.1.1","172.15.0.1","172.32.0.1","192.167.1.1","localhost","tv.local","example.com","192.168.1","192.168.1.999","192.168.01.1","::1"," 192.168.1.1").forEach { assertFalse(it, LocalAddressPolicy.isAllowed(it)) }
    }
    @Test fun pairingCodecRejectsPublicAddresses() {
        try { PairingPayloadCodec.encode(TvPairingPayload(ipAddress="8.8.8.8",tcpPort=4000,udpPort=4001,pairingToken="abcdefghijklmnop",expiryEpochSeconds=999)); fail("Public address accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun burstPacingThresholdIsDeterministic() {
        assertEquals(UdpBurstPacer.Plan(30,0), UdpBurstPacer.plan(30))
        assertEquals(UdpBurstPacer.Plan(12,350), UdpBurstPacer.plan(31))
        assertTrue(UdpBurstPacer.shouldRequestRecovery(1))
        assertFalse(UdpBurstPacer.shouldRequestRecovery(0))
    }
}
