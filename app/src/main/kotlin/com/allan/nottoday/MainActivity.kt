package com.allan.nottoday

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Uma Activity, várias telas montadas em código: definir PIN → desbloquear → configurar.
 * A sessão desbloqueada vive só em memória e é descartada quando o app sai de cena.
 */
class MainActivity : Activity() {
    private lateinit var pins: PinStore
    private lateinit var devPins: PinStore
    private lateinit var config: Config
    private lateinit var policy: PolicyManager
    private lateinit var pendings: PendingStore
    private lateinit var maintenance: Maintenance
    private lateinit var devWindow: DevWindow
    private lateinit var approved: Approved
    private lateinit var content: LinearLayout
    private var unlocked = false
    private var changingPin = false

    private val logView by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pins = PinStore(this)
        devPins = PinStore(this, PolicyManager.DEV_PIN)
        config = Config(this)
        policy = PolicyManager(this)
        pendings = PendingStore(this)
        maintenance = Maintenance(this)
        devWindow = DevWindow(this)
        approved = Approved(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val root = ScrollView(this).apply { addView(content) }
        // targetSdk 36 desenha edge-to-edge: afastar o conteúdo das barras do sistema.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        // Reaplicar ao abrir, como segunda rede de segurança além do boot.
        if (policy.isDeviceOwner()) runAsync { policy.apply() }
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    override fun onStop() {
        super.onStop()
        unlocked = false
    }

    /** Nunca deixa uma falha de tela impedir o PIN: se algo quebrar, mostra o erro e volta ao bloqueio. */
    private fun render() {
        content.removeAllViews()
        try {
            val owner = policy.isDeviceOwner()
            when {
                !pins.isSet && owner && !pins.firstPinAllowedAsOwner -> renderBroken()
                !pins.isSet || changingPin -> renderSetPin()
                !unlocked -> renderUnlock(owner)
                else -> renderSettings(owner)
            }
        } catch (e: Exception) {
            content.removeAllViews()
            unlocked = false
            title("Erro na tela")
            text("${e.javaClass.simpleName}: ${e.message}")
            button("Tentar de novo") { render() }
        }
    }

    // --- PIN -----------------------------------------------------------------

    private fun renderBroken() {
        title("PIN ausente")
        text(
            "Este aparelho está sob Device Owner, mas não há PIN (os dados do app foram apagados?). " +
                "Por segurança nenhum PIN novo pode ser criado e as restrições continuam valendo. " +
                "A saída é a pessoa de confiança autorizar um reset de fábrica.",
        )
    }

    private fun renderSetPin() {
        title(if (changingPin) "Trocar PIN" else "Definir PIN")
        text("A pessoa de confiança deve digitar o PIN agora e guardá-lo. Mínimo ${PinStore.MIN_LENGTH} dígitos.")
        val pin = pinField("Novo PIN")
        val again = pinField("Repita o PIN")
        var delayField: EditText? = null
        if (!changingPin) {
            text("Atraso para qualquer desbloqueio, em horas (0 = sem atraso, só para testes). Padrão: 24.")
            delayField = EditText(this).apply {
                setText((Config.DEFAULT_DELAY_MIN / 60).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }.also { content.addView(it) }
        }
        button("Salvar PIN") {
            val p = pin.text.toString()
            when {
                p.length < PinStore.MIN_LENGTH -> toast("Mínimo ${PinStore.MIN_LENGTH} dígitos")
                p != again.text.toString() -> toast("Os PINs não conferem")
                else -> {
                    pins.set(p)
                    delayField?.text?.toString()?.toLongOrNull()?.coerceIn(0, 24 * 14)?.let { config.unlockDelayMin = it * 60 }
                    changingPin = false
                    unlocked = true
                    render()
                }
            }
        }
        if (changingPin) button("Cancelar") {
            changingPin = false
            render()
        }
    }

    private fun renderUnlock(owner: Boolean) {
        title("Bloqueado")
        val locked = pins.lockedForMs()
        if (locked > 0) {
            text("Muitas tentativas. Tente de novo em ${(locked / 60_000) + 1} min.")
            button("Atualizar") { render() }
        } else {
            val pin = pinField("PIN")
            button("Entrar") {
                if (pins.verify(pin.text.toString())) unlocked = true else toast("PIN incorreto")
                render()
            }
        }
        if (owner) renderDevWindowLogin()
    }

    /** Janela de desenvolvimento: com o PIN de desenvolvedor (não é o da pessoa de confiança) abre só o ADB. */
    private fun renderDevWindowLogin() {
        val debugSpec = Catalog.restrictions.first { it.key == UserManager.DISALLOW_DEBUGGING_FEATURES }
        if (!devPins.isSet || !config.isRestrictionOn(debugSpec)) return
        section("Janela de desenvolvimento")
        val left = devWindow.remainingMs()
        if (left > 0) {
            text("ADB aberto por mais ${left / 1000 + 1} s.")
            button("Atualizar") { render() }
            return
        }
        text(
            "Usadas nas últimas 24 h: ${devWindow.usedInLast24h()} de ${config.devWindowsPerDay}. " +
                "Cada uma dura ${config.devWindowSeconds} s." +
                if (config.devReboot) " O aparelho reinicia ao fim." else "",
        )
        val locked = devPins.lockedForMs()
        if (locked > 0) {
            text("Muitas tentativas. Tente de novo em ${(locked / 60_000) + 1} min.")
            return
        }
        val pin = pinField("PIN de desenvolvedor")
        button("Abrir janela") {
            when {
                !devPins.verify(pin.text.toString()) -> toast("PIN incorreto")
                !devWindow.canStart(config) -> toast("Sem janelas disponíveis agora")
                else -> {
                    devWindow.start(config.devWindowSeconds)
                    runAsync { policy.apply() }
                    toast("ADB aberto por ${config.devWindowSeconds} s")
                }
            }
            render()
        }
    }

    // --- Configuração --------------------------------------------------------

    private fun renderSettings(owner: Boolean) {
        title("NotToday")
        text(
            if (owner) "Device Owner: ATIVO"
            else "Device Owner: inativo. Rode no PC:\nadb shell dpm set-device-owner $packageName/.AdminReceiver",
        )
        renderStatus(owner)

        val gateOn = owner && config.unlockDelayMin > 0
        renderMaintenance(owner)
        renderPending(owner)

        section("Restrições")
        val switches = Catalog.restrictions.map { spec ->
            spec to switchRow(spec.label, config.isRestrictionOn(spec), spec.warning)
        }

        section("Lojas")
        val suspend = switchRow(
            "Suspender lojas alternativas e instaladores de pacote", config.suspendBlocked,
            "Galaxy Store, GetApps, AppGallery, F-Droid etc. A Play Store continua liberada. Com \"Suspender apps novos\" " +
                "ligado, esta opção é redundante (o app novo já fica suspenso).",
        )
        text("Lojas extras (pacotes separados por vírgula), suspensas junto com as acima.")
        val storeExtra = EditText(this).apply {
            setText(config.extraStores.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(storeExtra)

        section("Filtro (DNS pelo Tailscale)")
        text(
            "O Tailscale vira a VPN sempre ativa e leva ao seu servidor DNS filtrante (Pi-hole, AdGuard Home etc., " +
                "definido como DNS da tailnet) só os apps marcados. Instale o Tailscale e faça " +
                "login ANTES de ativar o Device Owner. Você liga e desliga o Tailscale no app dele.",
        )
        val filterOn = switchRow("Filtro ativo (Tailscale sempre ativo)", config.dnsFilterEnabled)
        val needFilter = switchRow(
            "Apps marcados só funcionam com o filtro confirmado", config.browsersNeedFilter,
            "Sem VPN ou com o canário resolvendo, os apps marcados são suspensos; os demais seguem com internet.",
        )
        text("Domínio-canário: um domínio REAL (existente) que o seu servidor DNS mantém bloqueado. Sem ele, os apps marcados ficam suspensos.")
        val canary = EditText(this).apply {
            setText(config.canary)
            hint = "ex.: canario.exemplo.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(canary)

        var extraDraft = config.routedExtra.toSet()
        var exemptDraft = config.browsersExempt.toSet()
        val routedSummary = TextView(this)
        fun refreshSummary() {
            val browsers = Browsers.installed(this) - exemptDraft
            routedSummary.text = "No filtro: ${(browsers + extraDraft).distinct().joinToString { PackageGuard.label(this, it) }.ifEmpty { "nenhum app" }}"
        }
        refreshSummary()
        content.addView(routedSummary)
        button("Escolher apps que passam pelo filtro") {
            pickApps(extraDraft, exemptDraft) { extra, exempt ->
                extraDraft = extra
                exemptDraft = exempt
                refreshSummary()
            }
        }

        section("Apps novos (Play Store)")
        val newDeny = switchRow(
            "Suspender apps novos até a aprovação", config.newAppsDeny,
            "Instalar é livre; o app novo é suspenso logo em seguida e só abre depois que a pessoa de confiança aprova aqui. " +
                "Navegadores novos entram sozinhos no filtro.",
        )
        renderApprovals(owner)

        section("Desenvolvimento (ADB curto)")
        text(
            "O PIN de desenvolvedor abre só o ADB, por poucos segundos e poucas vezes ao dia, na tela de bloqueio. " +
                "Só funciona com a depuração bloqueada acima. Ajustes de limite que aumentam o acesso esperam o atraso.",
        )
        button(if (devPins.isSet) "Trocar PIN de desenvolvedor" else "Definir PIN de desenvolvedor") { promptDevPin() }
        text("Janelas por 24 h:")
        val devPerDay = EditText(this).apply {
            setText(config.devWindowsPerDay.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(devPerDay)
        text("Duração de cada janela (segundos):")
        val devSeconds = EditText(this).apply {
            setText(config.devWindowSeconds.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(devSeconds)
        val devReboot = switchRow(
            "Reiniciar o aparelho ao fim da janela", config.devReboot,
            "Mata processos de shell iniciados durante a janela (estilo Shizuku).",
        )

        section("Chrome (configuração gerenciada)")
        text("Melhor esforço, confira em chrome://policy. Não vale para outros navegadores.")
        val chromeDoh = switchRow(
            "Desligar DNS sobre HTTPS", config.chromeDohOff,
            "Recomendado: com DoH o Chrome ignora o servidor DNS filtrante. Só vale com o filtro ativo.",
        )
        val chromeSafe = switchRow("Forçar SafeSearch do Google", config.chromeSafeSearch)
        text("YouTube restrito no Chrome:")
        val chromeYt = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Desligado", "Moderado", "Estrito"),
            )
            setSelection(config.chromeYoutube)
        }
        content.addView(chromeYt)
        val chromeIncog = switchRow(
            "Bloquear modo anônimo", config.chromeIncognitoBlock,
            "Opcional: o anônimo não escapa do DNS filtrante, só esconde o histórico.",
        )

        section("Sistema")
        val postpone = switchRow(
            "Adiar atualizações do Android (até 30 dias)", config.postponeUpdates,
            "Dá tempo de testar o app numa versão nova. Passados 30 dias o sistema instala.",
        )
        text("Apps protegidos extras (pacotes separados por vírgula; ex.: AppLock). Confira com `pm list packages`.")
        val protectedPkgs = EditText(this).apply {
            setText(config.protectedPackages.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(protectedPkgs)
        section("Segurança")
        text("Atraso para desbloqueios, em horas. Aumentar vale na hora; diminuir também espera o atraso atual.")
        val delay = EditText(this).apply {
            setText((config.unlockDelayMin / 60).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(delay)

        button("Salvar e aplicar") {
            val old = config.snapshot()
            val new = LinkedHashMap(old)
            switches.forEach { (spec, sw) -> new["r_${spec.key}"] = sw.isChecked.toString() }
            new["suspend_blocked"] = suspend.isChecked.toString()
            new["dns_filter"] = filterOn.isChecked.toString()
            new["browsers_need_filter"] = needFilter.isChecked.toString()
            new["canary"] = canary.text.toString().trim().lowercase()
            new["routed_extra"] = extraDraft.sorted().joinToString(",")
            new["browsers_exempt"] = exemptDraft.sorted().joinToString(",")
            new["new_apps_deny"] = newDeny.isChecked.toString()
            new["dev_windows_per_day"] = (devPerDay.text.toString().toIntOrNull() ?: config.devWindowsPerDay).coerceIn(0, 20).toString()
            new["dev_window_seconds"] = (devSeconds.text.toString().toIntOrNull() ?: config.devWindowSeconds).coerceIn(10, 600).toString()
            new["dev_reboot"] = devReboot.isChecked.toString()
            new["postpone_updates"] = postpone.isChecked.toString()
            new["chrome_doh_off"] = chromeDoh.isChecked.toString()
            new["chrome_safesearch"] = chromeSafe.isChecked.toString()
            new["chrome_youtube"] = chromeYt.selectedItemPosition.toString()
            new["chrome_incognito_block"] = chromeIncog.isChecked.toString()
            new["store_extra"] = storeExtra.text.toString().split(',', ' ').map { it.trim() }
                .filter { it.isNotEmpty() }.joinToString(",")
            new["protected_pkgs"] = protectedPkgs.text.toString().split(',', ' ').map { it.trim() }
                .filter { it.isNotEmpty() }.joinToString(",")
            delay.text.toString().toLongOrNull()?.coerceIn(0, 24 * 14)?.let { new[Gate.KEY_DELAY] = (it * 60).toString() }

            // Endurecer vale na hora; afrouxar vira um pedido que só pode ser confirmado depois do atraso.
            val split = if (gateOn) Gate.split(old, new) else Gate.Split(new.filter { old[it.key] != it.value }, emptyMap())
            config.applySnapshot(split.immediate)
            var note = ""
            if (split.delayed.isNotEmpty()) {
                pendings.put(Pending.Kind.SETTINGS, split.delayed, config.unlockDelayMin * 60_000L)
                note = "${split.delayed.size} alteração(ões) que afrouxam a proteção aguardam ${formatDuration(config.unlockDelayMin * 60_000L)}.\n"
            }
            runAndShow("Aplicando…", note) { policy.apply() }
        }
        button("Trocar PIN") {
            changingPin = true
            render()
        }

        section("Zona de perigo")
        if (owner) button(if (gateOn) "Pedir remoção do Device Owner" else "Remover Device Owner") {
            if (gateOn) {
                pendings.put(Pending.Kind.RELEASE, emptyMap(), config.unlockDelayMin * 60_000L)
                render()
            } else {
                confirmRelease()
            }
        }
    }

    /** Estado do filtro: VPN, canário e erro recente. */
    private fun renderStatus(owner: Boolean) {
        if (!owner) return
        val vpn = VpnWatch.isVpnUp(this)
        val active = policy.filterActive()
        text(
            "Filtro: ${if (!config.dnsFilterEnabled) "desligado" else if (active) "CONFIRMADO" else "NÃO confirmado"}\n" +
                "VPN: ${if (vpn) "de pé" else "fora"} · ${Health.lastDetail}" +
                (Diag.lastError?.let { "\nErro: $it" } ?: ""),
        )
        button("Atualizar estado") {
            runAsync {
                policy.heartbeat()
                runOnUiThread { render() }
            }
        }
    }

    /** Escolha dos apps que passam pelo filtro. Navegadores vêm marcados; desmarcar um navegador o isenta. */
    private fun pickApps(extra: Set<String>, exempt: Set<String>, onDone: (Set<String>, Set<String>) -> Unit) {
        val browsers = Browsers.installed(this).toSet()
        val candidates = (PackageGuard.launcherApps(this) + browsers)
            .filter { it != packageName && it != Catalog.TAILSCALE_PKG }
            .distinct()
            .sortedBy { PackageGuard.label(this, it).lowercase() }
        val checked = BooleanArray(candidates.size) { i ->
            val p = candidates[i]
            if (p in browsers) p !in exempt else p in extra
        }
        val names = candidates.map { p -> PackageGuard.label(this, p) + if (p in browsers) "  [navegador]" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Apps que passam pelo filtro")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                val newExtra = candidates.indices.filter { candidates[it] !in browsers && checked[it] }.map { candidates[it] }.toSet()
                val newExempt = candidates.indices.filter { candidates[it] in browsers && !checked[it] }.map { candidates[it] }.toSet()
                onDone(newExtra, newExempt)
            }
            .show()
    }

    // --- Aprovação de apps novos -----------------------------------------------------

    private fun renderApprovals(owner: Boolean) {
        if (!approved.baselineDone) {
            text(
                "Base de apps NÃO definida: nenhum app novo é bloqueado ainda. Revise os apps instalados com a pessoa de " +
                    "confiança e aprove-os como base (apps do sistema não entram).",
            )
            button("Aprovar apps instalados agora (base)") {
                approved.addAll(PackageGuard.userApps(this) + Catalog.TAILSCALE_PKG)
                approved.baselineDone = true
                runAsync { policy.quickSync() }
                render()
            }
            return
        }
        val pending = PackageGuard.pending(this)
        text(if (pending.isEmpty()) "Nenhum app aguardando aprovação." else "Aguardando aprovação (suspensos):")
        pending.forEach { pkg ->
            val risks = PackageGuard.risks(this, pkg)
            button("Aprovar: ${PackageGuard.label(this, pkg)}" + if (risks.isEmpty()) "" else "  ⚠ ${risks.joinToString(", ")}") {
                AlertDialog.Builder(this)
                    .setTitle("Aprovar ${PackageGuard.label(this, pkg)}?")
                    .setMessage("$pkg" + if (risks.isEmpty()) "" else "\n\nAtenção: ${risks.joinToString(", ")}")
                    .setNegativeButton("Não", null)
                    .setPositiveButton("Aprovar") { _, _ ->
                        approved.addAll(listOf(pkg))
                        runAsync { policy.quickSync() }
                        render()
                    }
                    .show()
            }
        }
        button("Ver/revogar apps aprovados (${approved.all().size})") { showApproved() }
        if (owner) text("Aprovar vale só com o PIN, sem atraso: confira o app antes.")
    }

    private fun showApproved() {
        val pkgs = approved.all().filter { it != Catalog.TAILSCALE_PKG }.sortedBy { PackageGuard.label(this, it).lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Toque para revogar (o app será suspenso)")
            .setItems(pkgs.map { "${PackageGuard.label(this, it)} ($it)" }.toTypedArray()) { _, which ->
                approved.remove(pkgs[which])
                runAsync { policy.quickSync() }
                render()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // --- PIN de desenvolvedor ----------------------------------------------------------

    private fun promptDevPin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        fun field(h: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            box.addView(this)
        }
        val a = field("PIN de desenvolvedor")
        val b = field("Repita")
        AlertDialog.Builder(this)
            .setTitle("PIN de desenvolvedor")
            .setMessage("Mínimo ${PinStore.MIN_LENGTH} dígitos. Diferente do PIN da pessoa de confiança.")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val p = a.text.toString()
                when {
                    p.length < PinStore.MIN_LENGTH -> toast("Mínimo ${PinStore.MIN_LENGTH} dígitos")
                    p != b.text.toString() -> toast("Os PINs não conferem")
                    pins.verify(p) -> toast("Use um PIN diferente do principal")
                    else -> {
                        devPins.set(p)
                        toast("PIN de desenvolvedor salvo")
                        render()
                    }
                }
            }
            .show()
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remover Device Owner?")
            .setMessage("Todas as restrições serão desfeitas e os PINs apagados.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                pendings.clear(Pending.Kind.RELEASE)
                // O PIN é apagado na remoção: ao sair de cena a tela volta a pedir um PIN novo.
                runAndShow("Removendo…") { policy.releaseDeviceOwner().also { unlocked = false } }
            }
            .show()
    }

    // --- Atraso, pedidos e janela de manutenção ------------------------------------

    private fun formatDuration(ms: Long): String {
        val min = (ms + 59_999) / 60_000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    private fun labelFor(key: String): String =
        Catalog.restrictions.firstOrNull { "r_${it.key}" == key }?.label ?: when (key) {
            "suspend_blocked" -> "Suspender lojas alternativas/instaladores"
            "browsers_need_filter" -> "Apps marcados só com filtro confirmado"
            "dns_filter" -> "Filtro ativo"
            "canary" -> "Domínio-canário"
            "routed_extra" -> "Apps no filtro"
            "browsers_exempt" -> "Navegadores fora do filtro"
            "new_apps_deny" -> "Suspender apps novos"
            "dev_windows_per_day" -> "Janelas de desenvolvimento por 24 h"
            "dev_window_seconds" -> "Duração da janela de desenvolvimento (s)"
            "dev_reboot" -> "Reiniciar ao fim da janela de desenvolvimento"
            "store_extra" -> "Lojas extras"
            "chrome_doh_off" -> "Chrome: DNS sobre HTTPS desligado"
            "chrome_safesearch" -> "Chrome: SafeSearch"
            "chrome_youtube" -> "Chrome: YouTube restrito (0 desligado, 1 moderado, 2 estrito)"
            "chrome_incognito_block" -> "Chrome: bloquear modo anônimo"
            "protected_pkgs" -> "Apps protegidos"
            Gate.KEY_DELAY -> "Atraso de desbloqueio (min)"
            else -> key
        }

    private fun renderMaintenance(owner: Boolean) {
        val left = maintenance.remainingMs()
        if (owner && left > 0) {
            section("Janela de manutenção ATIVA")
            text("Termina em ${formatDuration(left)}. ADB, instalação de apps, USB e lojas estão liberados.")
            button("Encerrar agora") {
                maintenance.stop()
                runAndShow("Reaplicando…") { policy.apply() }
            }
        }
    }

    private fun renderPending(owner: Boolean) {
        if (!owner) return
        val clock = Clocks.now(this)
        for (kind in Pending.Kind.entries) {
            val p = pendings.get(kind) ?: continue
            val status = p.status(clock)
            if (status is Pending.Status.Expired) {
                pendings.clear(kind)
                continue
            }
            section(
                when (kind) {
                    Pending.Kind.SETTINGS -> "Pedido: afrouxar configurações"
                    Pending.Kind.RELEASE -> "Pedido: remover Device Owner"
                    Pending.Kind.MAINTENANCE -> "Pedido: janela de manutenção"
                },
            )
            if (kind == Pending.Kind.SETTINGS) {
                val current = config.snapshot()
                text(p.payload.entries.joinToString("\n") { (k, v) -> "• ${labelFor(k)}: ${current[k]} → $v" })
            }
            when (status) {
                is Pending.Status.Waiting -> text("Disponível em ${formatDuration(status.remainingMs)}.")
                is Pending.Status.Ready -> {
                    text("Pronto. Vale por mais ${formatDuration(status.expiresInMs)}.")
                    button(
                        when (kind) {
                            Pending.Kind.SETTINGS -> "Confirmar alterações"
                            Pending.Kind.RELEASE -> "Confirmar remoção do Device Owner"
                            Pending.Kind.MAINTENANCE -> "Iniciar janela de ${Maintenance.DEFAULT_MINUTES} min"
                        },
                    ) { confirmPending(p) }
                }
                Pending.Status.Expired -> {}
            }
            button("Cancelar pedido") {
                pendings.clear(kind)
                render()
            }
        }
        if (maintenance.remainingMs() == 0L && pendings.get(Pending.Kind.MAINTENANCE) == null) {
            button("Pedir janela de manutenção (${Maintenance.DEFAULT_MINUTES} min)") {
                if (config.unlockDelayMin > 0) {
                    pendings.put(Pending.Kind.MAINTENANCE, emptyMap(), config.unlockDelayMin * 60_000L)
                    render()
                } else {
                    maintenance.start(Maintenance.DEFAULT_MINUTES)
                    runAndShow("Abrindo janela…") { policy.apply() }
                }
            }
        }
    }

    private fun confirmPending(p: Pending) {
        when (p.kind) {
            Pending.Kind.SETTINGS -> {
                config.applySnapshot(p.payload)
                pendings.clear(p.kind)
                runAndShow("Aplicando…") { policy.apply() }
            }
            Pending.Kind.MAINTENANCE -> {
                pendings.clear(p.kind)
                maintenance.start(Maintenance.DEFAULT_MINUTES)
                runAndShow("Abrindo janela…") { policy.apply() }
            }
            Pending.Kind.RELEASE -> confirmRelease()
        }
    }

    private fun runAndShow(progress: String, prefix: String = "", work: () -> List<String>) {
        logView.text = prefix + progress
        if (logView.parent == null) content.addView(logView)
        runAsync {
            val result = prefix + work().joinToString("\n")
            runOnUiThread {
                logView.text = result
                toast("Concluído")
            }
        }
    }

    // --- Helpers de UI ---------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 24f
        setPadding(0, 0, 0, dp(8))
    })

    private fun section(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 16f
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun text(s: String) = content.addView(TextView(this).apply { text = s })

    private fun button(label: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    })

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        content.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun switchRow(label: String, checked: Boolean, hint: String? = null): Switch {
        val sw = Switch(this).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(sw)
        if (hint != null) content.addView(TextView(this).apply {
            text = hint
            textSize = 12f
        })
        return sw
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
