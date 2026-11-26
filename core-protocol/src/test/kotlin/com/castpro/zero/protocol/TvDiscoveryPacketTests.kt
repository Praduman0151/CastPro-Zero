package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test

class TvDiscoveryPacketTests {
    @Test fun packetRoundTripsAllDiscoveryFields() {
        val packet = TvDiscoveryPacket("Living Room TV", "192.168.1.42", 45670, 45671,
            "abcdefghijklmnop_1234567890", 9000)
        assertEquals(packet, TvDiscoveryPacketCodec.decode(TvDiscoveryPacketCodec.encode(packet), 8000))
    }
    @Test fun expiredDiscoveryPacketIsRejected() {
        val packet = TvDiscoveryPacket("TV", "10.0.0.4", 45670, 45671, "abcdefghijklmnop1234", 100)
        try { TvDiscoveryPacketCodec.decode(TvDiscoveryPacketCodec.encode(packet), 100); fail("expired") }
        catch (_: ProtocolException) { }
    }
    @Test fun publicAddressIsRejected() {
        val packet = TvDiscoveryPacket("TV", "8.8.8.8", 45670, 45671, "abcdefghijklmnop1234", 1000)
        try { TvDiscoveryPacketCodec.encode(packet); fail("public address") }
        catch (_: Exception) { }
    }
}
