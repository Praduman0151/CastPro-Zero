package com.castpro.zero.protocol
import java.nio.charset.StandardCharsets
import java.util.Base64
data class TvPairingPayload(val version:Int=1,val ipAddress:String,val tcpPort:Int,val udpPort:Int,val pairingToken:String,val expiryEpochSeconds:Long)
object PairingPayloadCodec {
    private const val PREFIX="CPZ0"
    fun encode(value:TvPairingPayload):String { validate(value); val raw=listOf(PREFIX,value.version.toString(),value.ipAddress,value.tcpPort.toString(),value.udpPort.toString(),value.pairingToken,value.expiryEpochSeconds.toString()).joinToString("\n"); return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8)) }
    fun decode(encoded:String,nowEpochSeconds:Long?=null):TvPairingPayload {
        if(encoded.length>2048)throw ProtocolException("Pairing QR payload too large")
        val raw=try{String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8)}catch(e:Exception){throw ProtocolException("Invalid pairing encoding",e)}
        val p=raw.split('\n'); if(p.size!=7||p[0]!=PREFIX)throw ProtocolException("Invalid CPZ0 pairing payload")
        val v=try{TvPairingPayload(p[1].toInt(),p[2],p[3].toInt(),p[4].toInt(),p[5],p[6].toLong())}catch(e:Exception){throw ProtocolException("Malformed pairing fields",e)}
        validate(v); if(nowEpochSeconds!=null&&v.expiryEpochSeconds<=nowEpochSeconds)throw ProtocolException("Pairing token expired"); return v
    }
    private fun validate(v:TvPairingPayload){require(v.version==1);LocalAddressPolicy.requireAllowed(v.ipAddress);require(v.tcpPort in 1..65535&&v.udpPort in 1..65535);require(v.pairingToken.length in 16..128&&v.pairingToken.matches(Regex("[A-Za-z0-9_-]+")));require(v.expiryEpochSeconds>0)}
}
