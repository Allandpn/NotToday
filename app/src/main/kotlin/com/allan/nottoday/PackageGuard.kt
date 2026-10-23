package com.allan.nottoday

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Base de apps aprovados pela pessoa de confiança. Qualquer app de usuário fora dela fica suspenso. */
class Approved(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("approved", Context.MODE_PRIVATE)

    var baselineDone: Boolean
        get() = prefs.getBoolean("baseline", false)
        set(v) = prefs.edit().putBoolean("baseline", v).apply()

    fun all(): Set<String> = prefs.getString("pkgs", null).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    fun addAll(pkgs: Collection<String>) = prefs.edit().putString("pkgs", (all() + pkgs).joinToString(",")).apply()

    fun remove(pkg: String) = prefs.edit().putString("pkgs", (all() - pkg).joinToString(",")).apply()

    fun clear() = prefs.edit().clear().apply()
}

object PackageGuard {
    /** Nunca suspensos por falta de aprovação: o próprio app e o Tailscale (a VPN que sustenta o filtro). */
    private fun alwaysAllowed(context: Context) = setOf(context.packageName, Catalog.TAILSCALE_PKG)

    private val KNOWN_RISK = mapOf(
        "moe.shizuku.privileged.api" to "Shizuku (poderes de shell)",
        "in.sunilpaulmathew.ashell" to "aShell (shell no aparelho)",
        "com.afwsamples.testdpc" to "Test DPC",
        "com.aistra.hail" to "Hail (desativa apps)",
    )
    private val KEYWORDS = listOf("torrent", "vpn", "proxy", "tor ", "onion", "incognit", "anonym", "shizuku", "adb")

    fun installedSet(context: Context): Set<String> =
        context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .map { it.packageName }.toSet()

    /** Apps instalados pelo usuário (fora os do sistema, inclusive os pré-instalados atualizados pela loja). */
    fun userApps(context: Context): List<String> =
        context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
            .map { it.packageName }.sorted()

    /** Clientes de torrent instalados: lista conhecida ou "torrent" no nome/pacote. Nunca liberados por aprovação. */
    fun torrentApps(context: Context): List<String> =
        userApps(context).filter { pkg ->
            pkg in Catalog.torrentPackages || "torrent" in (label(context, pkg) + " " + pkg).lowercase()
        }

    /** Apps de usuário ainda não aprovados. Vazio enquanto a base não foi definida (evita suspender tudo no 1º uso). */
    fun pending(context: Context): List<String> {
        val approved = Approved(context)
        if (!approved.baselineDone) return emptyList()
        val ok = approved.all() + alwaysAllowed(context)
        return userApps(context).filter { it !in ok }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (_: Exception) {
        pkg
    }

    /** Motivos pelos quais o app merece atenção na hora de aprovar (vazio = nada de especial). */
    fun risks(context: Context, pkg: String): List<String> {
        val out = mutableListOf<String>()
        KNOWN_RISK[pkg]?.let { out += it }
        val name = (label(context, pkg) + " " + pkg).lowercase()
        if (pkg in Catalog.torrentPackages || "torrent" in name) out += "torrent (fica bloqueado)"
        if (KEYWORDS.any { it in name }) out += "nome sugere VPN/torrent/anônimo"
        if (pkg in Browsers.installed(context)) out += if (pkg == "com.android.chrome") "navegador" else "navegador sem bloqueio de acesso por IP"
        try {
            val info = context.packageManager.getPackageInfo(
                pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()),
            )
            val perms = info.services?.mapNotNull { it.permission }.orEmpty()
            if (Manifest.permission.BIND_VPN_SERVICE in perms) out += "cria VPN"
            if (Manifest.permission.BIND_ACCESSIBILITY_SERVICE in perms) out += "serviço de acessibilidade"
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    /** Apps com ícone no launcher, para a tela de escolha de apps enviados ao Pi-hole. */
    fun launcherApps(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(
            intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).map { it.activityInfo.packageName }.distinct()
    }
}

/** Quais apps passam pelo Pi-hole (via Tailscale): navegadores (exceto os isentos) + os escolhidos. */
object Routing {
    fun routed(context: Context, config: Config): List<String> {
        val installed = PackageGuard.installedSet(context)
        val browsers = Browsers.installed(context) - config.browsersExempt.toSet()
        val extra = config.routedExtra.filter { it in installed && it != context.packageName }
        return (browsers + extra).distinct().filter { it != Catalog.TAILSCALE_PKG }.sorted()
    }

    /** Lista entregue ao Tailscale: precisa incluir o NotToday para o teste de saúde passar pela VPN. */
    fun included(context: Context, config: Config): List<String> =
        (routed(context, config) + context.packageName).distinct().sorted()
}
