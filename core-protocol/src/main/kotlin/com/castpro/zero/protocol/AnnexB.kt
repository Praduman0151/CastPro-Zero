package com.castpro.zero.protocol

enum class VideoCodec { AVC, HEVC }
data class CodecParameterSets(val codec:VideoCodec,val units:Map<Int,ByteArray>) {
    fun annexB():ByteArray=units.toSortedMap().values.fold(ByteArray(0)) { acc,nal -> acc+byteArrayOf(0,0,0,1)+nal }
}

object AnnexB {
    fun nalUnits(data:ByteArray):List<ByteArray> {
        val starts=mutableListOf<Pair<Int,Int>>(); var i=0
        while(i+3<data.size) {
            if(data[i]==0.toByte()&&data[i+1]==0.toByte()&&data[i+2]==1.toByte()) { starts+=i to 3; i+=3 }
            else if(i+4<data.size&&data[i]==0.toByte()&&data[i+1]==0.toByte()&&data[i+2]==0.toByte()&&data[i+3]==1.toByte()) { starts+=i to 4; i+=4 }
            else i++
        }
        return starts.mapIndexedNotNull { index,(start,prefix) ->
            val from=start+prefix; val end=if(index+1<starts.size) starts[index+1].first else data.size
            if(end<=from) null else data.copyOfRange(from,end).trimTrailingZeros()
        }
    }
    private fun ByteArray.trimTrailingZeros():ByteArray { var end=size; while(end>1&&this[end-1]==0.toByte()) end--; return copyOf(end) }
    private fun type(nal:ByteArray,codec:VideoCodec):Int? = nal.firstOrNull()?.toInt()?.let { if(codec==VideoCodec.AVC) it and 31 else (it ushr 1) and 63 }
    fun extract(data:ByteArray,codec:VideoCodec):CodecParameterSets? {
        val units=nalUnits(data); val required=if(codec==VideoCodec.AVC) setOf(7,8) else setOf(32,33,34)
        val sets=required.mapNotNull { t -> units.firstOrNull { type(it,codec)==t&&it.size>=2 }?.let { t to it } }.toMap()
        return if(sets.keys.containsAll(required)) CodecParameterSets(codec,sets) else null
    }
    fun isIdr(data:ByteArray,codec:VideoCodec):Boolean=nalUnits(data).any {
        val t=type(it,codec); if(codec==VideoCodec.AVC) t==5 else t in setOf(19,20,21)
    }
}

class DecodeGate(private val codec:VideoCodec) {
    enum class State { AWAITING_CSD, AWAITING_IDR, OPEN }
    var state=State.AWAITING_CSD; private set
    var parameterSets:CodecParameterSets?=null; private set
    fun onCsd(data:ByteArray):Boolean {
        val sets=AnnexB.extract(data,codec) ?: return false
        parameterSets=sets; state=State.AWAITING_IDR; return true
    }
    fun allowFrame(data:ByteArray):Boolean {
        if(state==State.AWAITING_CSD) return false
        if(state==State.AWAITING_IDR) { if(!AnnexB.isIdr(data,codec)) return false; state=State.OPEN }
        return state==State.OPEN
    }
    fun resetForDiscontinuity() { state=if(parameterSets==null) State.AWAITING_CSD else State.AWAITING_IDR }
}
