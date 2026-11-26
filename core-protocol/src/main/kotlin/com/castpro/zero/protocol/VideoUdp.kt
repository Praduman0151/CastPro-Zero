package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

const val VIDEO_HEADER_BYTES=32
const val MAX_UDP_DATAGRAM_BYTES=1360
const val MAX_VIDEO_PAYLOAD_BYTES=MAX_UDP_DATAGRAM_BYTES-VIDEO_HEADER_BYTES
const val DEFAULT_FRAME_DEADLINE_NS=20_000_000L
const val PLI_COALESCE_NS=100_000_000L
private val VIDEO_MAGIC=byteArrayOf(0x43,0x5a)

data class VideoFragment(val flags:Int,val sessionId:Long,val frameId:Long,val fragmentIndex:Int,val fragmentCount:Int,val payload:ByteArray,val captureTimestampNs:Long) {
    fun encode():ByteArray {
        require(flags in 0..255&&frameId in 0..0xffff_ffffL&&fragmentCount in 1..65535&&fragmentIndex in 0 until fragmentCount&&payload.size<=MAX_VIDEO_PAYLOAD_BYTES)
        val b=ByteBuffer.allocate(VIDEO_HEADER_BYTES+payload.size).order(ByteOrder.BIG_ENDIAN)
        b.put(VIDEO_MAGIC).put(1.toByte()).put(flags.toByte()).putLong(sessionId).putInt(frameId.toInt())
            .putShort(fragmentIndex.toShort()).putShort(fragmentCount.toShort()).putShort(payload.size.toShort()).putShort(0)
            .putLong(captureTimestampNs).put(payload)
        return b.array()
    }
    companion object {
        fun decode(packet:ByteArray):VideoFragment {
            if(packet.size !in VIDEO_HEADER_BYTES..MAX_UDP_DATAGRAM_BYTES)throw ProtocolException("Invalid UDP size")
            val b=ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            if(b.get()!=VIDEO_MAGIC[0]||b.get()!=VIDEO_MAGIC[1])throw ProtocolException("Invalid CZ magic")
            val version=b.get().toInt() and 255;if(version!=1)throw ProtocolException("Unsupported video version")
            val flags=b.get().toInt() and 255;val session=b.long;val frame=b.int.toLong() and 0xffff_ffffL
            val index=b.short.toInt() and 65535;val count=b.short.toInt() and 65535
            val length=b.short.toInt() and 65535;b.short
            val timestamp=b.long
            if(count==0||index>=count||length!=b.remaining())throw ProtocolException("Invalid fragment metadata")
            return VideoFragment(flags,session,frame,index,count,ByteArray(length).also(b::get),timestamp)
        }
    }
}
object VideoFragmenter {
    fun fragment(frame:ByteArray,sessionId:Long,frameId:Long,flags:Int,timestampNs:Long):List<ByteArray>{
        require(frame.isNotEmpty());val count=(frame.size+MAX_VIDEO_PAYLOAD_BYTES-1)/MAX_VIDEO_PAYLOAD_BYTES;require(count<=4096)
        return (0 until count).map{i->val from=i*MAX_VIDEO_PAYLOAD_BYTES;val to=minOf(frame.size,from+MAX_VIDEO_PAYLOAD_BYTES);var f=flags;if(i==0)f=f or 1;if(i==count-1)f=f or 2;VideoFragment(f,sessionId,frameId,i,count,frame.copyOfRange(from,to),timestampNs).encode()}
    }
}
data class ReassembledFrame(val frameId:Long,val flags:Int,val bytes:ByteArray,val captureTimestampNs:Long)
data class ReassemblyOutcome(val frames:List<ReassembledFrame> = emptyList(),val requestPli:Boolean=false,val dropped:Int=0,val rejected:Boolean=false)
class VideoReassembler(private val sessionId:Long,private val frameDeadlineNs:Long=DEFAULT_FRAME_DEADLINE_NS,private val maxInFlight:Int=3,private val pliCoalesceNs:Long=PLI_COALESCE_NS,private val maxFrameBytes:Int=4*1024*1024){
    private data class Pending(val created:Long,val count:Int,val flags:Int,val timestamp:Long,val parts:Array<ByteArray?>,var total:Int=0,var received:Int=0)
    private val pending=LinkedHashMap<Long,Pending>();private var newestCompleted:Long?=null;private var lastPli=Long.MIN_VALUE
    @Synchronized fun accept(packet:ByteArray,nowNs:Long):ReassemblyOutcome{
        val expired=expire(nowNs);val f=try{VideoFragment.decode(packet)}catch(_:Exception){return ReassemblyOutcome(requestPli=expired,dropped=1,rejected=true)}
        if(f.sessionId!=sessionId)return ReassemblyOutcome(requestPli=expired,dropped=1,rejected=true)
        if(newestCompleted?.let{older(f.frameId,it)}==true)return ReassemblyOutcome(requestPli=expired,dropped=1)
        var dropped=0
        if(!pending.containsKey(f.frameId)&&pending.size>=maxInFlight){pending.remove(pending.keys.first());dropped++}
        val p=pending.getOrPut(f.frameId){Pending(nowNs,f.fragmentCount,f.flags,f.captureTimestampNs,arrayOfNulls(f.fragmentCount))}
        if(p.count!=f.fragmentCount||p.timestamp!=f.captureTimestampNs){pending.remove(f.frameId);return ReassemblyOutcome(requestPli=shouldPli(nowNs),dropped=dropped+1,rejected=true)}
        if(p.parts[f.fragmentIndex]!=null)return ReassemblyOutcome(requestPli=expired,dropped=dropped)
        if(p.total+f.payload.size>maxFrameBytes){pending.remove(f.frameId);return ReassemblyOutcome(requestPli=shouldPli(nowNs),dropped=dropped+1,rejected=true)}
        p.parts[f.fragmentIndex]=f.payload;p.total+=f.payload.size;p.received++
        if(p.received!=p.count)return ReassemblyOutcome(requestPli=expired,dropped=dropped)
        val result=ByteArray(p.total);var offset=0;p.parts.forEach{part->val bytes=part?:return ReassemblyOutcome(requestPli=expired,dropped=dropped);bytes.copyInto(result,offset);offset+=bytes.size}
        pending.remove(f.frameId);newestCompleted=f.frameId
        return ReassemblyOutcome(listOf(ReassembledFrame(f.frameId,p.flags,result,p.timestamp)),expired,dropped)
    }
    @Synchronized fun expire(nowNs:Long):Boolean{val old=pending.filterValues{nowNs-it.created>=frameDeadlineNs}.keys.toList();old.forEach(pending::remove);return old.isNotEmpty()&&shouldPli(nowNs)}
    private fun shouldPli(now:Long):Boolean{if(lastPli!=Long.MIN_VALUE&&now-lastPli<pliCoalesceNs)return false;lastPli=now;return true}
    private fun older(a:Long,b:Long):Boolean=((a-b) and 0xffff_ffffL)>0x7fff_ffffL
}
