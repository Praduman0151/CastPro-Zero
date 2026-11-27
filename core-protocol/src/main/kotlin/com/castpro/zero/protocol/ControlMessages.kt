package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

sealed interface ControlMessage {
    data class Hello(val role:Int,val nonce:ByteArray,val capabilities:Int):ControlMessage
    data class HelloAck(val accepted:Boolean,val nonce:ByteArray,val capabilities:Int):ControlMessage
    data class PairConfirm(val token:String):ControlMessage
    data class StreamConfig(val codec:Int,val width:Int,val height:Int,val fps:Int,val bitrate:Int,val colorStandard:Int,val colorTransfer:Int,val colorRange:Int):ControlMessage
    data class VideoCsd(val codec:Int,val annexB:ByteArray):ControlMessage
    data class Ping(val timestampNs:Long,val pingSequence:Long):ControlMessage
    data class Pong(val timestampNs:Long,val pingSequence:Long):ControlMessage
    data class Pli(val reason:Int):ControlMessage
    data class Stop(val reason:Int):ControlMessage
    /** Phone-measured RTT, reported back to the TV for its on-screen HUD. */
    data class RttReport(val rttMs:Long):ControlMessage
    /** Device audio format; sent on the TCP control channel before audio UDP frames. */
    data class AudioConfig(val sampleRateHz:Int,val channelCount:Int,val bitDepth:Int,val encoding:Int):ControlMessage
    /** Selective retransmission request for missing UDP video fragments of a recent frame. */
    data class FragmentNack(val frameId:Long,val missingIndices:List<Int>):ControlMessage
    data class Error(val code:Int,val message:String):ControlMessage
}

object ControlMessageCodec {
    fun typeOf(m:ControlMessage)=when(m) {
        is ControlMessage.Hello->ControlType.HELLO
        is ControlMessage.HelloAck->ControlType.HELLO_ACK
        is ControlMessage.PairConfirm->ControlType.PAIR_CONFIRM
        is ControlMessage.StreamConfig->ControlType.STREAM_CONFIG
        is ControlMessage.VideoCsd->ControlType.VIDEO_CSD
        is ControlMessage.Ping->ControlType.PING
        is ControlMessage.Pong->ControlType.PONG
        is ControlMessage.Pli->ControlType.PLI
        is ControlMessage.Stop->ControlType.STOP
        is ControlMessage.RttReport->ControlType.RTT_REPORT
        is ControlMessage.AudioConfig->ControlType.AUDIO_CONFIG
        is ControlMessage.FragmentNack->ControlType.FRAGMENT_NACK
        is ControlMessage.Error->ControlType.ERROR
    }
    fun encode(m:ControlMessage):ByteArray {
        val out=when(m) {
            is ControlMessage.Hello->{require(m.nonce.size==16);ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).put(m.role.toByte()).put(m.nonce).putInt(m.capabilities).array()}
            is ControlMessage.HelloAck->{require(m.nonce.size==16);ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).put((if(m.accepted)1 else 0).toByte()).put(m.nonce).putInt(m.capabilities).array()}
            is ControlMessage.PairConfirm->stringField(m.token,256)
            is ControlMessage.StreamConfig->ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putInt(m.codec).putInt(m.width).putInt(m.height).putInt(m.fps).putInt(m.bitrate).putInt(m.colorStandard).putInt(m.colorTransfer).putInt(m.colorRange).array()
            is ControlMessage.VideoCsd->{require(m.annexB.size in 1..MAX_CONTROL_PAYLOAD-1);byteArrayOf(m.codec.toByte())+m.annexB}
            is ControlMessage.Ping->ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(m.timestampNs).putLong(m.pingSequence).array()
            is ControlMessage.Pong->ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(m.timestampNs).putLong(m.pingSequence).array()
            is ControlMessage.Pli->byteArrayOf(m.reason.toByte())
            is ControlMessage.Stop->ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(m.reason.toShort()).array()
            is ControlMessage.RttReport->ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(m.rttMs).array()
            is ControlMessage.AudioConfig->ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(m.sampleRateHz).putInt(m.channelCount).putInt(m.bitDepth).putInt(m.encoding).array()
            is ControlMessage.FragmentNack->{
                require(m.missingIndices.size in 1..32 && m.missingIndices.all { it in 0..65535 })
                val buf = ByteBuffer.allocate(10 + m.missingIndices.size * 2).order(ByteOrder.BIG_ENDIAN)
                buf.putLong(m.frameId).putShort(m.missingIndices.size.toShort())
                m.missingIndices.forEach { buf.putShort(it.toShort()) }
                buf.array()
            }
            is ControlMessage.Error->ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(m.code).array()+stringField(m.message,1024)
        }
        require(out.size<=MAX_CONTROL_PAYLOAD)
        return out
    }
    fun decode(type:ControlType,payload:ByteArray):ControlMessage {
        if(payload.size>MAX_CONTROL_PAYLOAD) throw ProtocolException("Payload too large")
        return try {
            val b=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            when(type) {
                ControlType.HELLO->{exact(payload,21);val role=b.get().toInt() and 255;val n=ByteArray(16).also(b::get);ControlMessage.Hello(role,n,b.int)}
                ControlType.HELLO_ACK->{exact(payload,21);val accepted=b.get().toInt()==1;val n=ByteArray(16).also(b::get);ControlMessage.HelloAck(accepted,n,b.int)}
                ControlType.PAIR_CONFIRM->ControlMessage.PairConfirm(readStringField(payload,256))
                ControlType.STREAM_CONFIG->{exact(payload,32);ControlMessage.StreamConfig(b.int,b.int,b.int,b.int,b.int,b.int,b.int,b.int)}
                ControlType.VIDEO_CSD->{if(payload.size<2)throw ProtocolException("Missing CSD");ControlMessage.VideoCsd(b.get().toInt() and 255,ByteArray(b.remaining()).also(b::get))}
                ControlType.PING->{exact(payload,16);ControlMessage.Ping(b.long,b.long)}
                ControlType.PONG->{exact(payload,16);ControlMessage.Pong(b.long,b.long)}
                ControlType.PLI->{exact(payload,1);ControlMessage.Pli(b.get().toInt() and 255)}
                ControlType.STOP->{exact(payload,2);ControlMessage.Stop(b.short.toInt() and 65535)}
                ControlType.RTT_REPORT->{exact(payload,8);ControlMessage.RttReport(b.long)}
                ControlType.AUDIO_CONFIG->{exact(payload,16);ControlMessage.AudioConfig(b.int,b.int,b.int,b.int)}
                ControlType.FRAGMENT_NACK->{
                    if(payload.size<12)throw ProtocolException("Invalid FRAGMENT_NACK")
                    val fid=b.long; val count=b.short.toInt() and 65535
                    if(count !in 1..32 || payload.size!=10+count*2)throw ProtocolException("Invalid FRAGMENT_NACK count")
                    val indices=List(count){b.short.toInt() and 65535}
                    ControlMessage.FragmentNack(fid,indices)
                }
                ControlType.ERROR->{if(payload.size<6)throw ProtocolException("Invalid ERROR");val code=b.int;val rest=ByteArray(b.remaining()).also(b::get);ControlMessage.Error(code,readStringField(rest,1024))}
            }
        } catch(e:ProtocolException){throw e}catch(e:Exception){throw ProtocolException("Malformed $type payload",e)}
    }
    private fun exact(p:ByteArray,n:Int){if(p.size!=n)throw ProtocolException("Expected $n bytes, got ${p.size}")}
    private fun stringField(s:String,max:Int):ByteArray{val bytes=s.toByteArray(StandardCharsets.UTF_8);require(bytes.size<=max);return ByteBuffer.allocate(2+bytes.size).order(ByteOrder.BIG_ENDIAN).putShort(bytes.size.toShort()).put(bytes).array()}
    private fun readStringField(bytes:ByteArray,max:Int):String{
        if(bytes.size<2)throw ProtocolException("Missing string length")
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);val n=b.short.toInt() and 65535
        if(n>max||n!=b.remaining())throw ProtocolException("Invalid string length")
        val value=ByteArray(n);b.get(value)
        return try{StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString()}catch(e:Exception){throw ProtocolException("Invalid UTF-8",e)}
    }
}
