package com.allan.nottoday.remote

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Endereços deste aparelho na Tailscale. O relatório assinado os leva ao gateway e o executor liga as consultas do Pi-hole ao aparelho. */
object TailnetIps {
    private const val MAX = 4

    /** 100.64.0.0/10 (v4) ou fd7a:115c:a1e0::/48 (v6). */
    fun isTailnet(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> b[0] == 100 && (b[1] and 0xC0) == 0x40
            is Inet6Address -> b.take(6) == listOf(0xFD, 0x7A, 0x11, 0x5C, 0xA1, 0xE0)
            else -> false
        }
    }

    fun filter(all: List<InetAddress>): List<String> =
        all.filter(::isTailnet).mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().take(MAX)

    fun local(): List<String> = try {
        filter(NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() })
    } catch (_: Exception) {
        emptyList()
    }
}
