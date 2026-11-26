package com.castpro.zero.protocol

/** Strict IPv4-only policy for local discovery and pairing. Hostnames and IPv6 are intentionally rejected. */
object LocalAddressPolicy {
    fun isAllowed(value: String): Boolean {
        if (value.isBlank() || value != value.trim() || value.any { it !in '0'..'9' && it != '.' }) return false
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it[0] == '0') }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]; val b = octets[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    fun requireAllowed(value: String): String {
        if (!isAllowed(value)) throw ProtocolException("Only RFC1918 or IPv4 link-local addresses are allowed")
        return value
    }
}

/** Deliberately conservative pacing hook: large IDRs are split into short UDP bursts. */
object UdpBurstPacer {
    data class Plan(val burstSize: Int, val pauseMicros: Long)
    fun plan(fragmentCount: Int): Plan {
        require(fragmentCount >= 0)
        return if (fragmentCount > 30) Plan(12, 350L) else Plan(maxOf(1, fragmentCount), 0L)
    }

    fun shouldRequestRecovery(droppedFragments: Int): Boolean = droppedFragments > 0
}
