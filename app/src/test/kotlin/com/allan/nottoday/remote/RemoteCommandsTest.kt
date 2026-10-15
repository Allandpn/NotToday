package com.allan.nottoday.remote

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.allan.nottoday.AdminReceiver
import com.allan.nottoday.Approved
import com.allan.nottoday.Config
import com.allan.nottoday.Pending
import com.allan.nottoday.PendingStore
import com.allan.nottoday.PinStore
import com.allan.nottoday.PolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** O executor de comandos do aparelho, de ponta a ponta com SharedPreferences reais (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class RemoteCommandsTest {
    private val rp = "gw.example.ts.net"
    private val origin = "https://$rp"
    private val dev = "aaaabbbbccccddddeeee"
    private lateinit var ctx: Context
    private lateinit var store: RemoteStore
    private lateinit var consumer: Consumer
    private lateinit var runner: RemoteCommands
    private lateinit var ana: FakePasskey
    private lateinit var bia: FakePasskey
    private val deviceKey = newEcKey()
    private var ctr = 0L

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ana = FakePasskey(rp, origin)
        bia = FakePasskey(rp, origin)
        val trust = Trust(rp, origin, linkedMapOf(
            "g_ana" to Guardian("Ana", linkedMapOf(ana.credIdB64 to ana.spkiB64)),
            "g_bia" to Guardian("Bia", linkedMapOf(bia.credIdB64 to bia.spkiB64)),
        ))
        consumer = Consumer(dev, trust)
        store = RemoteStore(ctx)
        store.saveConsumer(consumer)
        store.registered = true
        runner = RemoteCommands(ctx, store, consumer) { deviceKey.private }
    }

    private fun becomeOwner() {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        shadowOf(dpm).setDeviceOwner(ComponentName(ctx, AdminReceiver::class.java))
        assertTrue(PolicyManager(ctx).isDeviceOwner())
    }

    private fun send(
        type: String, args: Map<String, Any?> = emptyMap(), by: String = "g_ana", key: FakePasskey = ana,
        extra: List<Pair<String, FakePasskey>> = emptyList(), ctrOverride: Long? = null,
    ): RemoteCommands.Result {
        val n = ctrOverride ?: ++ctr
        val p = payloadOf(dev, by, n, type, args)
        val sigs = listOf(key.sign(by, p)) + extra.map { (g, k) -> k.sign(g, p) }
        return runner.handle(envelopeOf(p, *sigs.toTypedArray()))
    }

    private fun sleepHours(h: Long) = ShadowSystemClock.advanceBy(Duration.ofHours(h))

    @Test
    fun approveAndRevokeApp() {
        assertTrue(send("approve_app", mapOf("pkg" to "com.exemplo.jogo")).ok)
        assertTrue("com.exemplo.jogo" in Approved(ctx).all())
        assertTrue(send("revoke_app", mapOf("pkg" to "com.exemplo.jogo")).ok)
        assertFalse("com.exemplo.jogo" in Approved(ctx).all())
        assertFalse(send("approve_app", mapOf("pkg" to "com exemplo")).ok)
        assertFalse(send("approve_app", emptyMap()).ok)
    }

    @Test
    fun replayForgeryAndCounterPersistence() {
        assertTrue(send("approve_app", mapOf("pkg" to "com.a"), ctrOverride = 5).ok)
        val replay = send("approve_app", mapOf("pkg" to "com.b"), ctrOverride = 5)
        assertFalse(replay.ok)
        assertTrue(replay.msg.contains("recusado"))
        assertFalse("com.b" in Approved(ctx).all())
        val stranger = FakePasskey(rp, origin)
        assertFalse(send("approve_app", mapOf("pkg" to "com.c"), key = stranger, ctrOverride = 6).ok)
        assertFalse(send("approve_app", mapOf("pkg" to "com.d"), key = ana.let { FakePasskey(rp, "https://evil.example") }, ctrOverride = 7).ok)
        // o contador consumido está em disco, não só na memória
        assertEquals(5L, store.consumer()!!.lastCtr["g_ana"])
    }

    @Test
    fun setConfigAppliesEverythingWhenNotOwner() {
        val r = send("set_config", mapOf("patch" to mapOf("dns_filter" to false, "chrome_youtube" to 2L)))
        assertTrue(r.msg, r.ok)
        assertFalse(Config(ctx).dnsFilterEnabled)
        assertEquals(2, Config(ctx).chromeYoutube)
        assertNull(PendingStore(ctx).get(Pending.Kind.SETTINGS))
    }

    @Test
    fun loosenWaitsForTheDelayAndTightenIsImmediateWhenOwner() {
        becomeOwner()
        val r = send("set_config", mapOf("patch" to mapOf("dns_filter" to false, "canary" to "Canario.Exemplo.com")))
        assertTrue(r.msg, r.ok)
        assertEquals("1 aplicada(s), 1 em espera", r.msg)
        assertEquals("canario.exemplo.com", Config(ctx).canary) // 1º canário endurece: vale na hora
        assertTrue(Config(ctx).dnsFilterEnabled) // desligar o filtro afrouxa: espera
        val p = PendingStore(ctx).get(Pending.Kind.SETTINGS)!!
        assertEquals("false", p.payload["dns_filter"])

        val early = send("confirm", mapOf("kind" to "SETTINGS"))
        assertFalse(early.ok)
        assertTrue(early.msg, early.msg.contains("ainda em espera"))
        assertTrue(Config(ctx).dnsFilterEnabled)

        sleepHours(25)
        val done = send("confirm", mapOf("kind" to "SETTINGS"))
        assertTrue(done.msg, done.ok)
        assertFalse(Config(ctx).dnsFilterEnabled)
        assertNull(PendingStore(ctx).get(Pending.Kind.SETTINGS))
    }

    @Test
    fun readyRequestExpiresAfterItsValidity() {
        becomeOwner()
        send("set_config", mapOf("patch" to mapOf("dns_filter" to false)))
        sleepHours(24 + 24 + 1) // atraso de 24 h + validade de 24 h
        val r = send("confirm", mapOf("kind" to "SETTINGS"))
        assertFalse(r.ok)
        assertTrue(r.msg, r.msg.contains("expirou"))
        assertTrue(Config(ctx).dnsFilterEnabled)
    }

    @Test
    fun loweringTheDelayIsLoosening() {
        becomeOwner()
        val r = send("set_config", mapOf("patch" to mapOf("unlock_delay_min" to "0")))
        assertTrue(r.msg, r.ok)
        assertEquals(24L * 60, Config(ctx).unlockDelayMin)
        assertEquals("0", PendingStore(ctx).get(Pending.Kind.SETTINGS)!!.payload["unlock_delay_min"])
        // aumentar vale na hora
        assertTrue(send("set_config", mapOf("patch" to mapOf("unlock_delay_min" to "2880"))).ok)
        assertEquals(2880L, Config(ctx).unlockDelayMin)
    }

    @Test
    fun invalidConfigIsRejectedAndNeverTurnsIntoFalse() {
        for (patch in listOf(
            mapOf("new_apps_deny" to "talvez"), mapOf("new_apps_deny" to 1.5), mapOf("chave_inexistente" to "true"),
            mapOf("dev_windows_per_day" to 99L), mapOf("canary" to "x y"), emptyMap(),
        )) {
            val r = send("set_config", mapOf("patch" to patch))
            assertFalse("$patch", r.ok)
        }
        assertTrue(Config(ctx).newAppsDeny)
        assertFalse(send("set_config", emptyMap()).ok)
    }

    @Test
    fun devPolicyIsAnAliasOfSetConfig() {
        assertTrue(send("set_dev_policy", mapOf("windows" to 2L, "seconds" to 30L, "reboot" to false)).ok)
        assertEquals(2, Config(ctx).devWindowsPerDay)
        assertEquals(30, Config(ctx).devWindowSeconds)
        assertFalse(Config(ctx).devReboot)
    }

    @Test
    fun pinsArriveSealedAndAreChecked() {
        val seal = { type: String, pin: String, d: String -> sealPin(deviceKey.public, pin, PinBox.aad(type, d)) }
        assertTrue(send("set_pin", mapOf("box" to seal("set_pin", "246810", dev))).ok)
        val pins = PinStore(ctx)
        assertTrue(pins.isSet)
        assertTrue(pins.verify("246810"))
        assertFalse(pins.verify("111111"))

        assertFalse(send("set_dev_pin", mapOf("box" to seal("set_dev_pin", "246810", dev))).ok) // igual ao principal
        assertTrue(send("set_dev_pin", mapOf("box" to seal("set_dev_pin", "135790", dev))).ok)
        assertTrue(PinStore(ctx, PolicyManager.DEV_PIN).verify("135790"))
        assertFalse(send("set_pin", mapOf("box" to seal("set_pin", "135790", dev))).ok) // igual ao de desenvolvedor

        assertFalse(send("set_pin", mapOf("box" to seal("set_dev_pin", "999888", dev))).ok) // aad de outro comando
        assertFalse(send("set_pin", mapOf("box" to seal("set_pin", "999888", "outro"))).ok) // aad de outro aparelho
        assertFalse(send("set_pin", mapOf("box" to seal("set_pin", "12ab56", dev))).ok) // não numérico
        assertFalse(send("set_pin", mapOf("box" to seal("set_pin", "12345", dev))).ok) // curto
        assertFalse(send("set_pin", mapOf("box" to mapOf("epk" to "x"))).ok)
        assertFalse(send("set_pin", emptyMap()).ok)
        assertTrue(PinStore(ctx).verify("246810")) // nada disso mexeu no PIN
    }

    @Test
    fun pinFailuresDoNotLockTheLocalPin() {
        val seal = { pin: String -> sealPin(deviceKey.public, pin, PinBox.aad("set_dev_pin", dev)) }
        send("set_pin", mapOf("box" to sealPin(deviceKey.public, "246810", PinBox.aad("set_pin", dev))))
        repeat(8) { send("set_dev_pin", mapOf("box" to seal("246810"))) } // rejeitado: igual ao principal
        assertEquals(0L, PinStore(ctx).lockedForMs()) // matches() não conta tentativas
    }

    @Test
    fun removingAGuardianNeedsTwoSignatures() {
        val one = send("remove_guardian", mapOf("id" to "g_bia"))
        assertFalse(one.ok)
        assertTrue(one.msg, one.msg.contains("faltam assinaturas"))
        assertEquals(2, consumer.trust.guardians.size)
        val both = send("remove_guardian", mapOf("id" to "g_bia"), extra = listOf("g_bia" to bia))
        assertTrue(both.msg, both.ok) // sem Device Owner não há atraso
        assertEquals(setOf("g_ana"), consumer.trust.guardians.keys)
        assertEquals(setOf("g_ana"), store.consumer()!!.trust.guardians.keys)
        // o último guardião nunca sai
        assertFalse(send("remove_guardian", mapOf("id" to "g_ana")).ok)
    }

    @Test
    fun removingAGuardianWaitsForTheDelayWhenOwner() {
        becomeOwner()
        val r = send("remove_guardian", mapOf("id" to "g_bia"), extra = listOf("g_bia" to bia))
        assertTrue(r.msg, r.ok)
        assertEquals(2, consumer.trust.guardians.size)
        assertEquals("g_bia", PendingStore(ctx).get(Pending.Kind.GUARDIAN)!!.payload["id"])
        assertFalse(send("confirm", mapOf("kind" to "GUARDIAN")).ok)
        // o outro guardião pode cancelar durante a espera
        assertTrue(send("cancel", mapOf("kind" to "GUARDIAN"), by = "g_bia", key = bia).ok)
        assertNull(PendingStore(ctx).get(Pending.Kind.GUARDIAN))
        send("remove_guardian", mapOf("id" to "g_bia"), extra = listOf("g_bia" to bia))
        sleepHours(25)
        assertTrue(send("confirm", mapOf("kind" to "GUARDIAN")).ok)
        assertEquals(setOf("g_ana"), consumer.trust.guardians.keys)
    }

    @Test
    fun trustGrowsByAddGuardianAndAddCredential() {
        val cris = FakePasskey(rp, origin)
        assertTrue(send("add_guardian", mapOf("id" to "g_cris", "name" to "Cris", "cred" to cris.credIdB64, "spki" to cris.spkiB64, "role" to "admin")).ok)
        val cris2 = FakePasskey(rp, origin)
        assertTrue(send("add_credential", mapOf("id" to "g_cris", "cred" to cris2.credIdB64, "spki" to cris2.spkiB64)).ok)
        assertEquals(2, store.consumer()!!.trust.guardians["g_cris"]!!.creds.size)
        // o novo guardião já consegue assinar
        assertTrue(send("approve_app", mapOf("pkg" to "com.x"), by = "g_cris", key = cris2, ctrOverride = 1).ok)
        assertFalse(send("add_guardian", mapOf("id" to "g_cris", "name" to "outra", "cred" to "c", "spki" to "s", "role" to "admin")).ok) // já existe
    }

    @Test
    fun domainGuardianCannotTouchTheDeviceAndLeavesAtOnce() {
        becomeOwner()
        val dora = FakePasskey(rp, origin)
        assertFalse(send("add_guardian", mapOf("id" to "g_dora", "name" to "Dora", "cred" to dora.credIdB64, "spki" to dora.spkiB64)).ok) // sem papel
        assertTrue(send("add_guardian", mapOf("id" to "g_dora", "name" to "Dora", "cred" to dora.credIdB64, "spki" to dora.spkiB64, "role" to "domains")).ok)
        assertEquals("domains", store.consumer()!!.trust.guardians["g_dora"]!!.role)
        var n = 0L
        for ((t, a) in listOf("approve_app" to mapOf("pkg" to "com.x"), "set_config" to mapOf("patch" to emptyMap<String, Any?>()),
            "activate" to emptyMap(), "cancel" to mapOf("kind" to "SETTINGS"), "remove_guardian" to mapOf("id" to "g_bia"))) {
            val r = send(t, a, by = "g_dora", key = dora, ctrOverride = ++n)
            assertFalse(t, r.ok)
            assertTrue(r.msg, r.msg.contains("guardião de domínios"))
        }
        val dora2 = FakePasskey(rp, origin)
        assertTrue(send("add_credential", mapOf("id" to "g_dora", "cred" to dora2.credIdB64, "spki" to dora2.spkiB64), by = "g_dora", key = dora, ctrOverride = ++n).ok)
        assertFalse(send("add_credential", mapOf("id" to "g_ana", "cred" to dora2.credIdB64, "spki" to dora2.spkiB64), by = "g_dora", key = dora, ctrOverride = ++n).ok)
        // a assinatura de um guardião de domínios não conta como o segundo administrador
        assertFalse(send("remove_guardian", mapOf("id" to "g_bia"), extra = listOf("g_dora" to dora)).ok)
        // remover um guardião de domínios vale na hora, mesmo com Device Owner
        val r = send("remove_guardian", mapOf("id" to "g_dora"), extra = listOf("g_bia" to bia))
        assertTrue(r.msg, r.ok)
        assertFalse("g_dora" in store.consumer()!!.trust.guardians)
        assertNull(PendingStore(ctx).get(Pending.Kind.GUARDIAN))
    }

    @Test
    fun activateMaintenanceAndCancel() {
        assertFalse(store.activated)
        assertTrue(send("activate").ok)
        assertTrue(store.activated)
        assertTrue(send("request_maintenance").ok)
        assertNotNull(PendingStore(ctx).get(Pending.Kind.MAINTENANCE))
        assertTrue(send("cancel", mapOf("kind" to "MAINTENANCE")).ok)
        assertNull(PendingStore(ctx).get(Pending.Kind.MAINTENANCE))
        assertFalse(send("cancel", mapOf("kind" to "MAINTENANCE")).ok)
        assertFalse(send("confirm", mapOf("kind" to "NADA")).ok)
        assertFalse(send("comando_que_nao_existe").ok)
    }

    @Test
    fun reportHasWhatTheSiteNeedsAndEncodesAsJson() {
        send("request_maintenance")
        val rep = RemoteReport.build(ctx, consumer, listOf(mapOf("type" to "x", "ok" to true, "msg" to "m")))
        val parsed = Json.parseObject(Json.encode(rep))
        for (k in listOf("kind", "ts", "version", "owner", "filterActive", "filterReason", "tailscale", "pinSet", "pendingApps",
            "pending", "windows", "snapshot", "trust", "ctrs", "executed")) assertTrue("falta $k", k in parsed)
        assertEquals("device", parsed["kind"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("MAINTENANCE", (parsed["pending"] as List<Map<String, Any?>>)[0]["kind"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((parsed["trust"] as Map<String, Any?>).keys.containsAll(listOf("g_ana", "g_bia")))
    }

    @Test
    fun pairedDeviceCanActWithoutALocalPin() {
        becomeOwner()
        assertFalse(PinStore(ctx).isSet)
        assertTrue(store.isPaired)
        // apply() não desiste por falta de PIN quando há Guardian remoto (veja PolicyManager.canAct)
        val log = PolicyManager(ctx).apply()
        assertFalse(log.toString(), log.any { it.startsWith("PIN ausente") })
        store.clear()
        val log2 = PolicyManager(ctx).apply()
        assertTrue(log2.toString(), log2.any { it.startsWith("PIN ausente") })
    }
}
