package com.castpro.zero.phone.net

import android.util.Log
import com.castpro.zero.protocol.Discovery
import com.castpro.zero.protocol.ProtocolException
import com.castpro.zero.protocol.TvPairingPayload
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Finds idle TV receivers by local broadcast only; no internet service. */
class PhoneDiscovery(
    private val binding: LocalNetworkBinding,
    private val onDevice: (TvPairingPayload) -> Unit
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val socket = DatagramSocket(null).apply {
        binding.bindUdp(this)
        broadcast = true
        soTimeout = 250
    }
    private val thread = Thread({ loop() }, "CPZ-phone-discovery")

    init { thread.start() }

    fun scan() {
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
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val payload = Discovery.decodeResponse(response, System.currentTimeMillis() / 1000)
                onDevice(payload)
            } catch (_: java.net.SocketTimeoutException) {
                // Allows stop() to be observed.
            } catch (_: ProtocolException) {
                // Ignore malformed or expired responses from other devices.
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Discovery receive failed", e)
            }
        }
    }

    override fun close() {
        running.set(false)
        socket.close()
        try { thread.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private companion object { const val TAG = "CPZ-Phone-Discovery" }
}
