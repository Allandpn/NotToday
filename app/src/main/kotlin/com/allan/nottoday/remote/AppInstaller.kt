package com.allan.nottoday.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** O que importa de um APK (ou de um app instalado) para decidir se pode ser instalado. `certs`: SHA-256 dos assinantes, em hex. */
class ApkInfo(val pkg: String, val versionCode: Long, val versionName: String?, val certs: Set<String>)

/** A parte que depende do Android, separada para os testes de decisão rodarem sem APK de verdade. */
interface InstallEnv {
    fun inspect(file: File): ApkInfo?

    fun installed(pkg: String): ApkInfo?

    /** Instala em silêncio (Device Owner). null = instalado; senão o motivo da falha. */
    fun install(file: File, pkg: String): String?
}

/**
 * `install_app`: baixa o APK do gateway, confere tudo antes de tocar no sistema e instala pelo PackageInstaller sem
 * ação do usuário. Qualquer falha apaga o arquivo e vira mensagem de [CommandException].
 */
class AppInstaller(
    private val context: Context,
    private val store: RemoteStore,
    /** (path, destino, máximo de bytes) -> hash e tamanho do que foi gravado. */
    private val fetch: (String, File, Long) -> GatewayApi.Download,
    private val env: InstallEnv = SystemInstallEnv(context),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun install(dev: String, a: InstallArgs): String {
        val dir = File(context.createDeviceProtectedStorageContext().cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // sobras de uma instalação interrompida
        val file = File(dir, "${a.sha256}.apk")
        try {
            download(dev, a, file)
            val info = verify(file, a)
            val self = a.pkg == context.packageName
            if (self) {
                inFlight = true
                store.setPendingSelfInstall("${info.versionCode}|${info.versionName.orEmpty()}")
            }
            try {
                env.install(file, a.pkg)?.let { throw CommandException(it) }
            } catch (e: Throwable) {
                if (self) store.setPendingSelfInstall(null)
                throw e
            } finally {
                inFlight = false
            }
            if (self) store.setPendingSelfInstall(null)
            store.setCertPin(a.pkg, info.certs.sorted().joinToString(","))
            return "instalado ${a.pkg} ${info.versionName ?: info.versionCode}"
        } finally {
            file.delete()
        }
    }

    private fun download(dev: String, a: InstallArgs, file: File) {
        val path = "/v1/devices/$dev/apk/${a.sha256}"
        var tries = 0
        while (true) {
            try {
                val got = fetch(path, file, a.size)
                if (got.sha256 != a.sha256) throw CommandException("hash não confere")
                if (got.size != a.size) throw CommandException("tamanho não confere")
                return
            } catch (e: GatewayException) {
                file.delete()
                when (e.status) {
                    403 -> throw CommandException("o gateway não autoriza este APK neste aparelho")
                    404 -> {
                        // O Pi envia o APK ao gateway em até ~1 min depois do comando.
                        if (++tries >= NOT_YET_TRIES) throw CommandException("o APK ainda não chegou ao gateway; tente de novo")
                        sleep(NOT_YET_WAIT_MS)
                    }
                    else -> throw CommandException("gateway recusou o download: ${e.message}")
                }
            } catch (e: IOException) {
                file.delete()
                throw CommandException("falha ao baixar o APK: ${e.message}")
            }
        }
    }

    /** Hash e tamanho do arquivo no disco, pacote, certificado e versão. */
    private fun verify(file: File, a: InstallArgs): ApkInfo {
        if (file.length() != a.size) throw CommandException("tamanho não confere")
        if (sha256Hex(file) != a.sha256) throw CommandException("hash não confere")
        val info = env.inspect(file) ?: throw CommandException("APK ilegível ou sem assinatura")
        if (info.pkg != a.pkg) throw CommandException("o pacote do APK (${info.pkg}) não é ${a.pkg}")
        if (info.certs.isEmpty()) throw CommandException("APK sem certificado de assinatura")
        val have = env.installed(a.pkg)
        if (have != null) {
            if (info.certs != have.certs) throw CommandException("certificado diferente do app instalado")
            if (info.versionCode <= have.versionCode) throw CommandException("versão ${info.versionCode} não é maior que a instalada (${have.versionCode})")
        }
        store.certPin(a.pkg)?.let { if (it != info.certs.sorted().joinToString(",")) throw CommandException("certificado diferente do visto na primeira instalação") }
        return info
    }

    companion object {
        private const val NOT_YET_TRIES = 3
        private const val NOT_YET_WAIT_MS = 10_000L

        /** Uma troca do próprio app está esperando o instalador (o processo morre quando ela acontece). */
        @Volatile private var inFlight = false

        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Na primeira inicialização depois da troca do próprio app: devolve o resultado para o relatório (e apaga a marca).
         * Se a versão nova não está instalada, a troca não aconteceu.
         */
        fun takeSelfResult(context: Context, store: RemoteStore): Map<String, Any?>? {
            if (inFlight) return null
            val mark = store.pendingSelfInstall() ?: return null
            store.setPendingSelfInstall(null)
            val want = mark.substringBefore('|').toLongOrNull() ?: 0L
            val now = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) {
                null
            }
            val ok = now != null && now.longVersionCode >= want
            val msg = if (ok) "instalado ${context.packageName} ${now?.versionName}" else "a atualização do NotToday não foi aplicada"
            return RemoteCommands.Result(Commands.T_INSTALL_APP, ok, msg).toMap()
        }
    }
}

class SystemInstallEnv(private val context: Context) : InstallEnv {
    private val pm get() = context.packageManager
    private val flags get() = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    private fun info(pi: android.content.pm.PackageInfo): ApkInfo {
        val signers = pi.signingInfo?.apkContentsSigners.orEmpty()
        val certs = signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        return ApkInfo(pi.packageName, pi.longVersionCode, pi.versionName, certs)
    }

    override fun inspect(file: File): ApkInfo? = pm.getPackageArchiveInfo(file.path, flags)?.let(::info)

    override fun installed(pkg: String): ApkInfo? = try {
        info(pm.getPackageInfo(pkg, flags))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun install(file: File, pkg: String): String? {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        val action = "com.allan.nottoday.INSTALL_RESULT.$id"
        val latch = CountDownLatch(1)
        var status = PackageInstaller.STATUS_FAILURE
        var detail: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                latch.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            installer.openSession(id).use { s ->
                file.inputStream().use { input ->
                    s.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }
                // Mutável: o instalador acrescenta o status ao intent.
                val pending = PendingIntent.getBroadcast(
                    context, id, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pending.intentSender)
            }
            if (!latch.await(WAIT_MIN, TimeUnit.MINUTES)) return "o instalador não respondeu"
            return when (status) {
                PackageInstaller.STATUS_SUCCESS -> null
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    installer.abandonSession(id)
                    "o Android pediu confirmação do usuário (o NotToday precisa ser Device Owner)"
                }
                else -> "instalação falhou: ${detail ?: "status $status"}"
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(id) }
            return "instalação falhou: ${e.message}"
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val WAIT_MIN = 3L
    }
}
