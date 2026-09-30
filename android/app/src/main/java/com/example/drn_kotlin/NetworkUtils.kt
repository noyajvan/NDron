package com.example.drn_kotlin

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Утиліти для роботи з мережевими інтерфейсами.
 * Точно відрізняють справжній Tailscale VPN (tun/tailscale, 100.x.x.x) від 4G/5G CGNAT (rmnet/ccmni, 100.x.x.x).
 */
object NetworkUtils {

    private const val TAILSCALE_PREFIX = "100."

    /**
     * Повертає IP-адресу справжнього Tailscale VPN інтерфейсу (100.x.x.x на tun/tailscale).
     */
    fun findTailscaleIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

            // Пріоритет 1: Перевіряємо VPN інтерфейси (tun*, tailscale*, vpn*) з IP 100.x.x.x
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val isVpn = name.contains("tun") || name.contains("tailscale") || name.contains("vpn")
                if (isVpn) {
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith(TAILSCALE_PREFIX)) return host
                        }
                    }
                }
            }

            // Пріоритет 2: Будь-який 100.x.x.x IP, який НЕ належить мобільним мобільним інтерфейсам (rmnet, ccmni, pdp)
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val isCellular = name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")
                if (!isCellular) {
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith(TAILSCALE_PREFIX)) return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Повертає IP-адресу Hotspot (роздача інтернету) або локального Wi-Fi (192.168.x.x, 172.x.x.x, 10.x.x.x), окрім Tailscale.
     */
    fun findLocalOrHotspotIp(): String? {
        try {
            val tsIp = findTailscaleIp()
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // Ігноруємо мобільний 4G інтерфейс та Tailscale vpn
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host != tsIp && !host.startsWith(TAILSCALE_PREFIX)) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Повертає true, якщо знайдено саме активний Tailscale VPN.
     */
    fun isTailscaleUp(): Boolean {
        return findTailscaleIp() != null
    }

    /**
     * Повертає список усіх non-loopback IPv4 адрес у форматі "iface=ip".
     */
    fun listIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return result
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        result.add("${nif.name}=$host")
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
