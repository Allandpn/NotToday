package com.allan.nottoday

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeIpPolicyTest {
    private val pac = File("src/main/assets/${ChromeIpPolicy.ASSET}").readText()

    private fun norm(s: String) = s.replace("\r\n", "\n").trim()

    @Test
    fun pacHasTheEntryPointAndTheDeadEnd() {
        assertTrue(pac.contains("function FindProxyForURL(url, host)"))
        assertTrue(pac.contains("PROXY 127.0.0.1:9"))
        assertTrue("sem DIRECT como reserva do proxy morto", !pac.contains("PROXY 127.0.0.1:9; DIRECT"))
    }

    @Test
    fun localAndTailnetRangesStayReachable() {
        for (r in listOf("a === 10", "a === 127", "a === 192 && b === 168", "a === 100 && b >= 64 && b <= 127", "a === 172 && b >= 16 && b <= 31")) {
            assertTrue("falta $r", pac.contains(r))
        }
    }

    @Test
    fun windowsScriptUsesTheSameFile() {
        assertEquals(norm(pac), norm(File("../scripts/windows/block-ip.pac").readText()))
    }
}
