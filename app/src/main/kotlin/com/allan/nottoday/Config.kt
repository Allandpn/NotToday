package com.allan.nottoday

import android.content.Context
import android.os.UserManager

/** Uma restrição de usuário que o app sabe ligar/desligar. */
data class RestrictionSpec(
    val key: String,
    val label: String,
    val defaultOn: Boolean,
    val warning: String? = null,
)

object Catalog {
    val restrictions = listOf(
        RestrictionSpec(UserManager.DISALLOW_CONFIG_TETHERING, "Bloquear hotspot/tethering", true),
        RestrictionSpec(UserManager.DISALLOW_WIFI_TETHERING, "Bloquear hotspot Wi-Fi", true),
        RestrictionSpec(UserManager.DISALLOW_WIFI_DIRECT, "Bloquear Wi-Fi Direct", true),
        RestrictionSpec(UserManager.DISALLOW_NETWORK_RESET, "Bloquear reset de rede", true),
        RestrictionSpec(
            UserManager.DISALLOW_USB_FILE_TRANSFER, "Bloquear transferência de arquivos por USB", true,
            "Não afeta o ADB. A janela de manutenção libera temporariamente.",
        ),
        RestrictionSpec(UserManager.DISALLOW_UNINSTALL_APPS, "Bloquear desinstalar apps", true),
        RestrictionSpec(UserManager.DISALLOW_APPS_CONTROL, "Bloquear forçar parada/limpar dados", true),
        RestrictionSpec(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, "Bloquear fontes desconhecidas", true),
        RestrictionSpec(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, "Bloquear fontes desconhecidas (global)", true),
        RestrictionSpec(UserManager.DISALLOW_SAFE_BOOT, "Bloquear modo seguro", true),
        RestrictionSpec(UserManager.DISALLOW_ADD_USER, "Bloquear novos usuários (Pasta Segura etc.)", true),
        RestrictionSpec(UserManager.DISALLOW_ADD_MANAGED_PROFILE, "Bloquear perfis gerenciados", true),
        RestrictionSpec(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, "Bloquear Espaço Privado", true),
        RestrictionSpec(
            "no_add_clone_profile", "Bloquear perfil clone (Mensageiro duplo)", true,
            "Nome oculto da API; se o sistema recusar, aparece no log.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS, "Bloquear trocar DNS privado", true,
            "DNS privado fixo ignoraria o DNS do Tailscale/Pi-hole.",
        ),
        RestrictionSpec(UserManager.DISALLOW_CONFIG_VPN, "Bloquear VPN", true, "Impede também VPNs legítimas."),
        RestrictionSpec(
            UserManager.DISALLOW_CONFIG_DATE_TIME, "Bloquear mudar data/hora", true,
            "Mantém o atraso de tentativas de PIN confiável; liga hora automática.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_INSTALL_APPS, "Bloquear instalar qualquer app", false,
            "Impede até a Play Store de instalar. O padrão é permitir e suspender o app novo até a aprovação.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_MODIFY_ACCOUNTS, "Bloquear contas (adicionar/remover)", false,
            "Sem contas novas, mas também sem login em apps novos.",
        ),
        RestrictionSpec(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, "Bloquear config de rede móvel/APN", false),
        RestrictionSpec(
            UserManager.DISALLOW_CONFIG_WIFI, "Bloquear config de Wi-Fi", false,
            "Você não conseguirá entrar em Wi-Fi novo.",
        ),
        RestrictionSpec(UserManager.DISALLOW_USER_SWITCH, "Bloquear troca de usuário", false),
        RestrictionSpec(
            UserManager.DISALLOW_FACTORY_RESET, "Bloquear reset de fábrica (Configurações)", false,
            "Não impede reset pelo recovery. Se o PIN se perder, é a última saída.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_DEBUGGING_FEATURES, "Bloquear depuração USB/ADB (fecha o shell)", false,
            "Ative por último. Sem ADB, só a janela de desenvolvimento (PIN de dev) ou a de manutenção (PIN + espera) " +
                "o reabre. Confira com `adb devices` se vale também para depuração sem fio já autorizada.",
        ),
    )

    /** Lojas alternativas e instaladores de pacote, suspensos pela opção "lojas". A Play Store fica liberada. */
    val storePackages = listOf(
        "com.sec.android.app.samsungapps",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    const val TAILSCALE_PKG = "com.tailscale.ipn"
}

/** Estado desejado, persistido. O sistema guarda as restrições; isto permite reaplicar. */
class Config(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isRestrictionOn(spec: RestrictionSpec) = prefs.getBoolean("r_${spec.key}", spec.defaultOn)
    fun setRestriction(spec: RestrictionSpec, on: Boolean) = prefs.edit().putBoolean("r_${spec.key}", on).apply()

    /** Suspende lojas alternativas e instaladores de pacote (a Play Store não entra). */
    var suspendBlocked: Boolean
        get() = prefs.getBoolean("suspend_blocked", true)
        set(v) = prefs.edit().putBoolean("suspend_blocked", v).apply()

    /** Liga o filtro: Tailscale como VPN sempre ativa + apps marcados só funcionam com o filtro confirmado. */
    var dnsFilterEnabled: Boolean
        get() = prefs.getBoolean("dns_filter", true)
        set(v) = prefs.edit().putBoolean("dns_filter", v).apply()

    /** Apps marcados ficam suspensos enquanto o filtro (VPN + canário) não estiver confirmado. */
    var browsersNeedFilter: Boolean
        get() = prefs.getBoolean("browsers_need_filter", true)
        set(v) = prefs.edit().putBoolean("browsers_need_filter", v).apply()

    /** Domínio REAL que o Pi-hole mantém bloqueado; resolvido pela VPN para provar que o DNS passa pelo Pi-hole. */
    var canary: String
        get() = prefs.getString("canary", null).orEmpty()
        set(v) = prefs.edit().putString("canary", v.trim().lowercase()).apply()

    /** Apps (além dos navegadores) enviados ao Pi-hole pelo Tailscale. */
    var routedExtra: List<String>
        get() = csv(prefs.getString("routed_extra", null))
        set(v) = prefs.edit().putString("routed_extra", v.joinToString(",")).apply()

    /** Navegadores que o usuário tirou do Pi-hole (afrouxa a proteção; passa pelo atraso). */
    var browsersExempt: List<String>
        get() = csv(prefs.getString("browsers_exempt", null))
        set(v) = prefs.edit().putString("browsers_exempt", v.joinToString(",")).apply()

    /** Apps instalados depois da base aprovada ficam suspensos até a pessoa de confiança aprovar. */
    var newAppsDeny: Boolean
        get() = prefs.getBoolean("new_apps_deny", true)
        set(v) = prefs.edit().putBoolean("new_apps_deny", v).apply()

    /** Janelas de desenvolvimento (ADB curto) por 24 h. */
    var devWindowsPerDay: Int
        get() = prefs.getString("dev_windows_per_day", null)?.toIntOrNull() ?: 3
        set(v) = prefs.edit().putString("dev_windows_per_day", v.toString()).apply()

    var devWindowSeconds: Int
        get() = prefs.getString("dev_window_seconds", null)?.toIntOrNull() ?: 60
        set(v) = prefs.edit().putString("dev_window_seconds", v.toString()).apply()

    /** Reinicia o aparelho ao fim de cada janela de desenvolvimento (mata processos de shell). */
    var devReboot: Boolean
        get() = prefs.getBoolean("dev_reboot", true)
        set(v) = prefs.edit().putBoolean("dev_reboot", v).apply()

    /** Adia atualizações do Android (no máximo 30 dias; depois o sistema instala). */
    var postponeUpdates: Boolean
        get() = prefs.getBoolean("postpone_updates", false)
        set(v) = prefs.edit().putBoolean("postpone_updates", v).apply()

    /** Atraso (minutos) para qualquer desbloqueio. 0 desliga o atraso (só para testes). */
    var unlockDelayMin: Long
        get() = prefs.getString("unlock_delay_min", null)?.toLongOrNull() ?: DEFAULT_DELAY_MIN
        set(v) = prefs.edit().putString("unlock_delay_min", v.toString()).apply()

    /** Pacotes extras com desinstalação e parada/limpeza de dados bloqueadas (ex.: AppLock, Qustodio). */
    var protectedPackages: List<String>
        get() = csv(prefs.getString("protected_pkgs", null))
        set(v) = prefs.edit().putString("protected_pkgs", v.joinToString(",")).apply()

    /** Política gerenciada do Chrome: SafeSearch forçado, YouTube restrito, sem anônima. */
    var chromePolicy: Boolean
        get() = prefs.getBoolean("chrome_policy", true)
        set(v) = prefs.edit().putBoolean("chrome_policy", v).apply()

    /** Estado completo como mapa chave → texto (base do atraso de desbloqueio). */
    fun snapshot(): Map<String, String> = linkedMapOf<String, String>().also { m ->
        Catalog.restrictions.forEach { m["r_${it.key}"] = isRestrictionOn(it).toString() }
        m["suspend_blocked"] = suspendBlocked.toString()
        m["browsers_need_filter"] = browsersNeedFilter.toString()
        m["dns_filter"] = dnsFilterEnabled.toString()
        m["canary"] = canary
        m["routed_extra"] = routedExtra.joinToString(",")
        m["browsers_exempt"] = browsersExempt.joinToString(",")
        m["new_apps_deny"] = newAppsDeny.toString()
        m["dev_windows_per_day"] = devWindowsPerDay.toString()
        m["dev_window_seconds"] = devWindowSeconds.toString()
        m["dev_reboot"] = devReboot.toString()
        m["postpone_updates"] = postponeUpdates.toString()
        m["chrome_policy"] = chromePolicy.toString()
        m[Gate.KEY_DELAY] = unlockDelayMin.toString()
        m["protected_pkgs"] = protectedPackages.joinToString(",")
    }

    fun applySnapshot(values: Map<String, String>) {
        val edit = prefs.edit()
        for ((k, v) in values) if (k in STRING_KEYS) edit.putString(k, v) else edit.putBoolean(k, v == "true")
        edit.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_DELAY_MIN = 24L * 60
        private val STRING_KEYS = setOf(
            "canary", "routed_extra", "browsers_exempt", "dev_windows_per_day", "dev_window_seconds",
            Gate.KEY_DELAY, "protected_pkgs",
        )

        private fun csv(s: String?) = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
