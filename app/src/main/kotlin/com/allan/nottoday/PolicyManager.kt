package com.allan.nottoday

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings

/** Aplica o estado desejado (Config) via DevicePolicyManager. Exige ser Device Owner. */
class PolicyManager(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val config = Config(context)

    fun isDeviceOwner() = dpm.isDeviceOwnerApp(context.packageName)

    /** Falha fechada: com Device Owner e sem PIN (dados apagados) não se mexe em nada. */
    private fun canAct(): Boolean {
        val pins = PinStore(context)
        return pins.isSet || pins.firstPinAllowedAsOwner
    }

    /** Aplica tudo. Cada passo é isolado: uma falha não impede os demais. Devolve o log. */
    fun apply(): List<String> {
        if (!isDeviceOwner()) return listOf("Não é Device Owner; nada aplicado.")
        if (!canAct()) return listOf("PIN ausente com Device Owner ativo; estado mantido.")
        val log = mutableListOf<String>()
        val window = Maintenance(context).isActive()
        val devOpen = DevWindow(context).isActive()
        if (window) log += "Janela de manutenção ativa: ADB/instalação/USB/lojas liberados temporariamente."
        if (devOpen) log += "Janela de desenvolvimento ativa: ADB aberto por poucos segundos."

        for (spec in Catalog.restrictions) {
            step(log, spec.key) {
                val lifted = (window && spec.key in Maintenance.RESTRICTION_KEYS) ||
                    (devOpen && spec.key == UserManager.DISALLOW_DEBUGGING_FEATURES)
                if (config.isRestrictionOn(spec) && !lifted) dpm.addUserRestriction(admin, spec.key)
                else dpm.clearUserRestriction(admin, spec.key)
            }
        }
        if (devOpen) step(log, "adb") { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1") }

        step(log, "auto-hora") {
            val dateTimeLocked = Catalog.restrictions
                .first { it.key == UserManager.DISALLOW_CONFIG_DATE_TIME }
                .let(config::isRestrictionOn)
            if (dateTimeLocked) {
                dpm.setAutoTimeEnabled(admin, true)
                dpm.setAutoTimeZoneEnabled(admin, true)
            }
        }

        // Autoproteção: não desinstalar nem forçar parada/limpar dados do próprio app, do Tailscale e dos protegidos.
        step(log, "autoproteção") {
            val installed = PackageGuard.installedSet(context)
            val protectedPkgs = (listOf(context.packageName, Catalog.TAILSCALE_PKG) + config.protectedPackages)
                .filter { it in installed }.distinct()
            protectedPkgs.forEach { dpm.setUninstallBlocked(admin, it, true) }
            dpm.setUserControlDisabledPackages(admin, protectedPkgs)
        }

        // O aviso do serviço em primeiro plano precisa de permissão de notificação (melhor esforço).
        step(log, "notificações") {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }

        step(log, "vpn") { applyVpn(log, release = false) }
        step(log, "suspensões") { reconcile(log, desiredSuspended(window)) }
        VpnWatch.register(context)
        step(log, "chrome") { applyChromePolicy(config.dnsFilterEnabled, config.chromePolicy) }
        Watchdog.schedule(context)
        GuardService.start(context)
        step(log, "atualizações") {
            dpm.setSystemUpdatePolicy(
                admin,
                if (config.postponeUpdates) SystemUpdatePolicy.createPostponeInstallPolicy() else null,
            )
        }
        return log
    }

    /** Desfaz tudo o que foi aplicado e então abre mão do Device Owner. */
    fun releaseDeviceOwner(): List<String> {
        if (!isDeviceOwner()) return listOf("Não é Device Owner.")
        val log = mutableListOf<String>()
        for (spec in Catalog.restrictions) step(log, spec.key) { dpm.clearUserRestriction(admin, spec.key) }
        Watchdog.cancel(context)
        GuardService.stop(context)
        step(log, "suspensões") { reconcile(log, emptySet()) }
        step(log, "vpn") { applyVpn(log, release = true) }
        VpnWatch.unregister(context)
        step(log, "atualizações") { dpm.setSystemUpdatePolicy(admin, null) }
        step(log, "chrome") { applyChromePolicy(false, false) }
        step(log, "autoproteção") {
            dpm.setUserControlDisabledPackages(admin, emptyList())
            val installed = PackageGuard.installedSet(context)
            (listOf(context.packageName, Catalog.TAILSCALE_PKG) + config.protectedPackages)
                .filter { it in installed }.forEach { dpm.setUninstallBlocked(admin, it, false) }
        }
        dpm.clearDeviceOwnerApp(context.packageName)
        PinStore(context).clear()
        PinStore(context, DEV_PIN).clear()
        config.clear()
        Approved(context).clear()
        DevWindow(context).clear()
        Health.invalidate(context)
        PendingStore(context).clearAll()
        Maintenance(context).stop()
        log += "Device Owner removido."
        return log
    }

    // --- Batimento ---------------------------------------------------------------

    /**
     * Rotina leve e frequente (serviço de guarda a cada ~20 s, alarme a cada 1 min): fecha a janela de
     * desenvolvimento vencida, refaz o teste de saúde e concilia política do Tailscale e suspensões.
     */
    fun heartbeat(): List<String> {
        if (!isDeviceOwner() || !canAct()) return emptyList()
        val log = mutableListOf<String>()
        if (closeDevWindowIfDue(log)) return log
        if (config.dnsFilterEnabled) {
            if (VpnWatch.isVpnUp(context)) Health.check(context) else Health.invalidate(context)
        }
        log += quickSync()
        return log
    }

    /** Sem rede: só concilia a política do Tailscale e as suspensões com o estado atual. */
    fun quickSync(): List<String> {
        if (!isDeviceOwner() || !canAct()) return emptyList()
        val log = mutableListOf<String>()
        step(log, "tailscale") { applyTailscaleConfig(log, release = false) }
        step(log, "suspensões") { reconcile(log, desiredSuspended(Maintenance(context).isActive())) }
        return log
    }

    /** A janela de desenvolvimento acabou: fecha o ADB e, se configurado, reinicia (mata processos de shell). */
    private fun closeDevWindowIfDue(log: MutableList<String>): Boolean {
        val dev = DevWindow(context)
        val closing = dev.closingState() ?: return false
        dev.markClosed()
        log += apply()
        step(log, "adb") { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0") }
        if (config.devReboot && closing.rebootNeeded) {
            log += "Janela de desenvolvimento encerrada: reiniciando."
            step(log, "reinício") { dpm.reboot(admin) }
        }
        return true
    }

    // --- Suspensões ----------------------------------------------------------------

    /** O filtro está confirmado? Tailscale de pé E o canário bloqueado pelo DNS da VPN. */
    fun filterActive(): Boolean = config.dnsFilterEnabled && VpnWatch.isVpnUp(context) && Health.isOk(context)

    private fun installedStores(): List<String> {
        val installed = PackageGuard.installedSet(context)
        return Catalog.storePackages.filter { it in installed }
    }

    /** Tudo que deve estar suspenso agora: lojas alternativas, apps marcados sem filtro e apps novos sem aprovação. */
    private fun desiredSuspended(maintenanceWindow: Boolean): Set<String> {
        val d = mutableSetOf<String>()
        if (config.suspendBlocked && !maintenanceWindow) d += installedStores()
        if (config.dnsFilterEnabled && config.browsersNeedFilter && !filterActive()) d += Routing.routed(context, config)
        if (config.newAppsDeny) d += PackageGuard.pending(context)
        d -= context.packageName
        d -= Catalog.TAILSCALE_PKG
        return d
    }

    /** Leva o estado de suspensão ao desejado e desfaz só o que este app suspendeu antes. */
    private fun reconcile(log: MutableList<String>, desired: Set<String>) = synchronized(LOCK) {
        val state = context.createDeviceProtectedStorageContext().getSharedPreferences("suspension", Context.MODE_PRIVATE)
        val previous = state.getString("pkgs", null).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        val installed = PackageGuard.installedSet(context)
        val want = desired.filter { it in installed }.toSet()
        var failed = emptySet<String>()
        if (want.isNotEmpty()) {
            failed = dpm.setPackagesSuspended(admin, want.toTypedArray(), true).toSet()
            if (failed.isNotEmpty()) log += "Sem efeito (sistema recusou): $failed"
        }
        val release = (previous - want).filter { it in installed }
        if (release.isNotEmpty()) dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
        val now = want - failed
        if (now != previous) log += "Suspensos agora: ${now.sorted()}"
        state.edit().putString("pkgs", now.joinToString(",")).apply()
    }

    // --- VPN / Tailscale -----------------------------------------------------------

    /**
     * O Tailscale é a VPN "sempre ativa" (o Device Owner a autoriza sem diálogo; sem lockdown, para o resto do tráfego
     * seguir direto). A configuração gerenciada manda o DNS da tailnet (o Pi-hole) e limita a VPN aos apps marcados.
     */
    private fun applyVpn(log: MutableList<String>, release: Boolean) {
        val want: String? = if (release || !config.dnsFilterEnabled) null else Catalog.TAILSCALE_PKG
        val current = dpm.getAlwaysOnVpnPackage(admin)
        if (current != want) {
            try {
                dpm.setAlwaysOnVpnPackage(admin, want, false)
                log += "VPN sempre ativa: ${want ?: "nenhuma"}."
            } catch (e: Exception) {
                log += "VPN sempre ativa (${want}) falhou: ${e.javaClass.simpleName}" +
                    if (want != null) " — o Tailscale está instalado e com login feito?" else ""
            }
        }
        applyTailscaleConfig(log, release)
    }

    /**
     * Configuração gerenciada do Tailscale (melhor esforço; confira no app dele). Só é reenviada quando muda, para
     * não reconectar a VPN à toa. Sem `ForceEnabled`: você liga e desliga o Tailscale pelo app dele.
     */
    private fun applyTailscaleConfig(log: MutableList<String>, release: Boolean) {
        if (Catalog.TAILSCALE_PKG !in PackageGuard.installedSet(context)) return
        val want = Bundle()
        if (!release && config.dnsFilterEnabled) {
            want.putString("UseTailscaleDNSSettings", "always")
            want.putString("ManagedByOrganizationName", "NotToday")
            want.putStringArray("IncludedPackageNames", Routing.included(context, config).toTypedArray())
        }
        val current = dpm.getApplicationRestrictions(admin, Catalog.TAILSCALE_PKG)
        if (comparable(current) != comparable(want)) {
            dpm.setApplicationRestrictions(admin, Catalog.TAILSCALE_PKG, want)
            log += "Tailscale: política atualizada (apps no Pi-hole: ${Routing.routed(context, config)})."
        }
    }

    @Suppress("DEPRECATION")
    private fun comparable(b: Bundle): Map<String, Any?> = b.keySet().associateWith { k ->
        when (val v = b.get(k)) {
            is Array<*> -> v.map { it.toString() }.sorted()
            else -> v
        }
    }

    /**
     * Política gerenciada do Chrome (melhor esforço, sem garantia por versão): DoH desligado quando o filtro está
     * ativo; SafeSearch forçado, YouTube restrito e modo anônimo desativado.
     */
    private fun applyChromePolicy(filterOn: Boolean, hardening: Boolean) {
        val b = Bundle()
        if (filterOn) b.putString("DnsOverHttpsMode", "off")
        if (hardening) {
            b.putBoolean("ForceGoogleSafeSearch", true)
            b.putInt("ForceYouTubeRestrict", 2)
            b.putInt("IncognitoModeAvailability", 1)
        }
        dpm.setApplicationRestrictions(admin, CHROME, b)
    }

    companion object {
        private const val CHROME = "com.android.chrome"
        const val DEV_PIN = "devpin"
        private val LOCK = Any()
    }

    private inline fun step(log: MutableList<String>, name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log += "FALHOU $name: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
