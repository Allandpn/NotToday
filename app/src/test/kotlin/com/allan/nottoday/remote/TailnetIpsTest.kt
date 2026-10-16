package com.allan.nottoday.remote

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetIpsTest {
    private fun ips(vararg s: String) = TailnetIps.filter(s.map { InetAddress.getByName(it) })

    @Test
    fun keepsOnlyTailnetAddresses() {
        assertEquals(
            listOf("100.73.119.106", "fd7a:115c:a1e0:0:0:0:be34:776c"),  // o Java escreve o v6 sem comprimir; o gateway normaliza
            ips("192.168.2.194", "100.73.119.106", "2804:18:f18a:ba7b::1", "fd7a:115c:a1e0::be34:776c", "fe80::1", "127.0.0.1"),
        )
    }

    @Test
    fun cgnatRangeEdges() {
        assertEquals(listOf("100.64.0.1", "100.127.255.254"), ips("100.63.255.255", "100.64.0.1", "100.127.255.254", "100.128.0.1"))
    }

    @Test
    fun dedupesAndCaps() {
        assertEquals(listOf("100.64.0.1"), ips("100.64.0.1", "100.64.0.1"))
        assertEquals(4, ips("100.64.0.1", "100.64.0.2", "100.64.0.3", "100.64.0.4", "100.64.0.5").size)
    }
}
