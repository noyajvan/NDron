package com.example.drn_kotlin

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Утиліти для роботи з мережевими інтерфейсами.
 * Допомагають знайти Tailscale-адресу (діапазон 100.64.0.0/10) та GCS IP.
 */
object NetworkUtils {

    private const val TAILSCALE_PREFIX = "100."

    /**
     * Повертає IP-адресу Tailscale-інтерфейсу (100.x.x.x), якщо він піднятий.
     * Інакше — першу non-loopback IPv4 адресу.
     * Якщо нічого не знайдено — повертає null.
     */
    fun findTailscaleIp(): String? {
        var fallback: String? = null
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith(TAILSCALE_PREFIX)) return host
                        if (fallback == null) fallback = host
                    }
                }
            }
        } catch (e: Exception) {
            // ігноруємо — повернемо null
        }
        return fallback
    }

    /**
     * Повертає true, якщо знайдено саме Tailscale-адресу (100.x.x.x).
     */
    fun isTailscaleUp(): Boolean {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith(TAILSCALE_PREFIX)) return true
                    }
                }
            }
        } catch (e: Exception) {
            // ігноруємо
        }
        return false
    }

    /**
     * Повертає список усіх non-loopback IPv4 адрес у форматі "iface=ip".
     */
    fun listIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        result.add("${nif.name}=$host")
                    }
                }
            }
        } catch (e: Exception) {
            // ігноруємо
        }
        return result
    }
}
