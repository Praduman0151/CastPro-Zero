package com.castpro.zero.phone.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.castpro.zero.protocol.LocalAddressPolicy
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Binds transport sockets to Wi-Fi/Ethernet, or to a private hotspot interface without cellular fallback. */
class LocalNetworkBinding(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun selectedNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
        val localIpv4 = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .any { link -> link.address is Inet4Address &&
                LocalAddressPolicy.isAllowed(link.address.hostAddress ?: "") }
        localIpv4 && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun bindTcp(socket: Socket): String {
        val network = selectedNetwork()
        if (network != null) {
            network.bindSocket(socket)
            return "wifi-or-ethernet"
        }
        val address = privateApAddress()
        socket.bind(InetSocketAddress(address, 0))
        return address.hostAddress ?: "hotspot"
    }

    fun bindUdp(socket: DatagramSocket, port: Int = 0): String {
        val network = selectedNetwork()
        if (network != null) {
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(port))
            return "wifi-or-ethernet"
        }
        val address = privateApAddress()
        socket.bind(InetSocketAddress(address, port))
        return address.hostAddress ?: "hotspot"
    }

    fun validateTcp(socket: Socket) {
        LocalAddressPolicy.requireAllowed(socket.localAddress.hostAddress ?: "")
    }

    private fun privateApAddress(): Inet4Address {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback &&
                !it.name.startsWith("rmnet", true) &&
                !it.name.startsWith("ccmni", true) &&
                !it.name.startsWith("pdp", true) &&
                !it.name.startsWith("wwan", true) }
            .sortedBy { ni ->
                if (ni.name.contains("wlan", true) || ni.name.contains("ap", true)) 0 else 1
            }
        return interfaces.asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { LocalAddressPolicy.isAllowed(it.hostAddress ?: "") }
            ?: error("No private Wi-Fi hotspot IPv4 address; cellular interfaces are excluded")
    }
}
