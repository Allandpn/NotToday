package com.allan.nottoday.remote

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import java.io.File
import java.security.MessageDigest

/** `install_app`: quem pode mandar, formato dos argumentos e as conferências antes de instalar (sem APK de verdade). */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class InstallAppTest {
    private val rp = "gw.example"
    private val origin = "https://gw.example"
    private val dev = "devdevdevdevdevdevdev"
    private val sha = "a".repeat(64)

    private fun args(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("pkg" to "com.exemplo.app", "sha256" to sha, "size" to 10L).apply { putAll(extra) }

    // ---- autorização ----

    private fun accept(role: String, window: Window? = null, a: Map<String, Any?> = args(), now: Long = System.currentTimeMillis()) {
        val k = FakePasskey(rp, origin)
        val trust = Trust(rp, origin, linkedMapOf("g" to Guardian("G", linkedMapOf(k.credIdB64 to k.spkiB64), role, true, window)))
        val p = payloadOf(dev, "g", 1, Commands.T_INSTALL_APP, a)
        Consumer(dev, trust).accept(envelopeOf(p, k.sign("g", p)), now)
    }

    @Test
    fun onlyTheMasterMayInstall() {
        accept("master")
                for (role in listOf("admin", "domains")) {
            val e = assertThrows(CommandException::class.java) { accept(role) }
            assertTrue(e.message, "mestre" in e.message!!)
        }
    }

    @Test
    fun masterOutsideTheWindowIsRefused() {
        val work = Window(listOf(0, 1, 2, 3, 4), 12 * 60, 17 * 60, -180)
        fun ms(day: Int, h: Int) = java.time.ZonedDateTime.of(2026, 10, day, h, 0, 0, 0, java.time.ZoneOffset.ofHours(-3)).toInstant().toEpochMilli()
        accept("master", work, now = ms(14, 14))
        val e = assertThrows(CommandException::class.java) { accept("master", work, now = ms(14, 22)) }
        assertTrue(e.message, "janela" in e.message!!)
    }

    @Test
    fun malformedArgumentsAreRefused() {
        val bad = listOf(
            args("sha256" to "A".repeat(64)), args("sha256" to "a".repeat(63)), args("pkg" to "semponto"),
            args("pkg" to "com.exemplo; rm"), args("size" to 0L), args("size" to "10"), args("size" to Commands.MAX_APK_BYTES + 1),
            args("version_code" to 12L), args("version_name" to "1.2.0"), args("extra" to "x"),
            args().filterKeys { it != "pkg" },
        )
        for (a in bad) assertThrows(a.toString(), CommandException::class.java) { accept("master", a = a) }
    }

    // ---- conferências antes de instalar ----

    private class FakeEnv(
        var archive: ApkInfo?, var installed: ApkInfo? = null, var failure: String? = null,
    ) : InstallEnv {
        var installs = 0
        override fun inspect(file: File) = archive
        override fun installed(pkg: String) = installed
        override fun install(file: File, pkg: String): String? {
            installs++
            return failure
        }
    }

    private lateinit var ctx: Context
    private lateinit var store: RemoteStore
    private val body = "conteudo do apk".toByteArray()
    private val bodySha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        store = RemoteStore(ctx)
    }

    private fun info(code: Long = 2, cert: String = "c1", pkg: String = "com.exemplo.app") = ApkInfo(pkg, code, "1.2.0", setOf(cert))

    private fun run(
        env: InstallEnv, hash: String = bodySha, size: Long = body.size.toLong(), served: ByteArray = body, fail: GatewayException? = null,
        sleeps: MutableList<Long> = ArrayList(),
    ): String {
        val a = Commands.checkInstallArgs(mapOf("pkg" to "com.exemplo.app", "sha256" to hash, "size" to size))
        val fetch = { _: String, dest: File, _: Long ->
            fail?.let { throw it }
            dest.writeBytes(served)
            GatewayApi.Download(MessageDigest.getInstance("SHA-256").digest(served).joinToString("") { "%02x".format(it) }, served.size.toLong())
        }
        return AppInstaller(ctx, store, fetch, env) { sleeps.add(it) }.install(dev, a)
    }

    private fun refused(env: FakeEnv, part: String, block: () -> Unit) {
        val before = env.installs
        val e = assertThrows(CommandException::class.java) { block() }
        assertTrue(e.message, part in e.message!!)
        assertEquals(before, env.installs)
        assertTrue(File(ctx.createDeviceProtectedStorageContext().cacheDir, "apk").listFiles().orEmpty().isEmpty()) // arquivo apagado
    }

    @Test
    fun installsANewAppAndRemembersItsCertificate() {
        val env = FakeEnv(info())
        assertEquals("instalado com.exemplo.app 1.2.0", run(env))
        assertEquals(1, env.installs)
        assertEquals("c1", store.certPin("com.exemplo.app"))
        // mesma assinatura depois: passa; outra: não
        env.installed = info(code = 2)
        env.archive = info(code = 3)
        run(env)
        env.archive = info(code = 4, cert = "outro")
        refused(env, "certificado") { run(env) }
    }

    @Test
    fun wrongHashPackageCertificateAndDowngradeDoNotInstall() {
        val env = FakeEnv(info())
        refused(env, "hash") { run(env, hash = "b".repeat(64)) }
        refused(env, "tamanho") { run(env, size = 3) }
        env.archive = info(pkg = "com.outro.app")
        refused(env, "pacote") { run(env) }
        env.archive = info(cert = "novo")
        env.installed = info(cert = "velho")
        refused(env, "certificado diferente") { run(env) }
        env.archive = info(code = 5, cert = "velho")
        env.installed = info(code = 5, cert = "velho")
        refused(env, "não é maior") { run(env) }
        env.installed = info(code = 9, cert = "velho")
        refused(env, "não é maior") { run(env) }
        env.archive = null
        refused(env, "ilegível") { run(env) }
    }

    @Test
    fun gatewayAnswersAreTranslated() {
        val env = FakeEnv(info())
        refused(env, "não autoriza") { run(env, fail = GatewayException(403, "nope")) }
        val sleeps = ArrayList<Long>()
        refused(env, "ainda não chegou") { run(env, fail = GatewayException(404, "nope"), sleeps = sleeps) }
        assertTrue(sleeps.isNotEmpty()) // tentou de novo antes de desistir
    }

    @Test
    fun installerFailureIsReported() {
        val env = FakeEnv(info(), failure = "instalação falhou: INSTALL_FAILED_X")
        val e = assertThrows(CommandException::class.java) { run(env) }
        assertTrue(e.message, "INSTALL_FAILED_X" in e.message!!)
        assertNull(store.certPin("com.exemplo.app"))
    }

    // ---- o próprio NotToday ----

    @Test
    fun selfUpdateIsReportedOnFirstStartAfterTheSwap() {
        assertNull(AppInstaller.takeSelfResult(ctx, store))
        store.setPendingSelfInstall("1|0.1.0") // a versão instalada (build de teste) já atende
        val r = AppInstaller.takeSelfResult(ctx, store)!!
        assertEquals("install_app", r["type"])
        assertEquals(true, r["ok"])
        assertNull(store.pendingSelfInstall()) // só relata uma vez
        store.setPendingSelfInstall("999999|9.9.9") // a troca não aconteceu
        assertEquals(false, AppInstaller.takeSelfResult(ctx, store)!!["ok"])
        assertFalse(store.pendingSelfInstall() != null)
    }
}
