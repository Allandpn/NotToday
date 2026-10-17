package com.allan.nottoday.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Os mesmos casos de protocol/vectors.json (repositório guardian) que a implementação Python passa. */
class RemoteProtocolTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> = Json.parseObject(
        javaClass.classLoader!!.getResourceAsStream("vectors.json")!!.readBytes().toString(Charsets.UTF_8),
    )

    @Suppress("UNCHECKED_CAST")
    private fun m(x: Any?) = x as Map<String, Any?>

    private fun trust() = Trust.fromJson(m(v["trust"]))

    @Suppress("UNCHECKED_CAST")
    @Test
    fun commandCases() {
        val cases = v["commands"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val env = m(c["envelope"])
            if (c["expect_valid"] == true) {
                Commands.verifyEnvelope(trust(), env)
            } else {
                assertThrows("caso '${c["name"]}' devia ser recusado", CommandException::class.java) {
                    Commands.verifyEnvelope(trust(), env)
                }
            }
        }
    }

    @Test
    fun chromeVectorsAreAcceptedWhenPresent() {
        @Suppress("UNCHECKED_CAST")
        val chrome = v["chrome"] as? List<Map<String, Any?>> ?: return
        for (c in chrome) {
            val consumer = Consumer(c["dev"] as String, Trust.fromJson(m(c["trust"])))
            val ok = consumer.accept(m(c["envelope"]))
            assertEquals(c["type"], ok.cmd.type)
        }
    }

    @Test
    fun deviceRequestSignatureAndId() {
        val r = m(v["device_request"])
        val h = m(r["headers"])
        val spki = B64.dec(r["spki"] as String)
        val msg = DevAuth.signingString(
            r["method"] as String, r["path"] as String, h["X-Ts"] as String, h["X-Nonce"] as String,
            B64.dec(r["body_b64"] as String),
        )
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(msg); verify(B64.dec(h["X-Sig"] as String)) }
        assertTrue(ok)
        assertEquals(h["X-Dev"], DevAuth.deviceId(spki))
        val d = m(v["device_id"])
        assertEquals(d["id"], DevAuth.deviceId(B64.dec(d["spki"] as String)))
        assertEquals(d["fingerprint"], DevAuth.fingerprint(B64.dec(d["spki"] as String)))
    }

    @Test
    fun signedHeadersVerifyWithOurOwnSigner() {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val spki = kp.public.encoded
        val sign = { data: ByteArray -> Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() } }
        val h = DevAuth.headers(sign, spki, "POST", "/v1/x?a=1", "{}".toByteArray(), 1_700_000_000, "nonce-123456")
        val msg = DevAuth.signingString("POST", "/v1/x?a=1", h["X-Ts"]!!, h["X-Nonce"]!!, "{}".toByteArray())
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(kp.public); update(msg); verify(B64.dec(h["X-Sig"]!!)) })
        assertEquals(DevAuth.deviceId(spki), h["X-Dev"])
    }

    @Test
    fun pinBoxOpensThePythonVector() {
        val p = m(v["pinbox"])
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(B64.dec(p["device_private_pkcs8"] as String)))
        val plain = PinBox.open(priv, m(p["box"]), (p["aad"] as String).toByteArray())
        assertEquals(p["plaintext"], String(plain))
        assertThrows(Exception::class.java) { PinBox.open(priv, m(p["box"]), "guardian/set_dev_pin/x".toByteArray()) }
        assertArrayEquals(PinBox.aad("set_pin", "abc"), "guardian/set_pin/abc".toByteArray())
    }

    @Test
    fun challengeIsSha256OfPayload() {
        val c = m(v["sha256_challenge"])
        assertEquals(c["challenge"], B64.enc(Commands.challengeOf(B64.dec(c["payload"] as String))))
    }

    @Test
    fun consumerRejectsReplayWrongDestinationAndBadTrustChanges() {
        val cases = (v["commands"] as List<*>).map { m(it) }
        val first = m(cases.first { it["name"] == "um guardião aprova app" }["envelope"])
        val dev = v["dev"] as String
        val consumer = Consumer(dev, trust())
        consumer.accept(first)
        assertThrows(CommandException::class.java) { consumer.accept(first) } // replay
        assertThrows(CommandException::class.java) { Consumer("outrodestino", trust()).accept(first) }
        // contador sobrevive à serialização
        val again = Consumer.fromJson(Json.parseObject(Json.encode(consumer.toJson())))
        assertThrows(CommandException::class.java) { again.accept(first) }
        assertEquals(dev, again.dev)
        // confiança
        val cmd = { type: String, args: Map<String, Any?> -> Command(dev, "g_ana", 9, type, args) }
        again.applyTrust(cmd("add_guardian", mapOf("id" to "g_cris", "name" to "Cris", "cred" to "c1", "spki" to "s1", "role" to "domains", "allow_sync" to true)))
        assertTrue("g_cris" in again.trust.guardians)
        assertEquals("domains", Consumer.fromJson(Json.parseObject(Json.encode(again.toJson()))).trust.guardians["g_cris"]!!.role)
        assertThrows(CommandException::class.java) { again.applyTrust(cmd("add_guardian", mapOf("id" to "g_cris", "name" to "x", "cred" to "c", "spki" to "s"))) }
        again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_cris")))
        again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_bia")))
        assertThrows(CommandException::class.java) { again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_ana"))) } // último guardião-mor
        assertThrows(CommandException::class.java) { again.applyTrust(cmd("approve_app", emptyMap())) }
    }

    @Test
    fun malformedEnvelopesAreRejectedNotCrashing() {
        val t = trust()
        for (env in listOf<Map<String, Any?>>(
            emptyMap(), mapOf("payload" to 5L), mapOf("payload" to "###"), mapOf("payload" to B64.enc("{}".toByteArray())),
            mapOf("payload" to B64.enc("nao json".toByteArray()), "sigs" to emptyList<Any>()),
        )) {
            assertThrows(CommandException::class.java) { Commands.verifyEnvelope(t, env) }
        }
        val cases = (v["commands"] as List<*>).map { m(it) }
        val good = m(cases.first { it["expect_valid"] == true }["envelope"])
        for (sigs in listOf<Any?>(null, "x", emptyList<Any>(), listOf("x"), listOf(mapOf("guardian" to 1L)))) {
            assertThrows(CommandException::class.java) { Commands.verifyEnvelope(t, good + ("sigs" to sigs)) }
        }
    }
}

class JsonTest {
    @Test
    fun roundTripAndEscapes() {
        val src = mapOf("a" to listOf(1L, 2.5, true, null, "x\"y\\z\n\u00e7\u0001"), "b" to mapOf("c" to "ação"))
        assertEquals(src, Json.parse(Json.encode(src)))
        assertEquals("ã", Json.parse("\"\\u00e3\""))
        assertEquals(mapOf("n" to -3L), Json.parse(" {\"n\" : -3 } "))
    }

    @Test
    fun rejectsGarbage() {
        for (bad in listOf("", "{", "[1,]", "{\"a\":}", "tru", "\"abc", "1 2", "{\"a\" 1}", "\"\\x\"", "[" .repeat(40), "{'a':1}")) {
            assertThrows("'$bad'", Json.JsonException::class.java) { Json.parse(bad) }
        }
    }

    @Test
    fun windowAndDeviceOnlyRulesUseTheConsumersClock() {
        val rp = "gw.example"
        val origin = "https://gw.example"
        val mor = FakePasskey(rp, origin)
        val work = Window(listOf(0, 1, 2, 3, 4), 12 * 60, 17 * 60, -180)
        val trust = Trust(rp, origin, linkedMapOf("g_mor" to Guardian("Allan", linkedMapOf(mor.credIdB64 to mor.spkiB64), "admin", false, work)))
        val consumer = Consumer("devdevdevdevdevdevdev", trust)
        fun env(k: FakePasskey, ctr: Long): Map<String, Any?> {
            val p = payloadOf("devdevdevdevdevdevdev", "g_mor", ctr, "approve_app", mapOf("pkg" to "com.x"))
            return envelopeOf(p, k.sign("g_mor", p))
        }
        fun ms(day: Int, h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 10, day, h, m, 0, 0, java.time.ZoneOffset.ofHours(-3)).toInstant().toEpochMilli()
        consumer.accept(env(mor, 1), ms(14, 14)) // quarta, 14h: vale
        for ((day, h) in listOf(14 to 22, 14 to 11, 17 to 14, 18 to 14)) { // à noite, antes, sábado, domingo
            val e = assertThrows(CommandException::class.java) { consumer.accept(env(mor, 2), ms(day, h)) }
            assertTrue(e.message, "janela" in e.message!!)
        }
        assertEquals(1L, consumer.lastCtr["g_mor"]) // recusado não gasta o contador
        // a mesma assinatura, mas com a flag "sincronizável": recusada
        val e = assertThrows(CommandException::class.java) {
            val p = payloadOf("devdevdevdevdevdevdev", "g_mor", 3, "approve_app", mapOf("pkg" to "com.x"))
            // a chave certa do guardião, mas com a flag BE ligada
            consumer.accept(envelopeOf(p, mor.signWithBackupFlag("g_mor", p)), ms(14, 14))
        }
        assertTrue(e.message, "sincroniz" in e.message!!)
        // quem permite sincronizar aceita
        val open = Trust(rp, origin, linkedMapOf("g_mor" to Guardian("Allan", linkedMapOf(mor.credIdB64 to mor.spkiB64))))
        val p = payloadOf("devdevdevdevdevdevdev", "g_mor", 1, "approve_app", mapOf("pkg" to "com.x"))
        Consumer("devdevdevdevdevdevdev", open).accept(envelopeOf(p, mor.signWithBackupFlag("g_mor", p)), ms(14, 22))
        // a regra sobrevive à serialização, e pareamento antigo (sem as chaves) vale sempre e aceita sincronizada
        val again = Consumer.fromJson(Json.parseObject(Json.encode(consumer.toJson()))).trust.guardians["g_mor"]!!
        assertEquals(false, again.allowSync)
        assertEquals(work.days, again.window!!.days)
        val old = Trust.fromJson(Json.parseObject("""{"rp_id":"x","origin":"https://x","guardians":{"g":{"name":"A","creds":{"c":"s"},"role":"admin"}}}""")).guardians["g"]!!
        assertEquals(true, old.allowSync)
        assertNull(old.window)
    }

    @Test
    fun masterRoleManagesGuardiansAloneAndOthersCannot() {
        val rp = "gw.example"
        val origin = "https://gw.example"
        val dev = "devdevdevdevdevdevdev"
        val mst = FakePasskey(rp, origin)
        val mor = FakePasskey(rp, origin)
        val com = FakePasskey(rp, origin)
        fun g(k: FakePasskey, role: String) = Guardian("x", linkedMapOf(k.credIdB64 to k.spkiB64), role)
        fun trust() = Trust(rp, origin, linkedMapOf("g_mst" to g(mst, "master"), "g_mor" to g(mor, "admin"), "g_com" to g(com, "domains")))
        var ctr = 0L
        fun send(t: Trust, author: String, k: FakePasskey, type: String, args: Map<String, Any?>, vararg more: Pair<String, FakePasskey>) {
            ctr++
            val p = payloadOf(dev, author, ctr, type, args)
            Commands.verifyEnvelope(t, envelopeOf(p, k.sign(author, p), *more.map { it.second.sign(it.first, p) }.toTypedArray()))
        }
        val add = mapOf("id" to "g_n", "name" to "N", "cred" to "c", "spki" to "s", "role" to "master", "allow_sync" to true)
        val t = trust()
        send(t, "g_mst", mst, "add_guardian", add) // o mestre inclui outro mestre, sozinho
        send(t, "g_mst", mst, "remove_guardian", mapOf("id" to "g_mor"))
        send(t, "g_mst", mst, "set_config", mapOf("patch" to emptyMap<String, Any?>())) // e tem tudo do guardião-mor
        send(t, "g_mor", mor, "set_config", mapOf("patch" to emptyMap<String, Any?>()))
        send(t, "g_mor", mor, "add_credential", mapOf("id" to "g_mor", "cred" to "c2", "spki" to "s2")) // a própria passkey
        for ((type, args) in listOf("add_guardian" to add, "remove_guardian" to mapOf("id" to "g_mst"), "set_role" to mapOf("id" to "g_mor", "role" to "master"),
            "add_credential" to mapOf("id" to "g_com", "cred" to "c", "spki" to "s"), "remove_credential" to mapOf("id" to "g_com", "cred" to "c"))) {
            assertThrows(type, CommandException::class.java) { send(t, "g_mor", mor, type, args) }
            assertThrows("$type com 2 assinaturas", CommandException::class.java) { send(t, "g_mor", mor, type, args, "g_mst" to mst) }
        }
        assertThrows(CommandException::class.java) { send(t, "g_com", com, "remove_guardian", mapOf("id" to "g_mst")) }
        // o último mestre não sai nem é rebaixado
        val c = Consumer(dev, trust())
        c.applyTrust(Command(dev, "g_mst", 1, "remove_guardian", mapOf("id" to "g_mor")))
        assertThrows(CommandException::class.java) { c.applyTrust(Command(dev, "g_mst", 2, "remove_guardian", mapOf("id" to "g_mst"))) }
        assertThrows(CommandException::class.java) { c.applyTrust(Command(dev, "g_mst", 3, "set_role", mapOf("id" to "g_mst", "role" to "admin"))) }
        // sem mestre: um guardião-mor promove um deles com uma assinatura; só a promoção a mestre vale
        val old = Trust(rp, origin, linkedMapOf("g_mor" to g(mor, "admin"), "g_b" to g(mst, "admin")))
        val promo = mapOf("id" to "g_b", "role" to "master")
        send(old, "g_mor", mor, "set_role", promo)
        send(old, "g_mor", mor, "set_role", promo, "g_b" to mst)
        assertThrows(CommandException::class.java) { send(old, "g_mor", mor, "set_role", mapOf("id" to "g_b", "role" to "domains"), "g_b" to mst) }
        val c2 = Consumer(dev, old)
        c2.applyTrust(Command(dev, "g_mor", 1, "set_role", promo))
        assertEquals("master", old.guardians["g_b"]!!.role)
    }

    @Test
    fun windowValidationAndTrustPolicyAreFixedAtCreation() {
        for (bad in listOf("""{"days":[],"from":0,"to":10,"utc_offset_min":0}""", """{"days":[7],"from":0,"to":10,"utc_offset_min":0}""",
            """{"days":[1],"from":600,"to":600,"utc_offset_min":0}""", """{"days":[1],"from":0,"to":1441,"utc_offset_min":0}""",
            """{"days":[1],"from":0,"to":10,"utc_offset_min":9999}""", "\"x\"")) {
            assertThrows(CommandException::class.java) { Window.parse(Json.parse(bad)) }
        }
        val trust = Trust("gw", "https://gw", linkedMapOf("g_a" to Guardian("A", linkedMapOf("c" to "s"))))
        val c = Consumer("d", trust)
        val args = mapOf("id" to "g_n", "name" to "N", "cred" to "c2", "spki" to "s2", "role" to "domains", "allow_sync" to false,
            "window" to mapOf("days" to listOf(0L, 1L), "from" to 60L, "to" to 120L, "utc_offset_min" to -180L))
        c.applyTrust(Command("d", "g_a", 9, "add_guardian", args))
        assertEquals(false, trust.guardians["g_n"]!!.allowSync)
        assertEquals(60, trust.guardians["g_n"]!!.window!!.from)
        assertThrows(CommandException::class.java) { c.applyTrust(Command("d", "g_a", 10, "add_guardian", args - "allow_sync" + ("id" to "g_m"))) }
        assertThrows(CommandException::class.java) { c.applyTrust(Command("d", "g_a", 11, "add_guardian", args + ("allow_sync" to true))) } // já existe: a regra não muda
        // remover passkey: a última fica
        c.applyTrust(Command("d", "g_a", 12, "add_credential", mapOf("id" to "g_a", "cred" to "c3", "spki" to "s3")))
        c.applyTrust(Command("d", "g_a", 13, "remove_credential", mapOf("id" to "g_a", "cred" to "c")))
        assertEquals(setOf("c3"), trust.guardians["g_a"]!!.creds.keys)
        assertThrows(CommandException::class.java) { c.applyTrust(Command("d", "g_a", 14, "remove_credential", mapOf("id" to "g_a", "cred" to "c3"))) }
    }
}
