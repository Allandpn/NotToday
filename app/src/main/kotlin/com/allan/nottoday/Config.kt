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
        RestrictionSpec(UserManager.DISALLOW_CONFIG_TETHERING, "Bloquear hotspot/tethering", false),
        RestrictionSpec(UserManager.DISALLOW_WIFI_TETHERING, "Bloquear hotspot Wi-Fi", false),
        RestrictionSpec(UserManager.DISALLOW_WIFI_DIRECT, "Bloquear Wi-Fi Direct", false),
        RestrictionSpec(UserManager.DISALLOW_NETWORK_RESET, "Bloquear reset de rede", true),
        RestrictionSpec(
            UserManager.DISALLOW_USB_FILE_TRANSFER, "Bloquear transferência de arquivos por USB", false,
            "Não afeta o ADB. A janela de manutenção libera temporariamente.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_UNINSTALL_APPS, "Bloquear desinstalar qualquer app", false,
            "O NotToday, o Tailscale e os \"apps protegidos\" já ficam sem desinstalar, parar nem limpar dados, com esta opção desligada.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_APPS_CONTROL, "Bloquear forçar parada/limpar dados de qualquer app", false,
            "Mesma observação: os apps essenciais já têm proteção própria.",
        ),
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
            "DNS privado fixo ignoraria o DNS do Tailscale (o servidor filtrante).",
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
            UserManager.DISALLOW_FACTORY_RESET, "Bloquear reset de fábrica (Configurações)", true,
            "É o jeito mais rápido de apagar o NotToday. Não impede reset pelo recovery. Desligue se quiser ter essa saída se o PIN se perder.",
        ),
        RestrictionSpec(
            UserManager.DISALLOW_DEBUGGING_FEATURES, "Bloquear depuração USB/ADB (fecha o shell)", false,
            "Ative por último. Sem ADB, só a janela de desenvolvimento (PIN de dev) ou a de manutenção (PIN + espera) " +
                "o reabre. Confira com `adb devices` se vale também para depuração sem fio já autorizada.",
        ),
    )

    /**
     * Lojas alternativas e instaladores de pacote conhecidos, suspensos pela opção "lojas" (desligada por padrão: a
     * regra de apps novos já cobre). A Play Store fica liberada. Cada um pode acrescentar os seus em "lojas extras".
     */
    val storePackages = listOf(
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.xiaomi.mipicks", // Xiaomi GetApps
        "com.huawei.appmarket", // AppGallery
        "com.amazon.venezia", // Amazon Appstore
        "org.fdroid.fdroid", // F-Droid
        "com.aurora.store",
        "cm.aptoide.pt",
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
        get() = prefs.getBoolean("suspend_blocked", false)
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

    /** Pacotes de lojas além da lista conhecida (suspensos junto com ela quando "lojas" está ligado). */
    var extraStores: List<String>
        get() = csv(prefs.getString("store_extra", null))
        set(v) = prefs.edit().putString("store_extra", v.joinToString(",")).apply()

    // Política gerenciada do Chrome (melhor esforço; cada item é independente).

    /** Desliga o DNS-sobre-HTTPS do Chrome (só enquanto o filtro está ativo): senão ele ignora o DNS filtrante. */
    var chromeDohOff: Boolean
        get() = prefs.getBoolean("chrome_doh_off", true)
        set(v) = prefs.edit().putBoolean("chrome_doh_off", v).apply()

    var chromeSafeSearch: Boolean
        get() = prefs.getBoolean("chrome_safesearch", true)
        set(v) = prefs.edit().putBoolean("chrome_safesearch", v).apply()

    /** Modo restrito do YouTube no Chrome: 0 desligado, 1 moderado, 2 estrito. */
    var chromeYoutube: Int
        get() = prefs.getString("chrome_youtube", null)?.toIntOrNull()?.coerceIn(0, 2) ?: 1
        set(v) = prefs.edit().putString("chrome_youtube", v.coerceIn(0, 2).toString()).apply()

    /** Desativa o modo anônimo do Chrome (só esconde histórico; não escapa do DNS filtrante). */
    var chromeIncognitoBlock: Boolean
        get() = prefs.getBoolean("chrome_incognito_block", false)
        set(v) = prefs.edit().putBoolean("chrome_incognito_block", v).apply()

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
        m["store_extra"] = extraStores.joinToString(",")
        m["chrome_doh_off"] = chromeDohOff.toString()
        m["chrome_safesearch"] = chromeSafeSearch.toString()
        m["chrome_youtube"] = chromeYoutube.toString()
        m["chrome_incognito_block"] = chromeIncognitoBlock.toString()
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
            Gate.KEY_DELAY, "protected_pkgs", "store_extra", "chrome_youtube",
        )

        private val DOMAIN = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$")
        private val PKG = Regex("^[A-Za-z0-9._]{1,200}$")

        /**
         * Valida e normaliza um valor vindo de fora (canal remoto) para a chave `key` do [snapshot].
         * Devolve null se não serve. Rigoroso de propósito: [applySnapshot] grava qualquer texto não reconhecido
         * como `false`, o que afrouxaria a proteção sem passar pelo atraso.
         */
        fun normalize(key: String, value: String): String? {
            if (value.length > 2000 || value.any { it == '\n' || it == '\r' || it == '\t' }) return null
            return when (key) {
                "dev_windows_per_day" -> value.toIntOrNull()?.takeIf { it in 0..20 }?.toString()
                "dev_window_seconds" -> value.toIntOrNull()?.takeIf { it in 10..600 }?.toString()
                Gate.KEY_DELAY -> value.toLongOrNull()?.takeIf { it in 0..(24L * 14 * 60) }?.toString()
                "chrome_youtube" -> value.toIntOrNull()?.takeIf { it in 0..2 }?.toString()
                "canary" -> value.trim().lowercase().takeIf { it.isEmpty() || DOMAIN.matches(it) }
                "routed_extra", "browsers_exempt", "protected_pkgs", "store_extra" -> {
                    val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.all { PKG.matches(it) }) parts.joinToString(",") else null
                }
                else -> if (value == "true" || value == "false") value else null
            }
        }

        private fun csv(s: String?) = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
