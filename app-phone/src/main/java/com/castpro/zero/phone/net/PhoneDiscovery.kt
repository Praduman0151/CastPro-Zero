package com.castpro.zero.phone.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.castpro.zero.protocol.Discovery
import com.castpro.zero.protocol.LocalAddressPolicy
import com.castpro.zero.protocol.ProtocolException
import com.castpro.zero.protocol.TvPairingPayload
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** LAN-only discovery receiver. Uses the same CPZ0 payload as the TV's offline QR code. */
class PhoneDiscovery(
    context: Context,
    private val binding: LocalNetworkBinding,
    private val onDevice: (TvPairingPayload) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val multicastLock: WifiManager.MulticastLock =
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("CPZ-phone-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
    private val socket = DatagramSocket(null).apply {
        broadcast = true
        soTimeout = 500
        binding.bindUdp(this, Discovery.DISCOVERY_PORT)
    }
    private val thread = Thread({ loop() }, "CPZ-phone-discovery")

    init { thread.start() }

    fun scan() {
        if (!running.get()) return
        val request = Discovery.request()
        socket.send(DatagramPacket(request, request.size,
            InetAddress.getByName("255.255.255.255"), Discovery.DISCOVERY_PORT))
    }

    private fun loop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (!LocalAddressPolicy.isAllowed(packet.address.hostAddress ?: "")) continue
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val payload = Discovery.decodeResponse(response, System.currentTimeMillis() / 1000)
                onDevice(payload)
            } catch (_: java.net.SocketTimeoutException) {
                // Timeout keeps close() responsive.
            } catch (_: ProtocolException) {
                // Ignore malformed or expired LAN announcements.
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
