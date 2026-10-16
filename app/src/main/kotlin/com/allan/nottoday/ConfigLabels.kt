package com.allan.nottoday

/** Nomes em português, grupos e dicas das chaves de [Config.snapshot]: usados na tela e enviados no relatório ao site. */
object ConfigLabels {
    const val G_FILTER = "Filtro"
    const val G_NEW_APPS = "Apps novos"
    const val G_SECURITY = "Segurança"
    const val G_RESTRICTIONS = "Restrições do sistema"
    const val G_DEV = "Desenvolvimento (ADB curto)"
    const val G_CHROME = "Chrome"
    const val G_STORES = "Lojas e apps protegidos"
    const val G_SYSTEM = "Sistema"

    /** Ordem em que os grupos aparecem. */
    val GROUPS = listOf(G_FILTER, G_NEW_APPS, G_SECURITY, G_RESTRICTIONS, G_DEV, G_CHROME, G_STORES, G_SYSTEM)

    private val FIXED = mapOf(
        "suspend_blocked" to ("Suspender lojas alternativas/instaladores" to G_STORES),
        "browsers_need_filter" to ("Apps marcados só com filtro confirmado" to G_FILTER),
        "dns_filter" to ("Filtro ativo" to G_FILTER),
        "canary" to ("Domínio-canário" to G_FILTER),
        "routed_extra" to ("Outros apps no filtro (pacotes)" to G_FILTER),
        "browsers_exempt" to ("Navegadores fora do filtro (pacotes)" to G_FILTER),
        "new_apps_deny" to ("Suspender apps novos até a aprovação" to G_NEW_APPS),
        "dev_windows_per_day" to ("Janelas de desenvolvimento por 24 h" to G_DEV),
        "dev_window_seconds" to ("Duração da janela de desenvolvimento (s)" to G_DEV),
        "dev_reboot" to ("Reiniciar ao fim da janela de desenvolvimento" to G_DEV),
        "store_extra" to ("Lojas extras (pacotes)" to G_STORES),
        "chrome_doh_off" to ("Chrome: DNS sobre HTTPS desligado" to G_CHROME),
        "chrome_safesearch" to ("Chrome: SafeSearch" to G_CHROME),
        "chrome_youtube" to ("Chrome: YouTube restrito (0 desligado, 1 moderado, 2 estrito)" to G_CHROME),
        "chrome_incognito_block" to ("Chrome: bloquear modo anônimo" to G_CHROME),
        "chrome_block_ip" to ("Chrome: bloquear acesso por endereço IP" to G_CHROME),
        "protected_pkgs" to ("Apps protegidos (pacotes)" to G_STORES),
        "postpone_updates" to ("Adiar atualizações do Android (até 30 dias)" to G_SYSTEM),
        Gate.KEY_DELAY to ("Atraso de desbloqueio (minutos)" to G_SECURITY),
    )

    private val HINTS = mapOf(
        "canary" to "Trocar o canário espera o atraso.",
        "routed_extra" to "Apps, além dos navegadores, que passam pelo servidor DNS filtrante. O pacote é o nome técnico do app (ex.: com.nvt.cs).",
        "browsers_exempt" to "Navegadores que NÃO passam pelo filtro. Isentar um navegador espera o atraso.",
        "protected_pkgs" to "Apps que ninguém desinstala nem para à força sem passar pelo atraso.",
        "store_extra" to "Outras lojas de apps a suspender, além das que o NotToday já conhece.",
        "browsers_need_filter" to "Sem VPN ou com o canário resolvendo, os apps marcados são suspensos.",
        "new_apps_deny" to "Instalar é livre; o app novo fica suspenso até a aprovação.",
        Gate.KEY_DELAY to "Aumentar vale na hora; diminuir espera o atraso atual.",
        "chrome_doh_off" to "Com DoH o Chrome ignora o servidor DNS filtrante.",
        "chrome_block_ip" to "Site digitado como número (IP) não passa pelo filtro de DNS. Só vale no Chrome; apps seguem livres.",
    )

    private fun restriction(key: String) = Catalog.restrictions.firstOrNull { "r_${it.key}" == key }

    fun label(key: String): String = restriction(key)?.label ?: FIXED[key]?.first ?: key

    fun group(key: String): String =
        if (key.startsWith("r_")) G_RESTRICTIONS else FIXED[key]?.second ?: G_SYSTEM

    fun hint(key: String): String? = HINTS[key] ?: restriction(key)?.warning

    /** Rótulos, grupos e dicas das chaves dadas (a mesma forma que o relatório ao site usa). */
    fun describe(keys: Collection<String>): Map<String, Map<String, String>> = mapOf(
        "labels" to keys.associateWith { label(it) },
        "groups" to keys.associateWith { group(it) },
        "hints" to keys.mapNotNull { k -> hint(k)?.let { k to it.take(200) } }.toMap(),
    )
}
