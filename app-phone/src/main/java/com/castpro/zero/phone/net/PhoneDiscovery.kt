package com.castpro.zero.phone.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.castpro.zero.protocol.Discovery
import com.castpro.zero.protocol.LanDiscoveryBeacon
import com.castpro.zero.protocol.ProtocolException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN-only discovery receiver. Receives endpoint identity beacons (IP + ports) and validates
 * that the packet source address matches the advertised IPv4 address. Never accepts or extracts
 * pairing tokens from unauthenticated broadcasts.
 */
class PhoneDiscovery(
    context: Context,
    private val binding: LocalNetworkBinding,
    private val onDevice: (LanDiscoveryBeacon) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val multicastLock: WifiManager.MulticastLock =
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("CPZ-phone-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        soTimeout = 500
        binding.bindUdp(this, Discovery.DISCOVERY_PORT, bindToInterfaceAddress = false)
    }
    private val thread = Thread({ loop() }, "CPZ-phone-discovery")

    init { thread.start() }

    fun scan() {
        if (!running.get()) return
        val request = Discovery.request()
        socket.send(
            DatagramPacket(
                request, request.size,
                InetAddress.getByName("255.255.255.255"), Discovery.DISCOVERY_PORT
            )
        )
    }

    private fun loop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val sourceIp = packet.address.hostAddress ?: continue
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val beacon = Discovery.decodeBeacon(response)
                if (!Discovery.isAuthenticSource(sourceIp, beacon)) continue
                onDevice(beacon)
            } catch (_: java.net.SocketTimeoutException) {
                // Timeout keeps close() responsive.
            } catch (_: ProtocolException) {
                // Ignore malformed, unverified or non-beacon announcements.
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Discovery receive failed", e)
            }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        socket.close()
        try { thread.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { if (multicastLock.isHeld) multicastLock.release() } catch (_: Exception) {}
    }

    private companion object { const val TAG = "CPZ-Phone-Discovery" }
}
