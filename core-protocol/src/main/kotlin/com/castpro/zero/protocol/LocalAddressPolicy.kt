package com.castpro.zero.protocol
object LocalAddressPolicy {
    fun isAllowed(value: String): Boolean {
        if (value.isBlank() || value != value.trim() || value.any { it !in '0'..'9' && it != '.' }) return false
        val p=value.split('.'); if(p.size!=4 || p.any{it.isEmpty() || (it.length>1 && it[0]=='0')}) return false
        val o=p.map{it.toIntOrNull()?:return false}; if(o.any{it !in 0..255}) return false
        val a=o[0]; val b=o[1]
        return a==10 || (a==172 && b in 16..31) || (a==192 && b==168) || (a==169 && b==254)
    }
    fun requireAllowed(value:String):String { if(!isAllowed(value)) throw ProtocolException("Only RFC1918 or IPv4 link-local addresses are allowed"); return value }
}
object UdpBurstPacer {
    data class Plan(val burstSize: Int, val pauseMicros: Long)
    fun plan(fragmentCount: Int): Plan {
        require(fragmentCount >= 0)
        return if (fragmentCount > 30) Plan(12, 350L) else Plan(maxOf(1, fragmentCount), 0L)
    }

    /** Smooth low-burst pacing for production TX so 30-frag IDRs do not saturate Wi-Fi AP queues. */
    fun smoothPlan(fragmentCount: Int): Plan {
        require(fragmentCount >= 0)
        return when {
            fragmentCount <= 6 -> Plan(maxOf(1, fragmentCount), 0L)
            fragmentCount <= 30 -> Plan(6, 120L)
            else -> Plan(8, 160L)
        }
    }
    fun shouldRequestRecovery(droppedFragments: Int) = droppedFragments > 0
}
