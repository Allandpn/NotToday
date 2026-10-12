package com.allan.nottoday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LogicTest {
    // --- Gate --------------------------------------------------------------------

    @Test
    fun booleansLoosenWhenTurnedOff() {
        assertEquals(Change.LOOSEN, Gate.classify("r_no_uninstall_apps", "true", "false"))
        assertEquals(Change.TIGHTEN, Gate.classify("r_no_uninstall_apps", "false", "true"))
        assertEquals(Change.LOOSEN, Gate.classify("dns_filter", "true", "false"))
        assertEquals(Change.LOOSEN, Gate.classify("new_apps_deny", "true", "false"))
        assertEquals(Change.LOOSEN, Gate.classify("dev_reboot", "true", "false"))
        assertEquals(Change.NEUTRAL, Gate.classify("dns_filter", "true", "true"))
        assertEquals(Change.NEUTRAL, Gate.classify("postpone_updates", "false", "true"))
    }

    @Test
    fun canaryFirstSetTightensButChangeLoosens() {
        assertEquals(Change.TIGHTEN, Gate.classify("canary", "", "x.com"))
        assertEquals(Change.TIGHTEN, Gate.classify("canary", null, "x.com"))
        assertEquals(Change.LOOSEN, Gate.classify("canary", "x.com", "y.com"))
        assertEquals(Change.LOOSEN, Gate.classify("canary", "x.com", ""))
    }

    @Test
    fun routedAppsRemovalLoosens() {
        assertEquals(Change.TIGHTEN, Gate.classify("routed_extra", "a", "a,b"))
        assertEquals(Change.LOOSEN, Gate.classify("routed_extra", "a,b", "a"))
        assertEquals(Change.LOOSEN, Gate.classify("protected_pkgs", "a", ""))
    }

    @Test
    fun chromeOptionsAndExtraStoresFollowTheSameRules() {
        // YouTube restrito: 2 estrito, 0 desligado; descer afrouxa, subir endurece.
        assertEquals(Change.LOOSEN, Gate.classify("chrome_youtube", "2", "1"))
        assertEquals(Change.LOOSEN, Gate.classify("chrome_youtube", "1", "0"))
        assertEquals(Change.TIGHTEN, Gate.classify("chrome_youtube", "0", "2"))
        assertEquals(Change.LOOSEN, Gate.classify("chrome_doh_off", "true", "false"))
        assertEquals(Change.LOOSEN, Gate.classify("chrome_safesearch", "true", "false"))
        assertEquals(Change.TIGHTEN, Gate.classify("chrome_incognito_block", "false", "true"))
        // Tirar uma loja da lista extra afrouxa; acrescentar endurece.
        assertEquals(Change.LOOSEN, Gate.classify("store_extra", "a,b", "a"))
        assertEquals(Change.TIGHTEN, Gate.classify("store_extra", "a", "a,b"))
    }

    @Test
    fun exemptingABrowserLoosens() {
        assertEquals(Change.LOOSEN, Gate.classify("browsers_exempt", "", "com.android.chrome"))
        assertEquals(Change.TIGHTEN, Gate.classify("browsers_exempt", "com.android.chrome", ""))
    }

    @Test
    fun devWindowLimitsLoosenWhenRaised() {
        assertEquals(Change.LOOSEN, Gate.classify("dev_windows_per_day", "3", "5"))
        assertEquals(Change.TIGHTEN, Gate.classify("dev_windows_per_day", "3", "1"))
        assertEquals(Change.LOOSEN, Gate.classify("dev_window_seconds", "60", "120"))
        assertEquals(Change.TIGHTEN, Gate.classify("dev_window_seconds", "60", "30"))
    }

    @Test
    fun delayLoosensWhenLowered() {
        assertEquals(Change.LOOSEN, Gate.classify(Gate.KEY_DELAY, "1440", "60"))
        assertEquals(Change.TIGHTEN, Gate.classify(Gate.KEY_DELAY, "60", "1440"))
    }

    @Test
    fun splitSeparatesImmediateFromDelayed() {
        val old = mapOf("r_a" to "true", "r_b" to "false", "canary" to "x.com", "routed_extra" to "")
        val new = mapOf("r_a" to "false", "r_b" to "true", "canary" to "x.com", "routed_extra" to "app.x")
        val s = Gate.split(old, new)
        assertEquals(mapOf("r_a" to "false"), s.delayed)
        assertEquals(mapOf("r_b" to "true", "routed_extra" to "app.x"), s.immediate)
    }

    // --- Pending -----------------------------------------------------------------

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun pendingWaitsThenIsReadyThenExpires() {
        val p = Pending(Pending.Kind.SETTINGS, mapOf("r_a" to "false"), requestedElapsed = 1_000, requestedWall = 0, boot = 7, delayMs = day)
        assertTrue(p.status(Clock(elapsed = 2_000, wall = 0, boot = 7, wallTrusted = false)) is Pending.Status.Waiting)
        assertTrue(p.status(Clock(elapsed = 1_000 + day, wall = 0, boot = 7, wallTrusted = false)) is Pending.Status.Ready)
        assertEquals(
            Pending.Status.Expired,
            p.status(Clock(elapsed = 1_000 + 2 * day + 1, wall = 0, boot = 7, wallTrusted = false)),
        )
    }

    @Test
    fun pendingRestartsAfterRebootWithoutTrustedClock() {
        val p = Pending(Pending.Kind.RELEASE, emptyMap(), 0, 0, boot = 1, delayMs = day)
        val rebooted = Clock(elapsed = 10, wall = 50 * day, boot = 2, wallTrusted = false)
        assertEquals(Pending.Status.Waiting(day), p.status(rebooted))
        val trusted = Clock(elapsed = 10, wall = day + day / 2, boot = 2, wallTrusted = true)
        assertTrue(p.status(trusted) is Pending.Status.Ready)
    }

    @Test
    fun pendingSerializationRoundTrips() {
        val p = Pending(Pending.Kind.SETTINGS, linkedMapOf("k" to "v", "x" to "a b"), 5, 6, 7, 8)
        val q = Pending.parse(p.serialize())
        assertNotNull(q)
        assertEquals(p.payload, q!!.payload)
        assertEquals(Pending.Kind.SETTINGS, q.kind)
        assertEquals(8L, q.delayMs)
        assertNull(Pending.parse("lixo"))
    }

    // --- DnsProbe ----------------------------------------------------------------

    private fun response(rcode: Int, qname: String = "canario.exemplo.com", answers: List<ByteArray> = emptyList(), type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v shr 8); out.write(v and 0xFF)
        }
        u16(0x1234)
        u16(0x8180 or rcode)
        u16(1)
        u16(answers.size)
        u16(0); u16(0)
        qname.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        u16(1); u16(1)
        for (rdata in answers) {
            u16(0xC00C) // ponteiro de nome
            u16(type); u16(1)
            u16(0); u16(60) // ttl
            u16(rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    @Test
    fun nxdomainIsBlocked() {
        assertEquals(DnsProbe.Verdict.BLOCKED, DnsProbe.verdict(response(3)))
    }

    @Test
    fun nullAnswerIsBlocked() {
        assertEquals(DnsProbe.Verdict.BLOCKED, DnsProbe.verdict(response(0, answers = listOf(byteArrayOf(0, 0, 0, 0)))))
        assertEquals(DnsProbe.Verdict.BLOCKED, DnsProbe.verdict(response(0, answers = listOf(ByteArray(16)), type = 28)))
    }

    @Test
    fun emptyNoerrorIsBlocked() {
        assertEquals(DnsProbe.Verdict.BLOCKED, DnsProbe.verdict(response(0)))
    }

    @Test
    fun realAddressMeansNotBlocking() {
        assertEquals(
            DnsProbe.Verdict.ANSWERED,
            DnsProbe.verdict(response(0, answers = listOf(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))),
        )
        // basta um endereço real entre vários
        assertEquals(
            DnsProbe.Verdict.ANSWERED,
            DnsProbe.verdict(response(0, answers = listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(1, 2, 3, 4)))),
        )
    }

    @Test
    fun serverErrorsAndGarbageFail() {
        assertEquals(DnsProbe.Verdict.FAILED, DnsProbe.verdict(response(2))) // SERVFAIL
        assertEquals(DnsProbe.Verdict.FAILED, DnsProbe.verdict(response(5))) // REFUSED
        assertEquals(DnsProbe.Verdict.FAILED, DnsProbe.verdict(ByteArray(5)))
        val truncated = response(0, answers = listOf(byteArrayOf(1, 2, 3, 4))).copyOf(30)
        assertEquals(DnsProbe.Verdict.FAILED, DnsProbe.verdict(truncated))
    }
}
