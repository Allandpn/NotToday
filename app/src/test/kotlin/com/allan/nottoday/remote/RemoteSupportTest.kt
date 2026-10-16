package com.allan.nottoday.remote

import com.allan.nottoday.Catalog
import com.allan.nottoday.Change
import com.allan.nottoday.Config
import com.allan.nottoday.ConfigLabels
import com.allan.nottoday.Gate
import com.allan.nottoday.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizeTest {
    @Test
    fun booleansAreStrict() {
        assertEquals("true", Config.normalize("dns_filter", "true"))
        assertEquals("false", Config.normalize("r_no_safe_boot", "false"))
        for (bad in listOf("", "TRUE", "1", "yes", "x", "true ", "false\n")) assertNull("'$bad'", Config.normalize("dns_filter", bad))
    }

    @Test
    fun garbageNeverBecomesFalseBehindTheGate() {
        // applySnapshot grava texto desconhecido como false: o normalize tem que barrar antes.
        val garbage = Config.normalize("new_apps_deny", "talvez")
        assertNull(garbage)
        assertEquals(Change.LOOSEN, Gate.classify("new_apps_deny", "true", "false"))
    }

    @Test
    fun numbersAreRangeChecked() {
        assertEquals("3", Config.normalize("dev_windows_per_day", "3"))
        assertNull(Config.normalize("dev_windows_per_day", "21"))
        assertNull(Config.normalize("dev_windows_per_day", "-1"))
        assertEquals("60", Config.normalize("dev_window_seconds", "60"))
        assertNull(Config.normalize("dev_window_seconds", "9"))
        assertNull(Config.normalize("dev_window_seconds", "601"))
        assertEquals("1440", Config.normalize(Gate.KEY_DELAY, "1440"))
        assertNull(Config.normalize(Gate.KEY_DELAY, "20161"))
        assertEquals("2", Config.normalize("chrome_youtube", "2"))
        assertNull(Config.normalize("chrome_youtube", "3"))
        assertNull(Config.normalize("chrome_youtube", "abc"))
    }

    @Test
    fun textKeysAreValidatedAndNormalized() {
        assertEquals("canario.exemplo.com", Config.normalize("canary", "  Canario.Exemplo.COM "))
        assertEquals("", Config.normalize("canary", ""))
        for (bad in listOf("a b.com", "-x.com", "x.com/", "x.com\nbad", "x..")) {
            assertNull("'$bad'", Config.normalize("canary", bad))
        }
        assertEquals("com.a,com.b", Config.normalize("routed_extra", " com.a , com.b ,"))
        assertNull(Config.normalize("routed_extra", "com.a,com b"))
        assertNull(Config.normalize("protected_pkgs", "com.a;rm"))
        assertEquals("", Config.normalize("store_extra", ""))
    }
}

class PairingParseTest {
    private fun str(url: String = "https://gw.example.ts.net", rp: String = "gw.example.ts.net", token: Any? = "tok", guardians: Any? = defaultGuardians()): String =
        B64.enc(Json.encode(mapOf("v" to 1, "url" to url, "rp" to rp, "token" to token, "guardians" to guardians)).toByteArray())

    private fun defaultGuardians() = mapOf("g_ana" to mapOf("name" to "Ana", "creds" to mapOf("cred1" to "spki1")))

    @Test
    fun acceptsAWellFormedString() {
        val p = Pairing.parse(str())
        assertEquals("https://gw.example.ts.net", p.url)
        assertEquals("tok", p.token)
        assertEquals("gw.example.ts.net", p.trust.rpId)
        assertEquals("spki1", p.trust.guardians["g_ana"]!!.creds["cred1"])
        assertNotNull(Pairing.parse("  " + str("https://gw.example.ts.net/") + "\n"))
    }

    @Test
    fun rejectsInsecureOrMismatchedOrigins() {
        for (bad in listOf(
            str(url = "http://gw.example.ts.net"),
            str(url = "https://evil.example", rp = "gw.example.ts.net"),
            str(url = "https://gw.example.ts.net/caminho"),
            str(url = "https://gw.example.ts.net?x=1"),
            str(guardians = emptyMap<String, Any>()),
            str(guardians = "x"),
            str(token = 5),
            "###", "", B64.enc("{}".toByteArray()), B64.enc("[]".toByteArray()),
            B64.enc(Json.encode(mapOf("v" to 2, "url" to "https://a.b", "rp" to "a.b", "token" to "t", "guardians" to defaultGuardians())).toByteArray()),
        )) {
            assertThrows("'${bad.take(30)}'", CommandException::class.java) { Pairing.parse(bad) }
        }
    }
}

class PendingKindTest {
    @Test
    fun guardianKindRoundTripsThroughSerialization() {
        val p = Pending(Pending.Kind.GUARDIAN, mapOf("id" to "g_bia"), 1, 2, 3, 60_000)
        val back = Pending.parse(p.serialize())!!
        assertEquals(Pending.Kind.GUARDIAN, back.kind)
        assertEquals("g_bia", back.payload["id"])
        assertTrue(Pending.Kind.entries.size == 4)
    }
}

class ConfigLabelsTest {
    private val keys = Catalog.restrictions.map { "r_${it.key}" } + listOf(
        "suspend_blocked", "browsers_need_filter", "dns_filter", "canary", "routed_extra", "browsers_exempt", "new_apps_deny",
        "dev_windows_per_day", "dev_window_seconds", "dev_reboot", "postpone_updates", "store_extra", "chrome_doh_off",
        "chrome_safesearch", "chrome_youtube", "chrome_incognito_block", "chrome_block_ip", "protected_pkgs", Gate.KEY_DELAY,
    )

    @Test
    fun everyKnownKeyHasAHumanLabelAndAGroupInTheList() {
        for (k in keys) {
            assertTrue("$k sem rótulo", ConfigLabels.label(k) != k)
            assertTrue("$k com grupo fora da lista", ConfigLabels.group(k) in ConfigLabels.GROUPS)
        }
        assertEquals("chave_nova", ConfigLabels.label("chave_nova")) // desconhecida: mostra a própria chave
    }

    @Test
    fun describeCoversAllKeysAndKeepsHintsShort() {
        val d = ConfigLabels.describe(keys)
        assertEquals(keys.toSet(), d["labels"]!!.keys)
        assertEquals(keys.toSet(), d["groups"]!!.keys)
        assertTrue(d["hints"]!!.values.all { it.length <= 200 })
        assertTrue(d["hints"]!!.isNotEmpty())
    }
}

class UnblockTextTest {
    private fun item(d: String, st: String, ttl: Long? = null) = RequestItem(d, st, ttl)

    @Test
    fun answerMentionsAllowedAndDeniedAndNothingForCancelled() {
        val r = UnblockRequest(1, "done", listOf(item("a.com", "allowed"), item("b.com", "allowed", 3600), item("c.com", "denied")))
        val msg = UnblockText.answer(r)!!
        assertTrue(msg, msg.startsWith("Liberado: a.com, b.com. Negado: c.com"))
        assertEquals(null, UnblockText.answer(UnblockRequest(2, "done", listOf(item("a.com", "cancelled")))))
        assertEquals("Negado: x.com", UnblockText.answer(UnblockRequest(3, "done", listOf(item("x.com", "denied")))))
    }

    @Test
    fun labelsAndDurations() {
        assertEquals("liberado por 1 h", UnblockText.stateLabel(item("a", "allowed", 3600)))
        assertEquals("liberado", UnblockText.stateLabel(item("a", "allowed")))
        assertEquals("1 d", UnblockText.ttl(86400))
        assertEquals("há 5 min", UnblockText.ago(1000, 700))
    }
}
