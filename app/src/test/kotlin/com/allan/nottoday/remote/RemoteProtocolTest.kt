package com.allan.nottoday.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        again.applyTrust(cmd("add_guardian", mapOf("id" to "g_cris", "name" to "Cris", "cred" to "c1", "spki" to "s1", "role" to "domains")))
        assertTrue("g_cris" in again.trust.guardians)
        assertEquals("domains", Consumer.fromJson(Json.parseObject(Json.encode(again.toJson()))).trust.guardians["g_cris"]!!.role)
        assertThrows(CommandException::class.java) { again.applyTrust(cmd("add_guardian", mapOf("id" to "g_cris", "name" to "x", "cred" to "c", "spki" to "s"))) }
        again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_cris")))
        again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_bia")))
        assertThrows(CommandException::class.java) { again.applyTrust(cmd("remove_guardian", mapOf("id" to "g_ana"))) } // último administrador
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
}
