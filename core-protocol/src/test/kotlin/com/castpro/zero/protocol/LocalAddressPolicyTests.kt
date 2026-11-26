package com.castpro.zero.protocol
import org.junit.Assert.*
import org.junit.Test
class LocalAddressPolicyTests {
 @Test fun acceptsPrivateAndLinkLocalOnly(){listOf("10.1.2.3","172.16.1.1","172.31.1.1","192.168.1.2","169.254.2.1").forEach{assertTrue(it,LocalAddressPolicy.isAllowed(it))}}
 @Test fun rejectsPublicHostnamesAndMalformed(){listOf("8.8.8.8","1.1.1.1","172.15.1.1","172.32.1.1","192.167.1.1","localhost","tv.local","192.168.1","192.168.1.999","192.168.01.1","::1").forEach{assertFalse(it,LocalAddressPolicy.isAllowed(it))}}
 @Test fun pairingRejectsPublicIp(){try{PairingPayloadCodec.encode(TvPairingPayload(ipAddress="8.8.8.8",tcpPort=4000,udpPort=4001,pairingToken="abcdefghijklmnop",expiryEpochSeconds=999));fail()}catch(_:ProtocolException){}}
 @Test fun burstPacingAndRecovery(){assertEquals(UdpBurstPacer.Plan(30,0),UdpBurstPacer.plan(30));assertEquals(UdpBurstPacer.Plan(12,350),UdpBurstPacer.plan(31));assertTrue(UdpBurstPacer.shouldRequestRecovery(1));assertFalse(UdpBurstPacer.shouldRequestRecovery(0))}
}
