package com.castpro.zero.phone.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Explicitly pins app sockets to a local Wi-Fi/Ethernet network; hotspot mode never selects rmnet. */
class LocalNetworkBinding(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun selectedNetwork(): Network? {
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    fun bindTcp(socket: Socket): String {
        val network = selectedNetwork()
        if (network != null) {
            network.bindSocket(socket)
            return "wifi-or-ethernet"
        }
        val address = localApAddress() ?: error("No local private hotspot interface found")
        socket.bind(InetSocketAddress(address, 0))
        return address.hostAddress ?: "hotspot"
    }

    fun bindUdp(socket: DatagramSocket): String {
        val network = selectedNetwork()
        if (network != null) {
            network.bindSocket(socket)
            return "wifi-or-ethernet"
        }
        val address = localApAddress() ?: error("No local private hotspot interface found")
        socket.bind(InetSocketAddress(address, 0))
        return address.hostAddress ?: "hotspot"
    }

    private fun localApAddress() = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet", true) && !it.name.startsWith("ccmni", true) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { a ->
            val s = a.hostAddress ?: return@firstOrNull false
            s.startsWith("10.") || s.startsWith("172.") || s.startsWith("192.168.") || s.startsWith("169.254.")
        }
}
