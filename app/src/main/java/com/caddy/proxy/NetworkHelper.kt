package com.caddy.proxy

import java.net.Inet4Address
import java.net.NetworkInterface

data class IpInfo(
    val ip: String,
    val ifaceName: String,
    val type: String
) {
    fun getDisplayText(): String {
        if (ip.isBlank()) return "(Auto-detect IP)"
        return "$ip ($type - $ifaceName)"
    }

    override fun toString(): String = getDisplayText()
}

object NetworkHelper {
    /**
     * Returns all active local IPv4 addresses found on network interfaces.
     */
    fun getAllLocalIPv4(): List<IpInfo> {
        val result = mutableListOf<IpInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                val name = iface.name.lowercase()

                val type = when {
                    name.contains("ap") || name.contains("swlan") || name.contains("softap") -> "Hotspot AP"
                    name.contains("wlan") -> "Wi-Fi"
                    name.contains("rndis") || name.contains("usb") -> "USB Tethering"
                    name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") -> "Cellular"
                    name.contains("eth") -> "Ethernet"
                    name.contains("tun") || name.contains("tap") -> "VPN"
                    else -> "Network"
                }

                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        result.add(IpInfo(host, iface.name, type))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If no interfaces found, fallback to hotspot default
        if (result.isEmpty()) {
            result.add(IpInfo("192.168.43.1", "ap0", "Hotspot Default"))
        }

        return result
    }

    /**
     * Attempts to find the current active IPv4 address.
     * Prefers hotspot interface (e.g. ap0, swlan, rndis, wlan) or defaults to 192.168.43.1
     */
    fun getLocalIpAddress(): String {
        val ips = getAllLocalIPv4()
        // 1. Hotspot AP
        for (item in ips) {
            if (item.type.contains("Hotspot")) return item.ip
        }
        // 2. Wi-Fi
        for (item in ips) {
            if (item.type.contains("Wi-Fi")) return item.ip
        }
        // 3. Any other
        if (ips.isNotEmpty()) {
            return ips.first().ip
        }
        return "192.168.43.1"
    }
}
