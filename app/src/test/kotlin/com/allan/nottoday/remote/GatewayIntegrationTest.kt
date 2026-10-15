package com.allan.nottoday.remote

import android.content.Context
import com.allan.nottoday.Approved
import com.allan.nottoday.Config
import com.allan.nottoday.PinStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import java.io.File
import java.security.Signature
import java.util.concurrent.TimeUnit

/**
 * Integração de verdade: o Kotlin do aparelho (pareamento, requisições assinadas, long-poll, comandos, relatório)
 * contra o gateway Python real, por HTTP. Só roda com GUARDIAN_REPO apontando para o repositório guardian
 * (precisa de python3 + cryptography): `GUARDIAN_REPO=../guardian ./gradlew :app:testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class GatewayIntegrationTest {
    private val repo: String? = System.getenv("GUARDIAN_REPO")
    private var server: Process? = null
    private lateinit var dir: File
    private lateinit var ctx: Context

    private val soft = newEcKey()
    private val signer = object : DeviceSigner {
        override fun spki(): ByteArray = soft.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(soft.private)
            update(data)
            sign()
        }
    }
    private val dev get() = DevAuth.deviceId(signer.spki())

    private fun builder(vararg args: String) = ProcessBuilder(listOf("python3", "$repo/tests/guardian_sim.py") + args).apply {
        environment()["PYTHONPATH"] = "$repo/src${File.pathSeparator}$repo/tests"
        redirectErrorStream(true)
    }

    private fun sim(cmd: String, vararg rest: String): String {
        val p = builder(cmd, "--dir", dir.path, *rest).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        assertTrue("sim $cmd falhou: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
        return out
    }

    @Before
    fun setUp() {
        assumeTrue("GUARDIAN_REPO não definido", repo != null && File("$repo/tests/guardian_sim.py").exists())
        ctx = RuntimeEnvironment.getApplication()
        dir = File(System.getProperty("java.io.tmpdir"), "guardian-it-${System.nanoTime()}").also { it.mkdirs() }
        server = builder("serve", "--dir", dir.path).start()
        val first = server!!.inputStream.bufferedReader().readLine() ?: error("o gateway de teste não subiu")
        assertTrue(first, first.contains("port"))
    }

    @After
    fun tearDown() {
        server?.destroyForcibly()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun pairReportReceiveAndExecuteCommandsFromARealGateway() {
        // 1) pareamento: a string vem do "site" (simulador), o registro é assinado com a chave do aparelho
        val fp = Pairing.register(ctx, sim("pairing"), "Celular de teste", signer, allowInsecure = true)
        assertEquals(DevAuth.fingerprint(signer.spki()), fp)
        val store = RemoteStore(ctx)
        assertTrue(store.isPaired)
        val session = RemoteSession(ctx, store, GatewayApi(store.gateway!!, signer, allowInsecure = true)) { soft.private }

        // 2) primeiro ciclo: relata e consulta a fila (vazia)
        assertEquals(0, session.cycle(0))
        val listed = Json.parseObject(sim("devices"))
        @Suppress("UNCHECKED_CAST")
        val me = (listed["devices"] as List<Map<String, Any?>>).single { it["id"] == dev }
        assertEquals("Celular de teste", me["name"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("device", (me["report"] as Map<String, Any?>)["kind"])

        // 3) o guardião aprova um app: o comando assinado por passkey chega, é verificado e executado
        sim("send", "--dev", dev, "--type", "approve_app", "--args", """{"pkg":"com.e2e.app"}""")
        assertEquals(1, session.cycle(10))
        assertTrue("com.e2e.app" in Approved(ctx).all())

        // 4) PIN selado no "navegador" (Python), aberto no aparelho
        sim("send", "--dev", dev, "--type", "set_pin", "--pin", "246810")
        assertEquals(1, session.cycle(10))
        assertTrue(PinStore(ctx).verify("246810"))

        // 5) configuração que endurece vale na hora
        sim("send", "--dev", dev, "--type", "set_config", "--args", """{"patch":{"chrome_youtube":"2","canary":"canario.exemplo.com"}}""")
        assertEquals(1, session.cycle(10))
        assertEquals(2, Config(ctx).chromeYoutube)
        assertEquals("canario.exemplo.com", Config(ctx).canary)

        // 6) o contador anti-replay foi gravado e vai no relatório
        assertEquals(3L, store.consumer()!!.lastCtr.values.single())

        // 7) o resultado dos comandos chega ao gateway (auditoria) no ciclo seguinte
        assertEquals(0, session.cycle(0))
        val audit = sim("audit")
        for (needle in listOf("approve_app ok", "set_pin ok", "set_config ok")) assertTrue("falta '$needle' em $audit", needle in audit)
    }

    @Test
    fun longPollWakesUpWhenACommandArrives() {
        Pairing.register(ctx, sim("pairing"), "Celular", signer, allowInsecure = true)
        val store = RemoteStore(ctx)
        val session = RemoteSession(ctx, store, GatewayApi(store.gateway!!, signer, allowInsecure = true)) { soft.private }
        session.cycle(0)
        val sender = Thread {
            Thread.sleep(1500)
            sim("send", "--dev", dev, "--type", "approve_app", "--args", """{"pkg":"com.acordou.app"}""")
        }
        val t0 = System.nanoTime()
        sender.start()
        assertEquals(1, session.cycle(20))
        val ms = (System.nanoTime() - t0) / 1_000_000
        sender.join()
        assertTrue("o long-poll devia acordar na hora, levou $ms ms", ms in 1000..15_000)
        assertTrue("com.acordou.app" in Approved(ctx).all())
    }

    @Test
    fun aWrongKeyOrReplayedStateIsRefusedByTheGateway() {
        Pairing.register(ctx, sim("pairing"), "Celular", signer, allowInsecure = true)
        val store = RemoteStore(ctx)
        val impostor = object : DeviceSigner {
            val k = newEcKey()
            override fun spki(): ByteArray = signer.spki() // diz ser o aparelho
            override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(k.private)
                update(data)
                sign()
            }
        }
        val bad = GatewayApi(store.gateway!!, impostor, allowInsecure = true)
        val e = runCatching { bad.call("GET", "/v1/devices/$dev/commands?after=0&wait=0") }.exceptionOrNull()
        assertTrue("devia recusar: $e", e is GatewayException && e.status == 401)
        assertFalse(store.consumer() == null)
    }

    @Test
    fun unblockFlowFromBlockedListToTheGuardianAnswer() {
        Pairing.register(ctx, sim("pairing"), "Celular", signer, allowInsecure = true)
        val store = RemoteStore(ctx)
        val api = GatewayApi(store.gateway!!, signer, allowInsecure = true)
        val notes = ArrayList<String>()
        val session = RemoteSession(ctx, store, api, { notes.add(it) }) { soft.private }
        val un = UnblockApi(api, dev)

        // 1) o Pi-hole bloqueou: o executor informa, o aparelho lista (anúncios ficam de fora por padrão)
        sim("blocks", "--dev", dev, "--domains", "cdn.video.exemplo.com,api.video.exemplo.com")
        sim("blocks", "--dev", dev, "--domains", "ads.rastreador.net", "--list")
        assertEquals(setOf("cdn.video.exemplo.com", "api.video.exemplo.com"), un.blocked(true).map { it.domain }.toSet())
        assertTrue(un.blocked(false).any { it.domain == "ads.rastreador.net" && !it.own })

        // 2) pedido: só domínios vistos; nada de texto livre
        val e = runCatching { un.request(listOf("nunca-visto.com")) }.exceptionOrNull()
        assertTrue("devia recusar: $e", e is GatewayException && e.status == 400)
        val rid = un.request(listOf("cdn.video.exemplo.com", "api.video.exemplo.com"))!!
        store.watched = store.watched + rid
        assertEquals("open", un.requests().single().status)
        assertEquals(0, session.cycle(0))
        assertTrue("ainda sem resposta", notes.isEmpty())

        // 3) o guardião libera um e nega o outro: o ciclo seguinte avisa
        sim("resolve", "--rid", rid.toString(), "--allow", "cdn.video.exemplo.com", "--deny", "api.video.exemplo.com")
        assertEquals(0, session.cycle(0))
        assertEquals(1, notes.size)
        assertTrue(notes[0], "Liberado: cdn.video.exemplo.com" in notes[0] && "Negado: api.video.exemplo.com" in notes[0])
        assertTrue(store.watched.isEmpty())
        val states = un.requests().single().items.associate { it.domain to it.state }
        assertEquals(mapOf("cdn.video.exemplo.com" to "allowed", "api.video.exemplo.com" to "denied"), states)
        assertEquals(0, session.cycle(0))
        assertEquals("não avisa duas vezes", 1, notes.size)

        // 4) cancelar um pedido aberto
        val rid2 = un.request(listOf("api.video.exemplo.com"))!!
        un.cancel(rid2)
        assertEquals("cancelled", un.requests().first { it.id == rid2 }.items.single().state)
    }
}
