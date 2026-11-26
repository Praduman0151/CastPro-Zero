package com.castpro.zero.phone.net
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.*
class LocalNetworkBinding(context:Context) {
 private val connectivity=context.getSystemService(ConnectivityManager::class.java)
 fun selectedNetwork():Network?=connectivity.allNetworks.firstOrNull{n->val c=connectivity.getNetworkCapabilities(n)?:return@firstOrNull false;c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}
 fun bindTcp(socket:Socket):String {val n=selectedNetwork();if(n!=null){n.bindSocket(socket);return "wifi-or-ethernet"};val a=localApAddress()?:error("No private AP interface");socket.bind(InetSocketAddress(a,0));return a.hostAddress?: "hotspot"}
 fun bindUdp(socket:DatagramSocket):String {val n=selectedNetwork();if(n!=null){n.bindSocket(socket);return "wifi-or-ethernet"};val a=localApAddress()?:error("No private AP interface");socket.bind(InetSocketAddress(a,0));return a.hostAddress?: "hotspot"}
 private fun localApAddress()=NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback&&!it.name.startsWith("rmnet",true)&&!it.name.startsWith("ccmni",true)}.flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().firstOrNull{a->val s=a.hostAddress?:return@firstOrNull false;s.startsWith("10.")||s.startsWith("172.")||s.startsWith("192.168.")||s.startsWith("169.254.")}
}
