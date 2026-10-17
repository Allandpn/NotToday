package com.allan.nottoday

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Typeface
import android.os.UserManager
import android.text.InputType
import android.view.View
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
import com.allan.nottoday.remote.BlockedItem
import com.allan.nottoday.remote.CommandException
import com.allan.nottoday.remote.AppAsk
import com.allan.nottoday.remote.Cleared
import com.allan.nottoday.remote.UnblockApi
import com.allan.nottoday.remote.UnblockRequest
import com.allan.nottoday.remote.UnblockText
import com.allan.nottoday.remote.Pairing
import com.allan.nottoday.remote.RemoteChannel
import com.allan.nottoday.remote.RemoteStore
import com.allan.nottoday.remote.GatewayApi
import com.allan.nottoday.remote.DeviceKey
import com.allan.nottoday.remote.DevAuth

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
    private lateinit var remote: RemoteStore
    private lateinit var content: SettingsPage
    private var unlocked = false
    private var changingPin = false
    private var pairing = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdownView: TextView? = null
    private var countdownRunner: Runnable? = null
    private var showBlocked = false
    private var blockedTab = "domains"  // Liberações: "domains" | "apps" | "requests"
    private var barMode: String? = null  // barra de pedido: "sites" | "apps" | null
    private lateinit var requestBarView: LinearLayout
    private val requestLabel by lazy { TextView(this) }
    private val pickedApps = linkedSetOf<String>()
    private var showAds = false
    private val picked = linkedSetOf<String>()
    private var page: String? = null                 // subtela das configurações (null = início)
    private var draft: MutableMap<String, String>? = null  // alterações ainda não salvas
    private lateinit var saveBarView: LinearLayout
    private val saveLabel by lazy { TextView(this) }

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
        remote = RemoteStore(this)

        content = SettingsPage(this).apply { setPadding(dp(16), dp(8), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { addView(content) }
        saveBarView = LinearLayout(this).apply {
            tag = "savebar"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.nt_surface2))
            setPadding(dp(20), dp(10), dp(12), dp(10))
            visibility = View.GONE
            addView(saveLabel.apply { textSize = 15f; setTextColor(getColor(R.color.nt_text)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Descartar"
                isAllCaps = false
                setTextColor(getColor(R.color.nt_text))
                setBackgroundResource(R.drawable.btn_secondary)
                setOnClickListener { draft = null; render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
            addView(Button(this@MainActivity).apply {
                text = "Salvar e aplicar"
                isAllCaps = false
                setTextColor(getColor(R.color.nt_ink))
                setBackgroundResource(R.drawable.btn_primary)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { saveDraft(policy.isDeviceOwner()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        requestBarView = LinearLayout(this).apply {
            tag = "requestbar"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.nt_surface2))
            setPadding(dp(20), dp(10), dp(12), dp(10))
            visibility = View.GONE
            addView(requestLabel.apply { textSize = 15f; setTextColor(getColor(R.color.nt_text)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Limpar"
                isAllCaps = false
                setTextColor(getColor(R.color.nt_text))
                setBackgroundResource(R.drawable.btn_secondary)
                setOnClickListener { picked.clear(); pickedApps.clear(); render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
            addView(Button(this@MainActivity).apply {
                text = "Pedir liberação"
                isAllCaps = false
                setTextColor(getColor(R.color.nt_ink))
                setBackgroundResource(R.drawable.btn_primary)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { sendPicked() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.nt_bg))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(requestBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(saveBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
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
        // Antes do Device Owner o canal só roda com a tela aberta (o serviço de guarda o assume depois).
        if (remote.isPaired) RemoteChannel.start(applicationContext)
        render()
    }

    override fun onStop() {
        super.onStop()
        stopCountdown()
        unlocked = false
        showBlocked = false
        page = null
        draft = null
        if (!policy.isDeviceOwner()) RemoteChannel.stop()
    }

    /** Nunca deixa uma falha de tela impedir o PIN: se algo quebrar, mostra o erro e volta ao bloqueio. */
    private fun render() {
        stopCountdown()
        content.removeAllViews()
        (content.parent as? ScrollView)?.scrollTo(0, 0)
        barMode = null
        syncBack()
        try {
            val owner = policy.isDeviceOwner()
            val paired = remote.isPaired
            when {
                !pins.isSet && owner && !pins.firstPinAllowedAsOwner && !paired -> renderBroken()
                !pins.isSet && paired -> renderAwaitingGuardian(owner)
                pairing -> renderPair()
                showBlocked && paired -> renderBlocked()
                !pins.isSet || changingPin -> renderSetPin()
                !unlocked -> renderUnlock(owner)
                else -> renderSettings(owner)
            }
            refreshSave()
            refreshBar()
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
        button("Salvar PIN", primary = true) {
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
        if (!changingPin) {
            section("Guardian remoto")
            text("Se a pessoa de confiança está em outro lugar, pareie com ela em vez de definir o PIN aqui: ela define o PIN pelo site.")
            button("Parear com Guardian remoto") {
                pairing = true
                render()
            }
        }
    }

    // --- Guardian remoto -------------------------------------------------------------

    private fun renderPair() {
        title("Parear com Guardian remoto")
        text("No site do Guardian: Guardiões → Parear aparelho. Cole aqui a string gerada (vale 24 h, uso único).")
        val str = EditText(this).apply {
            hint = "string de pareamento"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        content.addView(str, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val name = EditText(this).apply {
            hint = "nome deste aparelho"
            setText(android.os.Build.MODEL ?: "Aparelho")
        }
        content.addView(name)
        button("Parear", primary = true) {
            val s = str.text.toString()
            val n = name.text.toString()
            val parsed = try {
                Pairing.parse(s)
            } catch (e: CommandException) {
                return@button toast(e.message ?: "string de pareamento inválida")
            }
            // A lista de guardiões veio do gateway: quem confirma o pareamento tem de conferi-la com o guardião por voz.
            val who = parsed.trust.guardians.values.joinToString("\n") { "• ${it.name} (${it.creds.size} passkey(s))" }
            AlertDialog.Builder(this)
                .setTitle("Parear com estes guardiões?")
                .setMessage(
                    "Gateway: ${parsed.url}\n\nQuem poderá controlar este aparelho:\n$who\n\n" +
                        "Confira os nomes com o guardião por voz. Se algum não for esperado, não continue.",
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Parear") { _, _ -> register(s, n) }
                .show()
        }
        button("Voltar") {
            pairing = false
            render()
        }
    }

    private fun register(pairingString: String, name: String) {
        logView.text = "Pareando…"
        if (logView.parent == null) content.addView(logView)
        runAsync {
            val msg = try {
                val fp = Pairing.register(this, pairingString, name)
                RemoteChannel.start(applicationContext)
                pairing = false
                "Pareado. Fingerprint: $fp"
            } catch (e: CommandException) {
                "Não foi possível parear: ${e.message}"
            } catch (e: Exception) {
                "Não foi possível parear: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                toast(msg)
                render()
            }
        }
    }

    /** Pareado e ainda sem PIN: só o guardião (pelo site) pode definir o PIN. Aqui nada é criado. */
    private fun renderAwaitingGuardian(owner: Boolean) {
        title("Aguardando o guardião")
        text("Este aparelho está pareado com um Guardian remoto. Leia o fingerprint para ele conferir:")
        fingerprintBox(remote.fingerprint ?: "?")
        text("Confira por voz com o guardião; ele define o PIN pelo site e envia a ativação. Aqui nenhum PIN pode ser criado.")
        cardText(
            "Canal: ${RemoteChannel.status}" + (Diag.lastError?.let { "\nErro: $it" } ?: "") +
                "\n" + (if (pins.isSet) "PIN definido pelo guardião." else "PIN: aguardando o guardião.") +
                "\n" + (if (remote.activated) "Ativado pelo guardião." else "Ativação: aguardando o guardião."),
        )
        if (!owner) {
            text("Ative o Device Owner só depois que o guardião ativar: adb shell dpm set-device-owner $packageName/.AdminReceiver")
            button("Desfazer o pareamento") {
                Pairing.unpair(this)
                render()
            }
        }
        button("Atualizar") { render() }
    }

    /** Entrada na tela de bloqueio: leva à lista do que foi bloqueado. Pedido ao guardião não usa PIN e não concede nada. */
    private fun renderRequestToGuardian() {
        if (!remote.isPaired) return
        section("Pedir ao guardião")
        button("Pedir liberação de sites e apps", primary = true) { openLiberacoes() }
        text("Mostra o que o filtro bloqueou e os apps suspensos, e manda o pedido ao guardião.")
    }

    // --- Liberações ------------------------------------------------------------------------------------
    // Uma tela com três abas: Sites (o que o filtro bloqueou), Apps (suspensos) e Pedidos (o que já foi pedido e as respostas).
    // Os dados são buscados ao entrar e ao atualizar; trocar de aba só redesenha. Marcar vira uma barra fixa embaixo
    // ("N selecionados · Limpar · Pedir liberação"), igual à barra de salvar das configurações.

    private class BlockedData(val items: List<BlockedItem>, val requests: List<UnblockRequest>, val refused: Set<String>)

    private var blockedData: BlockedData? = null
    private var blockedError: String? = null
    private var blockedLoading = false
    private var notifAsked = false

    private fun unblockApi(): Pair<UnblockApi, String>? {
        val c = remote.consumer() ?: return null
        val gw = remote.gateway ?: return null
        return Pair(UnblockApi(GatewayApi(gw), c.dev), gw)
    }

    private fun openLiberacoes(tab: String? = null) {
        blockedData = null
        blockedError = null
        showBlocked = true
        if (tab != null) blockedTab = tab
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notifAsked) {
            notifAsked = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        render()
        loadBlocked()
    }

    /** Só para os testes de captura de tela: mostra a tela de liberações com dados prontos, sem rede. */
    internal fun debugShowBlocked(items: List<BlockedItem>, requests: List<UnblockRequest>, tab: String, marked: Set<String> = emptySet()) {
        showBlocked = true
        blockedTab = tab
        blockedData = BlockedData(items, requests, emptySet())
        blockedError = null
        picked.clear()
        picked.addAll(marked)
        render()
    }

    private fun loadBlocked() {
        val api = unblockApi()?.first ?: return
        if (blockedLoading) return
        blockedLoading = true
        val onlyOwn = !showAds
        runAsync(onDone = { blockedLoading = false }) {
            var data: BlockedData? = null
            var err: String? = null
            try {
                val items = api.blocked(onlyOwn)
                val reqs = api.requests()
                val refused = try { api.refusedApps() } catch (_: Exception) { emptySet() }
                data = BlockedData(items, reqs, refused)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (!showBlocked || isFinishing) return@runOnUiThread
                if (data != null) blockedData = data
                blockedError = err
                render()
            }
        }
    }

    private fun renderBlocked() {
        subTitle("Liberações") { blockedData = null; blockedError = null; render(); loadBlocked() }
        val api = unblockApi()
        val d = blockedData
        val open = d?.requests?.count { it.status == "open" } ?: 0
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.group_bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((key, label) in listOf("domains" to "Sites", "apps" to "Apps", "requests" to if (open > 0) "Pedidos · $open" else "Pedidos")) {
            val on = blockedTab == key
            tabs.addView(
                TextView(this).apply {
                    text = label
                    gravity = android.view.Gravity.CENTER
                    textSize = 15f
                    setTextColor(getColor(if (on) R.color.nt_ink else R.color.nt_text))
                    touchable()
                    if (on) setBackgroundResource(R.drawable.btn_primary) // depois do toque: a aba marcada fica cheia
                    setOnClickListener { blockedTab = key; render() }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        }
        content.addLoose(tabs, matchWrap.apply { topMargin = dp(4) })
        if (api == null) {
            section(null)
            text("Sem pareamento com o guardião.")
            return
        }
        blockedError?.let { section(null); cardText("Não foi possível falar com o gateway\n$it") }
        if (d == null) {
            section(null)
            text(if (blockedError == null) "Carregando…" else "Toque em atualizar (canto de cima) para tentar de novo.")
            return
        }
        when (blockedTab) {
            "apps" -> drawApps(d)
            "requests" -> drawRequests(api.first, api.second, d)
            else -> drawSites(d)
        }
    }

    /** Uma linha marcável: tocar em qualquer parte alterna; o resumo fica embaixo do título. */
    private fun pickRow(title: String, summary: String?, checked: Boolean, titleColor: Int = R.color.nt_text, onToggle: (Boolean) -> Unit) {
        val cb = android.widget.CheckBox(this).apply {
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.nt_green))
            isClickable = false
            isFocusable = false
        }
        content.addView(
            LinearLayout(this).apply {
                tag = SettingsPage.ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(12), dp(8), dp(12))
                touchable()
                setOnClickListener { cb.toggle(); onToggle(cb.isChecked); refreshBar() }
                addView(twoLine(title, summary, titleColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(cb)
            },
        )
    }

    private fun drawSites(d: BlockedData) {
        val now = System.currentTimeMillis() / 1000
        val openDomains = d.requests.filter { it.status == "open" }.flatMap { r -> r.items.filter { it.state == "open" }.map { it.domain } }.toSet()
        // O canário é o teste de saúde do filtro: bloqueado de propósito, nunca é pedido de liberação.
        val canary = config.canary.trim().lowercase().trimEnd('.')
        val fresh = Cleared.blocked(d.items, remote.blockedClearedAt).filter { it.domain !in openDomains && it.domain != canary }
        picked.retainAll(fresh.map { it.domain }.toSet())

        section("O que aparece aqui")
        text("Marque o que você tentou abrir e peça a liberação. O guardião decide pelo site.")
        switchRow("Mostrar também anúncios e rastreadores", showAds, "Inclui o que as listas de anúncios bloquearam.").apply {
            setOnCheckedChangeListener { _, on -> showAds = on; blockedData = null; render(); loadBlocked() }
        }
        if (fresh.isEmpty()) {
            section("Bloqueado")
            text("Nada bloqueado nas últimas 24 horas. Tente abrir o site de novo e volte aqui.")
        } else {
            fun source(b: BlockedItem) = if (b.own) "regra própria" else b.list.ifEmpty { "lista" }.substringAfter("://").substringBefore('/')
            fun summary(b: BlockedItem) = "${b.count} vez${if (b.count > 1) "es" else ""} · ${UnblockText.ago(now, b.last)} · ${source(b)}"
            val recent = fresh.filter { now - it.last <= 900 }
            val older = fresh.filter { now - it.last > 900 }.take(60)
            if (recent.isNotEmpty()) {
                section("Agora · últimos 15 min")
                recent.take(60).forEach { b -> pickRow(b.domain, summary(b), b.domain in picked) { if (it) picked.add(b.domain) else picked.remove(b.domain) } }
            }
            if (older.isNotEmpty()) {
                section("Antes · últimas 24 h")
                older.forEach { b -> pickRow(b.domain, summary(b), b.domain in picked) { if (it) picked.add(b.domain) else picked.remove(b.domain) } }
            }
            section("Lista")
            button("Selecionar os mais recentes", summary = "Até $MAX_ASK de uma vez") {
                picked.clear()
                picked.addAll(fresh.take(MAX_ASK).map { it.domain })
                render()
            }
            button("Esconder desta lista", summary = "Só tira daqui o que já apareceu. O guardião continua vendo o registro dele.") {
                remote.blockedClearedAt = Cleared.newestSeen(d.items, remote.blockedClearedAt)
                picked.clear()
                toast("Lista limpa")
                render()
            }
        }
        section("Outro assunto")
        button("Pedir um app ou outro assunto…", summary = "Texto livre, para o que o filtro não mostra") { manualDialog() }
        barMode = "sites"
        refreshBar()
    }

    private fun drawApps(d: BlockedData) {
        val pending = PackageGuard.pending(this)
        pickedApps.retainAll(pending.toSet())
        section("Apps suspensos")
        text("Estes apps ficam suspensos até o guardião aprovar. Marque os que você precisa e peça a liberação. Se o guardião recusou por engano, o pedido o faz analisar de novo.")
        if (pending.isEmpty()) {
            text("Nenhum app suspenso.")
        } else {
            for (pkg in pending) {
                val refused = pkg in d.refused
                pickRow(
                    PackageGuard.label(this, pkg), if (refused) "Recusado pelo guardião" else "Aguardando o guardião",
                    pkg in pickedApps, if (refused) R.color.nt_orange else R.color.nt_text,
                ) { if (it) pickedApps.add(pkg) else pickedApps.remove(pkg) }
            }
        }
        barMode = "apps"
        refreshBar()
    }

    private fun drawRequests(api: UnblockApi, gateway: String, d: BlockedData) {
        val requests = Cleared.requests(d.requests, remote.requestsClearedId)
        val open = requests.filter { it.status == "open" }
        val answered = requests.filter { it.status != "open" }.take(8)
        if (requests.isEmpty()) {
            section(null)
            text("Nenhum pedido ainda. Marque o que você precisa em Sites ou Apps.")
            return
        }
        for (r in open) {
            val n = r.items.count { it.state == "open" }
            section("Aguardando o guardião · $n ${if (n > 1) "itens" else "item"}")
            r.items.forEach { content.addView(rowOf(it.domain, UnblockText.stateLabel(it))) }
            button("Enviar link ao guardião", summary = "Ele não é avisado sozinho: mande o link por mensagem") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, "Pedi a liberação de $n ${if (n > 1) "itens" else "item"} no NotToday: $gateway/#/domains/pedidos")
                }
                startActivity(android.content.Intent.createChooser(send, "Enviar ao guardião"))
            }
            button("Cancelar este pedido") {
                runAsync {
                    try {
                        api.cancel(r.id)
                    } catch (e: Exception) {
                        runOnUiThread { toast("Não foi possível cancelar: ${e.message}") }
                    }
                    runOnUiThread { blockedData = null; render(); loadBlocked() }
                }
            }
        }
        if (answered.isNotEmpty()) {
            section("Respondidos")
            answered.forEach { r ->
                r.items.forEach { content.addView(rowOf(it.domain, UnblockText.stateLabel(it), if (it.state == "denied") R.color.nt_orange else R.color.nt_text)) }
            }
            section("Histórico")
            button("Limpar os respondidos", summary = "Só some daqui. O guardião continua vendo o registro dele.") {
                remote.requestsClearedId = Cleared.newestAnswered(d.requests, remote.requestsClearedId)
                render()
            }
        }
    }

    /** Linha só de leitura (título e resumo). */
    private fun rowOf(title: String, summary: String?, titleColor: Int = R.color.nt_text): View =
        twoLine(title, summary, titleColor).apply {
            tag = SettingsPage.ROW
            minimumHeight = dp(56)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

    /** Barra fixa de baixo da tela de pedidos: quantos estão marcados, limpar a seleção e pedir. */
    private fun refreshBar() {
        val n = when (barMode) {
            "sites" -> picked.size
            "apps" -> pickedApps.size
            else -> 0
        }
        requestBarView.visibility = if (showBlocked && n > 0) View.VISIBLE else View.GONE
        requestLabel.text = "$n marcado${if (n > 1) "s" else ""}" + if (n > MAX_ASK) "\nvão só os $MAX_ASK primeiros" else ""
    }

    private fun sendPicked() {
        val api = unblockApi()?.first ?: return
        val sites = barMode == "sites"
        val chosen = (if (sites) picked.toList() else pickedApps.toList()).take(MAX_ASK)
        if (chosen.isEmpty()) return
        runAsync {
            val msg = try {
                if (sites) {
                    api.request(chosen)
                } else {
                    api.askApps(chosen.map { AppAsk(it, PackageGuard.label(this, it)) })
                }
                remote.watched = remote.watched + api.requests().filter { it.status == "open" }.map { it.id }
                picked.clear()
                pickedApps.clear()
                "Pedido enviado. Mande o link ao guardião."
            } catch (e: Exception) {
                "Não foi possível pedir: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                toast(msg)
                if (!msg.startsWith("Não")) blockedTab = "requests"
                blockedData = null
                render()
                loadBlocked()
            }
        }
    }

    /** Escape para o que o DNS não mostra (um app, outro assunto): texto livre, numa janela, fora da lista. */
    private fun manualDialog() {
        val kinds = listOf("app" to "App", "other" to "Outro assunto", "domain" to "Site (digitando o endereço)")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.second })
        }
        val what = EditText(this).apply {
            hint = "o que você precisa, e o motivo"
            setTextColor(getColor(R.color.nt_text))
            setHintTextColor(getColor(R.color.nt_muted))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(kind)
        box.addView(what)
        AlertDialog.Builder(this)
            .setTitle("Pedido manual")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar") { _, _ ->
                val t = what.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton toast("Escreva o pedido")
                val k = kinds[kind.selectedItemPosition].first
                runAsync {
                    val msg = try {
                        val c = remote.consumer() ?: throw CommandException("sem pareamento")
                        GatewayApi(remote.gateway ?: throw CommandException("sem gateway"))
                            .call("POST", "/v1/devices/${c.dev}/requests", mapOf("kind" to k, "text" to t.take(500)))
                        "Pedido enviado"
                    } catch (e: Exception) {
                        "Não foi possível enviar: ${e.message ?: e.javaClass.simpleName}"
                    }
                    runOnUiThread { toast(msg) }
                }
            }
            .show()
    }

    private fun renderRemoteSettings(owner: Boolean) {
        if (!remote.isPaired) {
            text("Nenhum Guardian remoto pareado.")
            button("Parear com Guardian remoto") {
                pairing = true
                render()
            }
            return
        }
        cardText(
            "Pareado. Fingerprint: ${remote.fingerprint ?: "?"}\nGateway: ${remote.gateway}\nCanal: ${RemoteChannel.status}" +
                (if (remote.activated) "\nAtivado pelo guardião." else "\nAinda não ativado pelo guardião."),
        )
        if (!owner) button("Desfazer o pareamento") {
            AlertDialog.Builder(this)
                .setTitle("Desfazer o pareamento?")
                .setMessage("O guardião deixa de controlar este aparelho pelo site.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desfazer") { _, _ ->
                    Pairing.unpair(this)
                    render()
                }
                .show()
        } else text("Com o Device Owner ativo, o pareamento só sai com a remoção do Device Owner (pedido com atraso).")
    }

    private fun renderUnlock(owner: Boolean) {
        title("Bloqueado")
        val locked = pins.lockedForMs()
        if (locked > 0) {
            text("Muitas tentativas. Tente de novo em ${(locked / 60_000) + 1} min.")
            button("Atualizar") { render() }
        } else {
            val pin = pinField("PIN")
            button("Entrar", primary = true) {
                if (pins.verify(pin.text.toString())) unlocked = true else toast("PIN incorreto")
                render()
            }
        }
        if (owner) renderDevWindowLogin()
        renderRequestToGuardian()
    }

    /** Contagem regressiva da janela de desenvolvimento: atualiza a cada 250 ms e volta ao formulário quando acaba. */
    private fun startCountdown() {
        val tv = TextView(this).apply {
            tag = SettingsPage.ROW
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.nt_text))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(tv)
        countdownView = tv
        val runner = object : Runnable {
            override fun run() {
                if (countdownView !== tv) return
                val left = devWindow.remainingMs()
                if (left > 0) {
                    tv.text = "ADB aberto: ${left / 1000 + 1} s restantes"
                    ticker.postDelayed(this, 250)
                } else {
                    countdownView = null
                    render()
                }
            }
        }
        countdownRunner = runner
        runner.run()
    }

    private fun stopCountdown() {
        countdownRunner?.let { ticker.removeCallbacks(it) }
        countdownRunner = null
        countdownView = null
    }

    /** Janela de desenvolvimento: com o PIN de desenvolvedor (não é o da pessoa de confiança) abre só o ADB. */
    private fun renderDevWindowLogin() {
        val debugSpec = Catalog.restrictions.first { it.key == UserManager.DISALLOW_DEBUGGING_FEATURES }
        if (!devPins.isSet || !config.isRestrictionOn(debugSpec)) return
        section("Janela de desenvolvimento")
        val left = devWindow.remainingMs()
        if (left > 0) {
            startCountdown()
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

    // A tela de configurações é como a das Configurações do Android: um início com o estado e as categorias, e uma
    // subtela por categoria. As mudanças de todas as subtelas vão para um rascunho só; "Salvar e aplicar" manda o
    // rascunho pelo Gate (endurecer vale na hora, afrouxar espera o atraso), como antes.
    private val pages = listOf(
        "restricoes" to "Restrições", "lojas" to "Lojas", "filtro" to "Filtro DNS", "apps" to "Apps novos (Play Store)",
        "dev" to "Desenvolvimento (ADB curto)", "chrome" to "Chrome", "sistema" to "Sistema", "seguranca" to "Segurança e PIN",
        "guardian" to "Guardian remoto", "perigo" to "Zona de perigo",
    )

    private fun draft(): MutableMap<String, String> = draft ?: LinkedHashMap(config.snapshot()).also { draft = it }

    private fun changedKeys(): List<String> {
        val d = draft ?: return emptyList()
        val old = config.snapshot()
        return d.keys.filter { old[it] != d[it] }
    }

    private fun renderSettings(owner: Boolean) {
        val p = page
        if (p == null) renderHome(owner) else renderSubpage(p, owner)
    }

    private fun renderHome(owner: Boolean) {
        title("NotToday")
        cardText(
            if (owner) "Device Owner ativo\nO NotToday controla as restrições deste aparelho."
            else "Device Owner inativo\nRode no PC: adb shell dpm set-device-owner $packageName/.AdminReceiver",
            accent = owner,
        )
        renderStatus(owner)
        renderMaintenance(owner)
        renderPending(owner)
        section("Configurações")
        for ((key, label) in pages) {
            if (key == "perigo" && !owner) continue
            button(label, summary = summaryOf(key)) {
                page = key
                render()
            }
        }
    }

    private fun summaryOf(key: String): String {
        val d = draft()
        fun on(k: String) = d[k] == "true"
        fun yesNo(k: String) = if (on(k)) "ligado" else "desligado"
        return when (key) {
            "restricoes" -> "${Catalog.restrictions.count { on("r_${it.key}") }} de ${Catalog.restrictions.size} ligadas"
            "lojas" -> "Suspender lojas alternativas: ${yesNo("suspend_blocked")}"
            "filtro" -> if (on("dns_filter")) "Pelo Tailscale · ligado · canário ${d["canary"].orEmpty().ifEmpty { "não definido" }}" else "Desligado"
            "apps" -> "Suspender até aprovar: ${yesNo("new_apps_deny")} · ${PackageGuard.pending(this).size} aguardando"
            "dev" -> "${d["dev_windows_per_day"]} janela(s) por dia de ${d["dev_window_seconds"]} s" +
                if (devPins.isSet) "" else " · PIN não definido"
            "chrome" -> "DoH desligado: ${if (on("chrome_doh_off")) "sim" else "não"} · SafeSearch: ${if (on("chrome_safesearch")) "sim" else "não"} · IP bloqueado: ${if (on("chrome_block_ip")) "sim" else "não"}"
            "sistema" -> "Adiar atualizações: ${yesNo("postpone_updates")}"
            "seguranca" -> "Atraso para afrouxar: ${(d[Gate.KEY_DELAY]?.toLongOrNull() ?: 0) / 60} h"
            "guardian" -> if (remote.isPaired) "Pareado" + if (remote.activated) " e ativado" else ", aguardando ativação" else "Não pareado"
            else -> "Remover o Device Owner"
        }
    }

    /** Título de subtela com a seta de voltar, como nas Configurações. */
    private fun subTitle(s: String, onRefresh: (() -> Unit)? = null) = content.addLoose(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(28), dp(8), dp(12))
            addView(android.widget.ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = "Voltar"
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { goBack() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(this@MainActivity).apply {
                text = s
                textSize = 28f
                setTextColor(getColor(R.color.nt_text))
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (onRefresh != null) addView(android.widget.ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_refresh)
                contentDescription = "Atualizar"
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onRefresh() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        },
        matchWrap,
    )

    private fun bindSwitch(key: String, label: String, hint: String? = null): Switch =
        switchRow(label, draft()[key] == "true", hint).apply { setOnCheckedChangeListener { _, on -> draft()[key] = on.toString(); refreshSave() } }

    /** Campo de texto ligado a uma chave do rascunho. `toValue` devolve null quando o texto não serve (fica o valor anterior). */
    private fun bindField(key: String, shown: String, type: Int, hintText: String? = null, toValue: (String) -> String?): EditText =
        EditText(this).apply {
            setText(shown)
            hint = hintText
            inputType = type
            setTextColor(getColor(R.color.nt_text))
            setHintTextColor(getColor(R.color.nt_muted))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    toValue(s?.toString().orEmpty())?.let { draft()[key] = it }
                    refreshSave()
                }
            })
            content.addView(this)
        }

    private fun pkgList(t: String) = t.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    private fun renderSubpage(key: String, owner: Boolean) {
        subTitle(pages.first { it.first == key }.second)
        val d = draft()
        val num = InputType.TYPE_CLASS_NUMBER
        when (key) {
            "restricoes" -> Catalog.restrictions.forEach { bindSwitch("r_${it.key}", it.label, it.warning) }
            "lojas" -> {
                bindSwitch(
                    "suspend_blocked", "Suspender lojas alternativas e instaladores de pacote",
                    "Galaxy Store, GetApps, AppGallery, F-Droid etc. A Play Store continua liberada. Com \"Suspender apps novos\" " +
                        "ligado, esta opção é redundante (o app novo já fica suspenso).",
                )
                section("Lojas extras")
                text("Pacotes separados por vírgula, suspensos junto com as lojas acima.")
                bindField("store_extra", d["store_extra"].orEmpty().replace(",", ", "), InputType.TYPE_CLASS_TEXT) { pkgList(it) }
            }
            "filtro" -> {
                text(
                    "O Tailscale vira a VPN sempre ativa e leva ao seu servidor DNS filtrante (Pi-hole, AdGuard Home etc., " +
                        "definido como DNS da tailnet) só os apps marcados. Instale o Tailscale e faça " +
                        "login ANTES de ativar o Device Owner. Você liga e desliga o Tailscale no app dele.",
                )
                bindSwitch("dns_filter", "Filtro ativo (Tailscale sempre ativo)")
                bindSwitch(
                    "browsers_need_filter", "Apps marcados só funcionam com o filtro confirmado",
                    "Sem VPN ou com o canário resolvendo, os apps marcados são suspensos; os demais seguem com internet.",
                )
                section("Domínio-canário")
                text("Um domínio REAL (existente) que o seu servidor DNS mantém bloqueado. Sem ele, os apps marcados ficam suspensos.")
                bindField("canary", d["canary"].orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "ex.: canario.exemplo.com") {
                    it.trim().lowercase()
                }
                section("Apps no filtro")
                val extra = d["routed_extra"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                val exempt = d["browsers_exempt"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                val inFilter = ((Browsers.installed(this) - exempt) + extra).distinct().joinToString { PackageGuard.label(this, it) }
                button("Escolher apps que passam pelo filtro", summary = inFilter.ifEmpty { "Nenhum app" }) {
                    pickApps(extra, exempt) { e, x ->
                        draft()["routed_extra"] = e.sorted().joinToString(",")
                        draft()["browsers_exempt"] = x.sorted().joinToString(",")
                        render()
                    }
                }
            }
            "apps" -> {
                bindSwitch(
                    "new_apps_deny", "Suspender apps novos até a aprovação",
                    "Instalar é livre; o app novo é suspenso logo em seguida e só abre depois que a pessoa de confiança aprova aqui. " +
                        "Navegadores novos entram sozinhos no filtro.",
                )
                section("Aprovação")
                renderApprovals(owner)
            }
            "dev" -> {
                text(
                    "O PIN de desenvolvedor abre só o ADB, por poucos segundos e poucas vezes ao dia, na tela de bloqueio. " +
                        "Só funciona com a depuração bloqueada em Restrições. Ajustes de limite que aumentam o acesso esperam o atraso.",
                )
                button(if (devPins.isSet) "Trocar PIN de desenvolvedor" else "Definir PIN de desenvolvedor") { promptDevPin() }
                bindSwitch("dev_reboot", "Reiniciar o aparelho ao fim da janela", "Mata processos de shell iniciados durante a janela (estilo Shizuku).")
                section("Janelas por 24 h")
                bindField("dev_windows_per_day", d["dev_windows_per_day"].orEmpty(), num) { it.toIntOrNull()?.coerceIn(0, 20)?.toString() }
                section("Duração de cada janela (segundos)")
                bindField("dev_window_seconds", d["dev_window_seconds"].orEmpty(), num) { it.toIntOrNull()?.coerceIn(10, 600)?.toString() }
            }
            "chrome" -> {
                text("Configuração gerenciada do Chrome. Melhor esforço: confira em chrome://policy. Não vale para outros navegadores.")
                bindSwitch(
                    "chrome_doh_off", "Desligar DNS sobre HTTPS",
                    "Recomendado: com DoH o Chrome ignora o servidor DNS filtrante. Só vale com o filtro ativo.",
                )
                bindSwitch("chrome_safesearch", "Forçar SafeSearch do Google")
                bindSwitch(
                    "chrome_block_ip", "Bloquear acesso por endereço IP",
                    "Recomendado: um site aberto pelo número (IP) escapa do filtro de DNS. Redes locais e a Tailscale continuam abrindo. Só vale no Chrome; apps seguem livres.",
                )
                bindSwitch("chrome_incognito_block", "Bloquear modo anônimo", "Opcional: o anônimo não escapa do DNS filtrante, só esconde o histórico.")
                section("YouTube restrito no Chrome")
                content.addView(Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Desligado", "Moderado", "Estrito"))
                    setSelection(d["chrome_youtube"]?.toIntOrNull() ?: 0)
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            draft()["chrome_youtube"] = position.toString()
                            refreshSave()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                })
            }
            "sistema" -> {
                bindSwitch("postpone_updates", "Adiar atualizações do Android (até 30 dias)", "Dá tempo de testar o app numa versão nova. Passados 30 dias o sistema instala.")
                section("Apps protegidos extras")
                text("Pacotes separados por vírgula (ex.: AppLock). Confira com `pm list packages`.")
                bindField("protected_pkgs", d["protected_pkgs"].orEmpty().replace(",", ", "), InputType.TYPE_CLASS_TEXT) { pkgList(it) }
            }
            "seguranca" -> {
                section("Atraso para desbloqueios (horas)")
                text("Aumentar vale na hora; diminuir também espera o atraso atual.")
                bindField(Gate.KEY_DELAY, ((d[Gate.KEY_DELAY]?.toLongOrNull() ?: 0) / 60).toString(), num) {
                    it.toLongOrNull()?.coerceIn(0, 24 * 14)?.let { h -> (h * 60).toString() }
                }
                section("PIN")
                button("Trocar PIN", summary = "O PIN da pessoa de confiança") {
                    changingPin = true
                    render()
                }
            }
            "guardian" -> renderRemoteSettings(owner)
            "perigo" -> {
                val gateOn = owner && config.unlockDelayMin > 0
                text("Remover o Device Owner desliga todas as proteções deste app." + if (gateOn) " Vira um pedido que espera o atraso." else "")
                if (owner) button(if (gateOn) "Pedir remoção do Device Owner" else "Remover Device Owner") {
                    if (gateOn) {
                        pendings.put(Pending.Kind.RELEASE, emptyMap(), config.unlockDelayMin * 60_000L)
                        page = null
                        render()
                    } else {
                        confirmRelease()
                    }
                }
            }
        }
    }

    /** Barra fixa embaixo: aparece assim que o rascunho muda, em qualquer subtela das configurações. */
    private fun refreshSave() {
        val n = if (unlocked && !changingPin) changedKeys().size else 0
        saveBarView.visibility = if (n > 0) View.VISIBLE else View.GONE
        saveLabel.text = "$n alteração${if (n > 1) "ões" else ""} não salva${if (n > 1) "s" else ""}"
    }

    private fun saveDraft(owner: Boolean) {
        val old = config.snapshot()
        val new = LinkedHashMap(old).apply { putAll(draft()) }
        val gateOn = owner && config.unlockDelayMin > 0
        // Endurecer vale na hora; afrouxar vira um pedido que só pode ser confirmado depois do atraso.
        val split = if (gateOn) Gate.split(old, new) else Gate.Split(new.filter { old[it.key] != it.value }, emptyMap())
        config.applySnapshot(split.immediate)
        var note = ""
        if (split.delayed.isNotEmpty()) {
            pendings.put(Pending.Kind.SETTINGS, split.delayed, config.unlockDelayMin * 60_000L)
            note = "${split.delayed.size} alteração(ões) que afrouxam a proteção aguardam ${formatDuration(config.unlockDelayMin * 60_000L)}.\n"
        }
        draft = null
        render()
        runAndShow("Aplicando…", note) { policy.apply() }
    }

    /** Voltar da subtela (seta ou gesto do sistema). */
    private fun goBack() {
        when {
            page != null -> page = null
            showBlocked -> showBlocked = false
            pairing -> pairing = false
            else -> return
        }
        render()
    }

    private val backCallback = android.window.OnBackInvokedCallback { goBack() }
    private var backRegistered = false

    private fun syncBack() {
        val want = (page != null && unlocked) || showBlocked || pairing
        if (want == backRegistered) return
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        backRegistered = want
    }

    /** Estado do filtro: VPN, canário e erro recente. */
    private fun renderStatus(owner: Boolean) {
        if (!owner) return
        val vpn = VpnWatch.isVpnUp(this)
        val active = policy.filterActive()
        cardText(
            "Filtro: ${if (!config.dnsFilterEnabled) "desligado" else if (active) "CONFIRMADO" else "NÃO confirmado"}\n" +
                "VPN: ${if (vpn) "de pé" else "fora"} · ${Health.lastDetail}" +
                (Diag.lastError?.let { "\nErro: $it" } ?: ""),
            accent = active,
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

    private fun labelFor(key: String): String = ConfigLabels.label(key)

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
                    Pending.Kind.GUARDIAN -> "Pedido: remover guardião"
                },
            )
            if (kind == Pending.Kind.GUARDIAN) text("Guardião: ${p.payload["id"] ?: "?"}")
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
                            Pending.Kind.GUARDIAN -> "Confirmar remoção do guardião"
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
            Pending.Kind.GUARDIAN -> {
                val id = p.payload["id"]
                val c = remote.consumer()
                if (id != null && c != null) {
                    try {
                        c.applyTrust(com.allan.nottoday.remote.Command(c.dev, "", 0, "remove_guardian", mapOf("id" to id)))
                        remote.saveConsumer(c)
                    } catch (e: CommandException) {
                        toast(e.message ?: "falhou")
                    }
                }
                pendings.clear(p.kind)
                render()
            }
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

    private companion object { const val MAX_ASK = 20 }

    private val matchWrap get() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Fundo com o efeito de toque do sistema (o mesmo das Configurações). */
    private fun View.touchable() {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        isClickable = true
        isFocusable = true
    }

    /** Título grande da tela, como o da barra das Configurações expandida. */
    private fun title(s: String) = content.addLoose(
        TextView(this).apply {
            text = s
            textSize = 32f
            setTextColor(getColor(R.color.nt_text))
            setPadding(dp(8), dp(40), dp(8), dp(12))
        },
        matchWrap,
    )

    /** Nova categoria (nome em destaque e um grupo novo). */
    private fun section(s: String?) = content.startGroup(s)

    /** Texto explicativo dentro do grupo, no tom secundário. */
    private fun text(s: String) = content.addView(TextView(this).apply {
        tag = SettingsPage.ROW
        text = s
        textSize = 14f
        setLineSpacing(0f, 1.15f)
        setTextColor(getColor(R.color.nt_muted))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    })

    /** Item de duas linhas (título e resumo), sem ação. */
    private fun twoLine(titleText: String, summary: String?, titleColor: Int = R.color.nt_text): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = titleText
            textSize = 17f
            setTextColor(getColor(titleColor))
        })
        if (!summary.isNullOrEmpty()) addView(TextView(this@MainActivity).apply {
            text = summary
            textSize = 14f
            setLineSpacing(0f, 1.1f)
            setTextColor(getColor(R.color.nt_muted))
            setPadding(0, dp(2), 0, 0)
        })
    }

    /** Estado (filtro, Guardian remoto…): a primeira linha é o título, o resto vira o resumo. */
    private fun cardText(s: String, accent: Boolean = false) = content.addView(
        twoLine(s.substringBefore('\n'), s.substringAfter('\n', "").ifEmpty { null }, if (accent) R.color.nt_green else R.color.nt_text).apply {
            tag = SettingsPage.ROW
            setPadding(dp(16), dp(16), dp(16), dp(16))
        },
    )

    /** O fingerprint do aparelho, grande, para ler em voz alta ao guardião. */
    private fun fingerprintBox(fp: String) = content.addLoose(
        TextView(this).apply {
            text = fp
            typeface = Typeface.MONOSPACE
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.nt_text))
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.card_accent)
            setPadding(dp(10), dp(18), dp(10), dp(18))
        },
        matchWrap.apply { topMargin = dp(12); bottomMargin = dp(4) },
    )

    /**
     * Ação. A principal da tela (Salvar, Entrar…) é um botão cheio fora dos grupos; as demais são itens tocáveis do grupo,
     * como "Revogar autorizações de depuração USB" nas Opções do desenvolvedor.
     */
    private fun button(label: String, primary: Boolean = false, summary: String? = null, onClick: () -> Unit) {
        if (primary) {
            content.addLoose(
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 16f
                    setTextColor(getColor(R.color.nt_ink))
                    setBackgroundResource(R.drawable.btn_primary)
                    minHeight = dp(52)
                    minimumHeight = dp(52)
                    setOnClickListener { onClick() }
                },
                matchWrap.apply { topMargin = dp(16); bottomMargin = dp(4) },
            )
            return
        }
        content.addView(
            twoLine(label, summary).apply {
                tag = SettingsPage.ROW
                minimumHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                touchable()
                setOnClickListener { onClick() }
            },
        )
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        setTextColor(getColor(R.color.nt_text))
        setHintTextColor(getColor(R.color.nt_muted))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        content.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Item com interruptor à direita; tocar em qualquer parte da linha liga ou desliga. */
    private fun switchRow(label: String, checked: Boolean, hint: String? = null): Switch {
        val sw = Switch(this).apply {
            isChecked = checked
            contentDescription = label
        }
        val row = LinearLayout(this).apply {
            tag = SettingsPage.ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            touchable()
            setOnClickListener { sw.toggle() }
            addView(twoLine(label, hint), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
            addView(sw)
        }
        content.addView(row)
        return sw
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
