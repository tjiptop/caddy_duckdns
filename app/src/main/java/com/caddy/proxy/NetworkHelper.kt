package com.caddy.proxy

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelper {
    /**
     * Attempts to find the current active IPv4 address.
     * Prefers hotspot interface (e.g. ap0, swlan, rndis, wlan) or defaults to 192.168.43.1
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.43.1"
            val ipList = mutableListOf<Pair<String, String>>()

            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        ipList.add(Pair(iface.name.lowercase(), host))
                    }
                }
            }

            // Look for typical hotspot interface names
            for ((name, ip) in ipList) {
                if (name.contains("ap") || name.contains("swlan") || name.contains("tether") || name.contains("rndis")) {
                    return ip
                }
            }

            // Look for WiFi interface
            for ((name, ip) in ipList) {
                if (name.contains("wlan")) {
                    return ip
                }
            }

            // Any remaining IPv4
            if (ipList.isNotEmpty()) {
                return ipList.first().second
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Standard Android Tethering Hotspot Gateway IP
        return "192.168.43.1"
    }
}
