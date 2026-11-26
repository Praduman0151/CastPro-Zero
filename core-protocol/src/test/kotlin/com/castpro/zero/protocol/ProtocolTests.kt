package com.castpro.zero.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTests {
    private val nonce=ByteArray(16){it.toByte()}
    private fun roundTrip(m:ControlMessage) {
        val decoded=ControlMessageCodec.decode(ControlMessageCodec.typeOf(m),ControlMessageCodec.encode(m))
        when {
            m is ControlMessage.Hello && decoded is ControlMessage.Hello -> { assertEquals(m.role,decoded.role); assertArrayEquals(m.nonce,decoded.nonce); assertEquals(m.capabilities,decoded.capabilities) }
            m is ControlMessage.HelloAck && decoded is ControlMessage.HelloAck -> { assertEquals(m.accepted,decoded.accepted); assertArrayEquals(m.nonce,decoded.nonce); assertEquals(m.capabilities,decoded.capabilities) }
            m is ControlMessage.PairConfirm && decoded is ControlMessage.PairConfirm -> assertEquals(m.token,decoded.token)
            m is ControlMessage.StreamConfig && decoded is ControlMessage.StreamConfig -> assertEquals(m,decoded)
            m is ControlMessage.VideoCsd && decoded is ControlMessage.VideoCsd -> { assertEquals(m.codec,decoded.codec); assertArrayEquals(m.annexB,decoded.annexB) }
            m is ControlMessage.Ping && decoded is ControlMessage.Ping -> assertEquals(m,decoded)
            m is ControlMessage.Pong && decoded is ControlMessage.Pong -> assertEquals(m,decoded)
            m is ControlMessage.Pli && decoded is ControlMessage.Pli -> assertEquals(m,decoded)
            m is ControlMessage.Stop && decoded is ControlMessage.Stop -> assertEquals(m,decoded)
            m is ControlMessage.Error && decoded is ControlMessage.Error -> assertEquals(m,decoded)
            else -> fail("Round-trip mismatch: $m")
        }
    }

    @Test fun everyControlSerializerRoundTrips() {
        listOf(ControlMessage.Hello(1,nonce,7),ControlMessage.HelloAck(true,nonce,3),
            ControlMessage.PairConfirm("PairingToken_123456789"),
            ControlMessage.StreamConfig(1,1920,1080,60,25_000_000,1,3,2),
            ControlMessage.VideoCsd(1,byteArrayOf(0,0,0,1,0x67,1,0,0,0,1,0x68,1)),
            ControlMessage.Ping(123456,9),ControlMessage.Pong(123456,9),ControlMessage.Pli(1),
            ControlMessage.Stop(2),ControlMessage.Error(500,"bad frame")).forEach(::roundTrip)
    }

    @Test fun fragmentedTcpReadsAndCleanEof() {
        val out=ByteArrayOutputStream()
        ControlFraming.write(out,ControlFrame(ControlType.PING,sequence=4,sessionId=99,payload=byteArrayOf(1,2)))
        val input=object:ByteArrayInputStream(out.toByteArray()) {
            override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,minOf(2,len))
        }
        val frame=ControlFraming.read(input)!!
        assertEquals(99L,frame.sessionId); assertArrayEquals(byteArrayOf(1,2),frame.payload)
        assertNull(ControlFraming.read(input))
    }

    @Test fun controlPayloadBoundsAreEnforced() {
        try { ControlFraming.write(ByteArrayOutputStream(),ControlFrame(ControlType.ERROR,sequence=0,sessionId=1,payload=ByteArray(MAX_CONTROL_PAYLOAD+1))); fail("Expected bound") }
        catch(_:IllegalArgumentException) {}
    }

    @Test fun udpFragmentationReassemblesOutOfOrder() {
        val original=ByteArray(5000){(it%251).toByte()}
        val packets=VideoFragmenter.fragment(original,77,12,0,1000)
        assertTrue(packets.all{it.size<=1360})
        val receiver=VideoReassembler(77); var result=ReassemblyOutcome()
        packets.reversed().forEach{result=receiver.accept(it,1_000_000)}
        assertEquals(1,result.frames.size); assertArrayEquals(original,result.frames.single().bytes)
    }

    @Test fun packetLossTriggersPliAndCoalesces() {
        val packets=VideoFragmenter.fragment(ByteArray(3000){it.toByte()},5,1,0,10)
        val receiver=VideoReassembler(5)
        assertFalse(receiver.accept(packets.first(),1_000_000).requestPli)
        assertTrue(receiver.expire(22_000_001))
        val second=VideoFragmenter.fragment(ByteArray(3000){it.toByte()},5,2,0,20)
        receiver.accept(second.first(),30_000_000)
        assertFalse(receiver.expire(52_000_001))
        assertTrue(receiver.expire(140_000_001))
    }

    @Test fun wrongSessionIsRejected() {
        val packet=VideoFragmenter.fragment(byteArrayOf(1,2,3),8,1,0,10).single()
        val result=VideoReassembler(9).accept(packet,100)
        assertTrue(result.rejected); assertTrue(result.frames.isEmpty())
    }

    @Test fun qrPayloadRoundTripsAndExpires() {
        val value=TvPairingPayload(ipAddress="192.168.1.20",tcpPort=4567,udpPort=4568,pairingToken="Abcdefghijklmnop_123",expiryEpochSeconds=500)
        val encoded=PairingPayloadCodec.encode(value)
        assertEquals(value,PairingPayloadCodec.decode(encoded,400))
        try { PairingPayloadCodec.decode(encoded,500); fail("Expected expiry") } catch(_:ProtocolException) {}
    }

    @Test fun avcAnnexBCsdGatesUntilIdr() {
        val gate=DecodeGate(VideoCodec.AVC)
        val csd=byteArrayOf(0,0,0,1,0x67,0x42,0,0x1e,0,0,0,1,0x68,1)
        val nonIdr=byteArrayOf(0,0,0,1,0x41,1); val idr=byteArrayOf(0,0,0,1,0x65,1)
        assertFalse(gate.allowFrame(idr)); assertTrue(gate.onCsd(csd))
        assertFalse(gate.allowFrame(nonIdr)); assertTrue(gate.allowFrame(idr))
        assertEquals(DecodeGate.State.OPEN,gate.state)
    }

    @Test fun hevcRequiresVpsSpsPpsAndIdr() {
        val csd=byteArrayOf(0,0,0,1,0x40,1,0,0,0,1,0x42,1,0,0,0,1,0x44,1)
        val gate=DecodeGate(VideoCodec.HEVC)
        assertTrue(gate.onCsd(csd))
        assertFalse(gate.allowFrame(byteArrayOf(0,0,0,1,0x02,1)))
        assertTrue(gate.allowFrame(byteArrayOf(0,0,0,1,0x26,1)))
    }
}
