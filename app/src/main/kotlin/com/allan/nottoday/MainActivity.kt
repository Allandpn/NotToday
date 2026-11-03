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
import com.allan.nottoday.remote.DomainEdit
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
import com.allan.nottoday.remote.GatewayException
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
    private lateinit var fabView: LinearLayout
    private val cancelling = mutableSetOf<Long>()  // pedidos cancelados que ainda podem ser desfeitos
    private var blockedTab = "domains"  // Liberações: "domains" | "apps" | "requests"
    private var barMode: String? = null  // barra de pedido: "sites" | "apps" | null
    private lateinit var requestBarView: LinearLayout
    private val requestLabel by lazy { TextView(this) }
    private val pickedApps = linkedSetOf<String>()
    private val picked = linkedSetOf<String>()
    private val depth = HashMap<String, Int>()  // domínio bloqueado -> quantos rótulos foram tirados antes de pedir
    // Barra de cima das Liberações (Material 3): título com Atualizar e as ações da aba; com algo marcado vira o modo de
    // seleção ("2 selecionados", X para desmarcar). As abas dizem quais ações têm em headerActions e selectRecent.
    private var blockedHeader: LinearLayout? = null
    private var headerActions: List<Triple<Int, String, () -> Unit>> = emptyList()
    private var selectRecent: (() -> Unit)? = null
    private val requestButton by lazy { Button(this) }
    // "Lista limpa · Desfazer" por alguns segundos, como o snackbar do Material: limpar não pede confirmação, mas dá para voltar.
    private lateinit var undoBarView: LinearLayout
    private val undoLabel by lazy { TextView(this) }
    private var undoAction: (() -> Unit)? = null
    private val undoHide = Runnable { undoBarView.visibility = View.GONE; undoAction = null }
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
        // Botão flutuante das Liberações ("Outro pedido"): fica sobre a lista, some no modo de seleção.
        fabView = LinearLayout(this).apply {
            tag = "fab"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.fab_bg)
            elevation = dp(6).toFloat()
            minimumHeight = dp(56)
            setPadding(dp(16), 0, dp(20), 0)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            contentDescription = "Outro pedido: um app ou assunto que a lista não mostra"
            addView(icon(R.drawable.ic_add, R.color.nt_text), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = "Outro pedido"
                textSize = 15f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(getColor(R.color.nt_text))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { manualDialog() }
        }
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
                minHeight = dp(48); minimumHeight = dp(48)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(Button(this@MainActivity).apply {
                text = "Salvar e aplicar"
                isAllCaps = false
                setTextColor(getColor(R.color.nt_ink))
                setBackgroundResource(R.drawable.btn_primary)
                setPadding(dp(16), 0, dp(16), 0)
                minHeight = dp(48); minimumHeight = dp(48)
                setOnClickListener { saveDraft(policy.isDeviceOwner()) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        requestBarView = LinearLayout(this).apply {
            tag = "requestbar"
            setBackgroundColor(getColor(R.color.nt_surface2))
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(requestLabel.apply { textSize = 13f; setTextColor(getColor(R.color.nt_muted)); setPadding(dp(4), 0, 0, dp(6)) })
            addView(requestButton.apply {
                isAllCaps = false
                textSize = 16f
                setTextColor(getColor(R.color.nt_ink))
                setBackgroundResource(R.drawable.btn_primary)
                minHeight = dp(52); minimumHeight = dp(52)
                setOnClickListener { sendPicked() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        undoBarView = LinearLayout(this).apply {
            tag = "undobar"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.nt_surface2))
            setPadding(dp(20), dp(6), dp(8), dp(6))
            visibility = View.GONE
            addView(undoLabel.apply { textSize = 15f; setTextColor(getColor(R.color.nt_text)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Desfazer"
                isAllCaps = false
                textSize = 15f
                setTextColor(getColor(R.color.nt_green))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { val u = undoAction; undoHide.run(); u?.invoke() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.nt_bg))
            addView(android.widget.FrameLayout(this@MainActivity).apply {
                addView(scroll)
                addView(fabView, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(undoBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(requestBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(saveBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // targetSdk 36 desenha edge-to-edge: afastar o conteúdo das barras do sistema e do teclado (a janela não encolhe
        // mais sozinha quando ele abre, e a barra fixa de baixo ficaria por trás dele).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
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
        undoBarView.removeCallbacks(undoHide)
        undoHide.run()
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
        blockedHeader = null
        headerActions = emptyList()
        selectRecent = null
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
        lead("A pessoa de confiança digita o PIN agora e o guarda.")
        val pin = pinField("Novo PIN", loose = true, helper = "Mínimo de ${PinStore.MIN_LENGTH} dígitos")
        val again = pinField("Repita o PIN", loose = true)
        var delayField: EditText? = null
        if (!changingPin) {
            // O atraso quase nunca muda na primeira vez: fica recolhido em "Avançado".
            section(null)
            val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val hours = Config.DEFAULT_DELAY_MIN / 60
            val toggle = navRow("Avançado", "Atraso para desbloqueios: $hours h", R.drawable.ic_tune, R.drawable.ic_expand_more) {}
            toggle.setOnClickListener {
                val open = more.visibility != View.VISIBLE
                more.visibility = if (open) View.VISIBLE else View.GONE
                (toggle.getChildAt(toggle.childCount - 1) as android.widget.ImageView)
                    .setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            }
            content.addView(more.apply { tag = SettingsPage.ROW; setPadding(dp(16), 0, dp(16), dp(12)) })
            more.addView(TextView(this).apply {
                text = "Espera para qualquer desbloqueio, em horas (0 = sem atraso, só para testes)."
                textSize = 14f
                setTextColor(getColor(R.color.nt_muted))
                setPadding(0, 0, 0, dp(4))
            })
            delayField = field("Atraso (horas)", parent = more) {
                setText(hours.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }
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
        if (changingPin) actionButton("Cancelar", style = Style.TEXT) {
            changingPin = false
            render()
        } else actionButton("Prefiro parear com o guardião", R.drawable.ic_link, Style.TEXT) {
            pairing = true
            render()
        }
    }

    // --- Guardian remoto -------------------------------------------------------------

    private fun renderPair() {
        title("Parear com Guardian remoto")
        text("No site do Guardian: Guardiões → Parear aparelho. Cole aqui a string gerada (vale 24 h, uso único).")
        val str = field("String de pareamento", "cole aqui o texto copiado do site") {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        val name = field("Nome deste aparelho") { setText(android.os.Build.MODEL ?: "Aparelho") }
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
    /** Na tela de bloqueio: a ação de quem usa o aparelho, secundária ao "Entrar" (que é da pessoa de confiança). */
    private fun renderRequestToGuardian() {
        if (!remote.isPaired) return
        content.addLoose(TextView(this).apply {
            text = "Precisa de um site ou app?"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.nt_muted))
        }, matchWrap.apply { topMargin = dp(48) })
        actionButton("Pedir liberação ao guardião", R.drawable.ic_send, Style.TONAL) { openLiberacoes() }
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

    /** Só para os testes de captura de tela: redesenha a tela atual. */
    internal fun debugRender() = render()

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
        runAsync(onDone = { blockedLoading = false }) {
            var data: BlockedData? = null
            var err: String? = null
            try {
                val items = api.blocked()
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
        blockedHeader = LinearLayout(this).apply {
            tag = "blockedheader"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(28), dp(4), dp(12))
        }
        content.addLoose(blockedHeader!!, matchWrap)
        val api = unblockApi()
        val d = blockedData
        val open = d?.requests?.count { it.status == "open" && it.id !in cancelling } ?: 0
        content.addLoose(tabs(open), matchWrap)
        content.addLoose(View(this).apply { setBackgroundColor(getColor(R.color.nt_line)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        if (api == null) {
            emptyState(R.drawable.ic_link, "Sem pareamento", "Pareie com o guardião para pedir liberações.")
            return
        }
        if (d == null) {
            if (blockedError != null) {
                content.addLoose(View(this), LinearLayout.LayoutParams(1, dp(12)))
                statusCard(false, R.drawable.ic_cloud_off, "Sem conexão com o guardião", blockedError!!, "Tentar de novo") {
                    blockedError = null; render(); loadBlocked()
                }
            } else {
                content.addLoose(android.widget.ProgressBar(this).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.nt_green))
                    contentDescription = "Carregando"
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = dp(96)
                })
            }
            return
        }
        blockedError?.let { note("Não foi possível atualizar agora ($it). Mostrando a última lista.") }
        when (blockedTab) {
            "apps" -> drawApps(d)
            "requests" -> drawRequests(api.first, api.second, d)
            else -> drawSites(d)
        }
    }

    /** Abas do Material 3: texto, sublinhado verde na marcada e o contador de pedidos abertos num selo. */
    private fun tabs(open: Int): LinearLayout = LinearLayout(this).apply {
        tag = "tabs"
        orientation = LinearLayout.HORIZONTAL
        for ((key, label) in listOf("domains" to "Sites", "apps" to "Apps", "requests" to "Pedidos")) {
            val on = blockedTab == key
            addView(android.widget.FrameLayout(this@MainActivity).apply {
                touchable()
                isSelected = on
                contentDescription = label + if (key == "requests" && open > 0) ", $open aberto${if (open > 1) "s" else ""}" else ""
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.TabWidget"
                        info.isSelected = on
                    }
                }
                setOnClickListener { blockedTab = key; render() }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(TextView(this@MainActivity).apply {
                        text = label
                        textSize = 15f
                        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                        setTextColor(getColor(if (on) R.color.nt_green else R.color.nt_muted))
                    })
                    if (key == "requests" && open > 0) addView(badge(open.toString()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (on) addView(View(this@MainActivity).apply { setBackgroundResource(R.drawable.tab_indicator) },
                    android.widget.FrameLayout.LayoutParams(dp(56), dp(3), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }

    private fun badge(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
        setTextColor(getColor(R.color.nt_ink))
        setBackgroundResource(R.drawable.badge_bg)
        minWidth = dp(20)
        minHeight = dp(20)
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    private enum class Chip { OK, WAIT, NO, NEUTRAL }

    /** Selo de estado: ícone, texto e cor juntos (a cor nunca é o único sinal). */
    private fun chip(t: String, iconRes: Int?, kind: Chip) = LinearLayout(this).apply {
        val (bg, fg) = when (kind) {
            Chip.OK -> R.color.nt_chip_ok to R.color.nt_on_chip_ok
            Chip.WAIT -> R.color.nt_chip_wait to R.color.nt_on_chip_wait
            Chip.NO -> R.color.nt_chip_no to R.color.nt_on_chip_no
            Chip.NEUTRAL -> R.color.nt_surface2 to R.color.nt_muted
        }
        tag = "chip"
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.chip_bg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(bg))
        setPadding(dp(8), 0, dp(10), 0)
        minimumHeight = dp(24)
        if (iconRes != null) addView(icon(iconRes, fg), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(4) })
        addView(TextView(this@MainActivity).apply {
            text = t
            textSize = 12f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(getColor(fg))
        })
    }

    /** Estado vazio: ícone num círculo, uma frase e o que esperar. */
    private fun emptyState(iconRes: Int, titleText: String, body: String) = content.addLoose(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(circleIcon(iconRes, R.drawable.circle_neutral, R.color.nt_green, 80, 40))
            addView(TextView(this@MainActivity).apply {
                text = titleText
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.nt_text))
                setPadding(0, dp(20), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setTextColor(getColor(R.color.nt_muted))
                setPadding(0, dp(8), 0, 0)
            })
        },
        matchWrap,
    )

    /**
     * Linha marcável no padrão das listas do Android: a caixa à esquerda marca; o resto da linha faz a ação dela
     * (`onOpen`) ou, se não houver, também marca. `trailing` vai à direita (um selo).
     */
    private fun checkRow(title: String, summary: String?, checked: Boolean?, trailing: View? = null, titleColor: Int = R.color.nt_text,
                         onOpen: (() -> Unit)? = null, onToggle: (Boolean) -> Unit = {}): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = SettingsPage.ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
        }
        var cb: android.widget.CheckBox? = null
        if (checked != null) {
            val box = android.widget.CheckBox(this).apply {
                isChecked = checked
                buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.nt_green))
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cb = box
            row.addView(android.widget.FrameLayout(this).apply {
                touchable()
                contentDescription = "Marcar $title"
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.CheckBox::class.java.name
                        info.isCheckable = true
                        info.isChecked = box.isChecked
                    }
                }
                setOnClickListener { box.toggle(); onToggle(box.isChecked); refreshBar() }
                addView(box, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        } else row.addView(View(this), LinearLayout.LayoutParams(dp(56), 1))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(10), dp(16), dp(10))
            addView(twoLine(title, summary, titleColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            when {
                onOpen != null -> { touchable(); setOnClickListener { onOpen() } }
                cb != null -> { touchable(); setOnClickListener { cb.toggle(); onToggle(cb.isChecked); refreshBar() } }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
        return row
    }

    /** Linha de um site bloqueado. O pedido vai com o domínio escolhido na folha de baixo (o completo ou um pai dele). */
    private fun siteRow(b: BlockedItem, baseSummary: String) {
        val levels = DomainEdit.levels(b.domain)
        val i = (depth[b.domain] ?: 0).coerceIn(0, levels.size - 1)
        val summary = if (i > 0) "vale para *.${levels[i]}\n$baseSummary" else baseSummary
        if (!b.own) {
            // Bloqueio de lista é só para ver: o gateway recusa o pedido e quem libera é o guardião, à mão.
            checkRow(b.domain, baseSummary, null, chip(if (b.veto) "vetado" else "lista", R.drawable.ic_block, Chip.NEUTRAL))
            return
        }
        checkRow(levels[i], summary, b.domain in picked,
            onOpen = if (levels.size > 1) ({ levelSheet(b, levels) }) else null) { on ->
            if (on) picked.add(b.domain) else picked.remove(b.domain)
        }
    }

    /** Folha de baixo para escolher o que pedir: o endereço exato ou um domínio pai (que vale para tudo dentro dele). */
    private fun levelSheet(b: BlockedItem, levels: List<String>) {
        val dlg = android.app.Dialog(this)
        var chosen = (depth[b.domain] ?: 0).coerceIn(0, levels.size - 1)
        val radios = mutableListOf<android.widget.RadioButton>()
        val box = LinearLayout(this).apply {
            tag = "sheet"
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding(0, dp(8), 0, dp(24))
            addView(View(this@MainActivity).apply { setBackgroundResource(R.drawable.sheet_handle) },
                LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(16) })
            addView(TextView(this@MainActivity).apply {
                text = "Pedir qual domínio?"
                textSize = 20f
                setTextColor(getColor(R.color.nt_text))
                setPadding(dp(24), 0, dp(24), dp(8))
            })
            levels.forEachIndexed { idx, lv ->
                val rb = android.widget.RadioButton(this@MainActivity).apply {
                    isChecked = idx == chosen
                    buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.nt_green))
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                radios += rb
                val desc = when {
                    idx == 0 -> "Só este endereço"
                    idx == levels.size - 1 -> "O domínio inteiro, com tudo dentro dele"
                    else -> "Este e tudo dentro dele"
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(16), dp(8), dp(24), dp(8))
                    touchable()
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = android.widget.RadioButton::class.java.name
                            info.isCheckable = true
                            info.isChecked = rb.isChecked
                        }
                    }
                    setOnClickListener { chosen = idx; radios.forEachIndexed { k, r -> r.isChecked = k == idx } }
                    addView(rb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
                    addView(twoLine(lv, desc), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(24), dp(16), dp(24), 0)
                fun pill(label: String, primary: Boolean, onClick: () -> Unit) = Button(this@MainActivity).apply {
                    text = label
                    isAllCaps = false
                    textSize = 16f
                    setTextColor(getColor(if (primary) R.color.nt_ink else R.color.nt_text))
                    setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_secondary)
                    minHeight = dp(52); minimumHeight = dp(52)
                    setOnClickListener { onClick() }
                }
                addView(pill("Cancelar", false) { dlg.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
                addView(pill("Marcar", true) {
                    depth[b.domain] = chosen
                    picked.add(b.domain)
                    dlg.dismiss()
                    render()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheetShown = dlg
        dlg.show()
    }

    /** Só para os testes: a última folha de baixo aberta. */
    internal var sheetShown: android.app.Dialog? = null

    private fun drawSites(d: BlockedData) {
        val now = System.currentTimeMillis() / 1000
        val openDomains = d.requests.filter { it.status == "open" }.flatMap { r -> r.items.filter { it.state == "open" }.map { it.domain } }.toSet()
        // O canário é o teste de saúde do filtro: bloqueado de propósito, nunca é pedido de liberação.
        val canary = config.canary.trim().lowercase().trimEnd('.')
        val fresh = Cleared.blocked(d.items, remote.blockedClearedAt).filter { it.domain !in openDomains && it.domain != canary }
        picked.retainAll(fresh.map { it.domain }.toSet())

        if (fresh.isNotEmpty()) {
            headerActions = listOf(Triple(R.drawable.ic_delete_sweep, "Limpar lista") {
                val before = remote.blockedClearedAt
                remote.blockedClearedAt = Cleared.newestSeen(d.items, remote.blockedClearedAt)
                picked.clear()
                render()
                showUndo("Lista limpa") { remote.blockedClearedAt = before; render() }
            })
        }
        if (fresh.any { it.own }) selectRecent = {
            picked.clear()
            picked.addAll(fresh.filter { it.own }.take(MAX_ASK).map { it.domain })
            render()
        }
        if (fresh.isEmpty()) {
            emptyState(R.drawable.ic_public, "Nada bloqueado nas últimas 24 h", "Tente abrir o site de novo e volte aqui. Para um app ou outro assunto, use “Outro pedido”.")
        } else {
            fun source(b: BlockedItem) = when {
                b.own -> null
                b.veto -> "vetado pelo guardião-mor (${b.list})"
                else -> "lista " + b.list.ifEmpty { "do Pi-hole" }.let { if ("://" in it) it.substringAfter("://").substringBefore('/') else it }
            }
            fun summary(b: BlockedItem) = listOfNotNull("${b.count} vez${if (b.count > 1) "es" else ""}", UnblockText.ago(now, b.last), source(b)).joinToString(" · ")
            val recent = fresh.filter { now - it.last <= 900 }
            val older = fresh.filter { now - it.last > 900 }.take(60)
            if (recent.isNotEmpty()) {
                section("Agora · últimos 15 min")
                recent.take(60).forEach { b -> siteRow(b, summary(b)) }
            }
            if (older.isNotEmpty()) {
                section("Antes · últimas 24 h")
                older.forEach { b -> siteRow(b, summary(b)) }
            }
            note("Toque no nome para escolher o domínio." + if (fresh.any { !it.own }) " Itens com “lista” ou “vetado” só o guardião libera." else "")
        }
        barMode = "sites"
        refreshBar()
    }

    private fun drawApps(d: BlockedData) {
        val pending = PackageGuard.pending(this)
        pickedApps.retainAll(pending.toSet())
        if (pending.isEmpty()) {
            emptyState(R.drawable.ic_apps, "Nenhum app suspenso", "Quando um app novo ficar suspenso até o guardião aprovar, ele aparece aqui para você pedir.")
        } else {
            section("Suspensos até o guardião aprovar")
            for (pkg in pending) {
                val refused = pkg in d.refused
                checkRow(PackageGuard.label(this, pkg), pkg, pkg in pickedApps,
                    if (refused) chip("recusado", R.drawable.ic_block, Chip.NO) else chip("suspenso", R.drawable.ic_schedule, Chip.WAIT),
                ) { if (it) pickedApps.add(pkg) else pickedApps.remove(pkg) }
            }
            note("Se o guardião recusou por engano, pedir de novo o faz analisar outra vez.")
        }
        barMode = "apps"
        refreshBar()
    }

    private fun drawRequests(api: UnblockApi, gateway: String, d: BlockedData) {
        val requests = Cleared.requests(d.requests, remote.requestsClearedId).filter { it.id !in cancelling }
        val open = requests.filter { it.status == "open" }
        val answered = requests.filter { it.status != "open" }.take(8)
        if (requests.isEmpty()) {
            emptyState(R.drawable.ic_send, "Nenhum pedido ainda", "Marque o que você precisa em Sites ou Apps e toque em “Pedir liberação”.")
            return
        }
        if (open.isNotEmpty()) section("Aguardando o guardião")
        for (r in open) {
            if (r !== open.first()) section(null)
            val n = r.items.count { it.state == "open" }
            content.addView(LinearLayout(this).apply {
                tag = SettingsPage.ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(4))
                addView(chip("aguardando", R.drawable.ic_schedule, Chip.WAIT))
                addView(TextView(this@MainActivity).apply {
                    text = "pedido nº ${r.id} · $n ${if (n > 1) "itens" else "item"}"
                    textSize = 13f
                    setTextColor(getColor(R.color.nt_muted))
                    setPadding(dp(8), 0, 0, 0)
                })
            })
            r.items.forEach { content.addView(rowOf(it.domain, null)) }
            content.addView(LinearLayout(this).apply {
                tag = SettingsPage.ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
                addView(textAction("Cancelar", null, R.color.nt_red) { cancelRequest(api, r) })
                addView(textAction("Enviar ao guardião", R.drawable.ic_share, R.color.nt_green) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "Pedi a liberação de $n ${if (n > 1) "itens" else "item"} no NotToday: $gateway/#/domains/pedidos")
                    }
                    startActivity(android.content.Intent.createChooser(send, "Enviar ao guardião"))
                })
            })
        }
        if (open.isNotEmpty()) note("O guardião não é avisado sozinho: use “Enviar ao guardião” para mandar o link.")
        if (answered.isNotEmpty()) {
            section("Respondidos")
            answered.forEach { r ->
                r.items.forEach { content.addView(rowOf(it.domain, null, trailing = stateChip(it))) }
            }
            headerActions = listOf(Triple(R.drawable.ic_delete_sweep, "Limpar os respondidos") {
                val before = remote.requestsClearedId
                remote.requestsClearedId = Cleared.newestAnswered(d.requests, remote.requestsClearedId)
                render()
                showUndo("Respondidos limpos") { remote.requestsClearedId = before; render() }
            })
        }
    }

    private fun stateChip(i: com.allan.nottoday.remote.RequestItem): View = when (i.state) {
        "allowed" -> if (i.ttlS == null) chip("liberado", R.drawable.ic_check_circle, Chip.OK)
        else chip(UnblockText.stateLabel(i).removePrefix("liberado "), R.drawable.ic_schedule, Chip.OK)
        "denied" -> chip("negado", R.drawable.ic_block, Chip.NO)
        "open" -> chip("aguardando", R.drawable.ic_schedule, Chip.WAIT)
        "failed" -> chip("não aplicado", R.drawable.ic_block, Chip.WAIT)
        else -> chip("cancelado", null, Chip.NEUTRAL)
    }

    /** Cancelar some na hora e pode ser desfeito por alguns segundos; só depois o pedido é cancelado no gateway. */
    private fun cancelRequest(api: UnblockApi, r: UnblockRequest) {
        cancelling += r.id
        render()
        val commit = Runnable {
            if (r.id !in cancelling) return@Runnable
            runAsync {
                val err = try { api.cancel(r.id); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    cancelling -= r.id
                    if (err != null) toast("Não foi possível cancelar: $err")
                    if (showBlocked) { blockedData = null; render(); loadBlocked() }
                }
            }
        }
        undoBarView.postDelayed(commit, 6000)
        showUndo("Pedido cancelado") {
            undoBarView.removeCallbacks(commit)
            cancelling -= r.id
            render()
        }
    }

    /** Botão de texto de um cartão (Material 3 "text button"): rótulo com ícone opcional, 48 dp de altura. */
    private fun textAction(label: String, iconRes: Int?, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
        minimumHeight = dp(48)
        minimumWidth = dp(64)
        setPadding(dp(12), 0, dp(12), 0)
        touchable()
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        setOnClickListener { onClick() }
        if (iconRes != null) addView(icon(iconRes, color), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(getColor(color))
        })
    }

    /** Linha só de leitura (título e resumo). */
    private fun rowOf(title: String, summary: String?, titleColor: Int = R.color.nt_text, trailing: View? = null): View =
        LinearLayout(this).apply {
            tag = SettingsPage.ROW
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(56)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(twoLine(title, summary, titleColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }

    /** Barra fixa de baixo da tela de pedidos: quantos estão marcados, limpar a seleção e pedir. */
    private fun refreshBar() {
        val n = when (barMode) {
            "sites" -> picked.size
            "apps" -> pickedApps.size
            else -> 0
        }
        requestBarView.visibility = if (showBlocked && n > 0) View.VISIBLE else View.GONE
        val fab = showBlocked && n == 0 && blockedData != null && blockedTab != "requests"
        fabView.visibility = if (fab) View.VISIBLE else View.GONE
        content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, dp(if (fab) 96 else 32)) // a lista não fica sob o botão
        requestButton.text = "Pedir liberação · $n"
        requestLabel.text = if (n > MAX_ASK) "Vão só os $MAX_ASK primeiros" else ""
        requestLabel.visibility = if (n > MAX_ASK) View.VISIBLE else View.GONE
        drawBlockedHeader(n)
    }

    /** Barra de cima das Liberações: normal (voltar, título, atualizar e as ações da aba) ou seleção (X, "N selecionados"). */
    private fun drawBlockedHeader(n: Int) {
        val hdr = blockedHeader ?: return
        hdr.removeAllViews()
        fun label(s: String, size: Float) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(getColor(R.color.nt_text))
            setPadding(dp(8), 0, 0, 0)
        }
        val weight = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (n > 0) {
            hdr.addView(iconButton(R.drawable.ic_close, "Desmarcar tudo") { picked.clear(); pickedApps.clear(); render() })
            hdr.addView(label("$n selecionado${if (n > 1) "s" else ""}", 22f), weight)
            if (barMode == "sites") selectRecent?.let { hdr.addView(iconButton(R.drawable.ic_select_all, "Selecionar os mais recentes (até $MAX_ASK)", it)) }
        } else {
            hdr.addView(iconButton(R.drawable.ic_back, "Voltar") { goBack() })
            hdr.addView(label("Liberações", 28f), weight)
            hdr.addView(iconButton(R.drawable.ic_refresh, "Atualizar") { blockedData = null; blockedError = null; render(); loadBlocked() })
            headerActions.forEach { (icon, desc, f) -> hdr.addView(iconButton(icon, desc, f)) }
        }
    }

    /** Ação de barra de cima: só o ícone (48 dp), com a descrição para o leitor de tela e ao tocar e segurar. */
    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        tooltipText = desc
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun showUndo(label: String, undo: () -> Unit) {
        undoLabel.text = label
        undoAction = undo
        undoBarView.visibility = View.VISIBLE
        undoBarView.removeCallbacks(undoHide)
        undoBarView.postDelayed(undoHide, 6000)
    }

    /** Nota de rodapé, fora dos grupos, no tom secundário. */
    private fun note(s: String) = content.addLoose(
        TextView(this).apply {
            text = s
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.nt_muted))
            setPadding(dp(16), dp(12), dp(16), 0)
        },
        matchWrap,
    )

    private fun sendPicked() {
        val api = unblockApi()?.first ?: return
        val sites = barMode == "sites"
        // Nos sites vai o domínio como foi encurtado na lista (dois bloqueios do mesmo site viram um pedido só).
        val chosen = (if (sites) picked.map { DomainEdit.levels(it).getOrElse(depth[it] ?: 0) { _ -> it } }.distinct() else pickedApps.toList()).take(MAX_ASK)
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
                depth.clear()
                "Pedido enviado. Mande o link ao guardião."
            } catch (e: GatewayException) {
                if (e.status == 403 && !sites) {
                    "Não foi possível pedir: este app foi vetado pelo guardião-mor e não pode ser pedido."
                } else {
                    "Não foi possível pedir: ${e.message ?: e.javaClass.simpleName}"
                }
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
        box.addView(kind)
        val what = field("O que você precisa", "e o motivo", box) { inputType = InputType.TYPE_CLASS_TEXT }
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

    private var unlockError: String? = null

    private fun renderUnlock(owner: Boolean) {
        val locked = pins.lockedForMs()
        lockHeader(if (locked > 0) "Muitas tentativas. Tente de novo em ${(locked / 60_000) + 1} min." else "Digite o PIN da pessoa de confiança")
        if (locked > 0) {
            actionButton("Tentar de novo", R.drawable.ic_refresh, Style.TONAL) { render() }
        } else {
            val pin = pinField("PIN", loose = true, helper = unlockError, error = unlockError != null).apply {
                gravity = android.view.Gravity.CENTER
                textSize = 22f
                letterSpacing = 0.3f
                setOnEditorActionListener { _, _, _ -> performUnlock(this); true }
            }
            button("Entrar", primary = true) { performUnlock(pin) }
        }
        if (owner) renderDevWindowLogin()
        renderRequestToGuardian()
    }

    private fun performUnlock(pin: EditText) {
        if (pins.verify(pin.text.toString())) {
            unlocked = true
            unlockError = null
        } else unlockError = "PIN incorreto"
        render()
    }

    /** Cadeado no centro, nome do app e a instrução: o padrão de tela de desbloqueio. */
    private fun lockHeader(message: String) = content.addLoose(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(56), 0, dp(16))
            addView(circleIcon(R.drawable.ic_lock, R.drawable.circle_neutral, R.color.nt_green, 72, 36))
            addView(TextView(this@MainActivity).apply {
                text = "NotToday"
                textSize = 26f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.nt_text))
                setPadding(0, dp(20), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.nt_muted))
                setPadding(dp(16), dp(6), dp(16), 0)
            })
        },
        matchWrap,
    )

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
        "restricoes" to "Restrições", "lojas" to "Lojas", "filtro" to "Filtro DNS", "apps" to "Apps novos",
        "dev" to "Desenvolvimento", "chrome" to "Chrome", "sistema" to "Sistema", "seguranca" to "Segurança e PIN",
        "guardian" to "Guardian remoto", "perigo" to "Zona de perigo",
    )

    /** Ícone de cada categoria, como nas Configurações do Android (Material Icons). */
    private val pageIcons = mapOf(
        "restricoes" to R.drawable.ic_shield, "lojas" to R.drawable.ic_store, "filtro" to R.drawable.ic_dns, "apps" to R.drawable.ic_apps,
        "dev" to R.drawable.ic_code, "chrome" to R.drawable.ic_language, "sistema" to R.drawable.ic_system_update,
        "seguranca" to R.drawable.ic_lock, "guardian" to R.drawable.ic_link, "perigo" to R.drawable.ic_dangerous,
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
        renderStatus(owner)
        renderMaintenance(owner)
        renderPending(owner)
        if (remote.isPaired) {
            section(null)
            navRow("Liberações", "Sites e apps bloqueados, pedidos", R.drawable.ic_public) { openLiberacoes() }
        }
        section("Configurações")
        for ((key, label) in pages) {
            if (key == "perigo" && !owner) continue
            navRow(label, summaryOf(key), pageIcons.getValue(key), danger = key == "perigo") {
                page = key
                render()
            }
        }
    }

    private fun summaryOf(key: String): String {
        val d = draft()
        fun on(k: String) = d[k] == "true"
        return when (key) {
            "restricoes" -> "${Catalog.restrictions.count { on("r_${it.key}") }} de ${Catalog.restrictions.size} ligadas"
            "lojas" -> if (on("suspend_blocked")) "Lojas alternativas suspensas" else "Desligado"
            "filtro" -> if (on("dns_filter")) "Ligado · canário ${d["canary"].orEmpty().ifEmpty { "não definido" }}" else "Desligado"
            "apps" -> if (on("new_apps_deny")) {
                val n = PackageGuard.pending(this).size
                "Suspensos até aprovar" + if (n > 0) " · $n aguardando" else ""
            } else "Desligado"
            "dev" -> {
                val n = d["dev_windows_per_day"]?.toIntOrNull() ?: 0
                "$n ${if (n == 1) "janela" else "janelas"} por dia de ${d["dev_window_seconds"]} s" + if (devPins.isSet) "" else " · sem PIN"
            }
            "chrome" -> listOfNotNull(
                "SafeSearch".takeIf { on("chrome_safesearch") }, "DoH desligado".takeIf { on("chrome_doh_off") }, "IP bloqueado".takeIf { on("chrome_block_ip") },
            ).joinToString(" · ").ifEmpty { "Nada ativo" }
            "sistema" -> if (on("postpone_updates")) "Atualizações adiadas" else "Atualiza normalmente"
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
    private fun bindField(key: String, label: String, shown: String, type: Int, hintText: String? = null, toValue: (String) -> String?): EditText =
        field(label, hintText) {
            setText(shown)
            inputType = type
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    toValue(s?.toString().orEmpty())?.let { draft()[key] = it }
                    refreshSave()
                }
            })
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
                bindField("store_extra", "Pacotes", d["store_extra"].orEmpty().replace(",", ", "), InputType.TYPE_CLASS_TEXT) { pkgList(it) }
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
                bindField("canary", "Domínio", d["canary"].orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "ex.: canario.exemplo.com") {
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
                bindField("dev_windows_per_day", "Janelas", d["dev_windows_per_day"].orEmpty(), num) { it.toIntOrNull()?.coerceIn(0, 20)?.toString() }
                section("Duração de cada janela (segundos)")
                bindField("dev_window_seconds", "Segundos", d["dev_window_seconds"].orEmpty(), num) { it.toIntOrNull()?.coerceIn(10, 600)?.toString() }
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
                bindField("protected_pkgs", "Pacotes", d["protected_pkgs"].orEmpty().replace(",", ", "), InputType.TYPE_CLASS_TEXT) { pkgList(it) }
            }
            "seguranca" -> {
                section("Atraso para desbloqueios (horas)")
                text("Aumentar vale na hora; diminuir também espera o atraso atual.")
                bindField(Gate.KEY_DELAY, "Horas", ((d[Gate.KEY_DELAY]?.toLongOrNull() ?: 0) / 60).toString(), num) {
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
            showBlocked && (picked.isNotEmpty() || pickedApps.isNotEmpty()) -> { picked.clear(); pickedApps.clear() }  // como no Gmail: sai da seleção
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
    /**
     * O cartão do topo do início responde "estou protegido?": verde quando o Device Owner está ativo e o filtro (se ligado)
     * está confirmado; âmbar, com o motivo e o que fazer, quando não.
     */
    private fun renderStatus(owner: Boolean) {
        if (!owner) {
            statusCard(false, R.drawable.ic_warning, "Device Owner inativo", "Sem ele as restrições não valem.", "Como ativar") { showOwnerHowTo() }
            return
        }
        val active = policy.filterActive()
        val ligadas = Catalog.restrictions.count { config.isRestrictionOn(it) }
        val refresh = {
            runAsync {
                policy.heartbeat()
                runOnUiThread { render() }
            }
        }
        val err = Diag.lastError?.let { " · Erro: $it" } ?: ""
        if (!config.dnsFilterEnabled || active) {
            val filtro = if (config.dnsFilterEnabled) "Filtro confirmado" else "Filtro desligado"
            val par = if (remote.isPaired) " · pareado" else ""
            statusCard(true, R.drawable.ic_verified_user, "Protegido", "$filtro · $ligadas restrições$par$err", refresh = refresh)
        } else {
            val vpn = if (VpnWatch.isVpnUp(this)) "VPN de pé" else "VPN fora"
            statusCard(false, R.drawable.ic_gpp_maybe, "Filtro não confirmado", "$vpn · ${Health.lastDetail}$err", refresh = refresh)
        }
    }

    private fun showOwnerHowTo() {
        val cmd = "adb shell dpm set-device-owner $packageName/.AdminReceiver"
        AlertDialog.Builder(this)
            .setTitle("Ativar o Device Owner")
            .setMessage("Com o aparelho ligado ao PC por USB e a depuração ativa, rode no PC:\n\n$cmd\n\nAntes, remova as contas do aparelho (o Android exige).")
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Copiar comando") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("comando", cmd))
                toast("Comando copiado")
            }
            .show()
    }

    /** Cartão de estado no padrão do Material 3: ícone num círculo, título, resumo e, se houver, ação e atualizar. */
    private fun statusCard(ok: Boolean, iconRes: Int, titleText: String, summary: String, action: String? = null,
                           refresh: (() -> Unit)? = null, onAction: () -> Unit = {}) {
        val on = if (ok) R.color.nt_on_ok else R.color.nt_on_warn
        content.addLoose(
            LinearLayout(this).apply {
                tag = "statuscard"
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(if (ok) R.drawable.status_ok else R.drawable.status_warn)
                setPadding(dp(20), dp(18), dp(8), dp(18))
                addView(circleIcon(iconRes, if (ok) R.drawable.circle_ok else R.drawable.circle_warn, on, 48, 28))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = titleText
                        textSize = 18f
                        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                        setTextColor(getColor(R.color.nt_text))
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = summary
                        textSize = 14f
                        setLineSpacing(0f, 1.1f)
                        setTextColor(getColor(if (ok) R.color.nt_muted else R.color.nt_on_warn))
                        setPadding(0, dp(2), 0, 0)
                    })
                    if (action != null) addView(TextView(this@MainActivity).apply {
                        text = action
                        textSize = 15f
                        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                        setTextColor(getColor(if (ok) R.color.nt_green else R.color.nt_orange))
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minHeight = dp(48)
                        minimumHeight = dp(48)
                        minWidth = dp(64)
                        minimumWidth = dp(64)
                        touchable()
                        setOnClickListener { onAction() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16); marginEnd = dp(8) })
                if (refresh != null) addView(iconButton(R.drawable.ic_refresh, "Atualizar estado") { refresh() })
            },
            matchWrap.apply { topMargin = dp(4); bottomMargin = dp(4) },
        )
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
        val a = pinField("PIN de desenvolvedor", box)
        val b = pinField("Repita o PIN", box)
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

    /** Item de navegação: ícone à esquerda, título e resumo, e a seta (ou outro indicador) à direita. */
    private fun navRow(label: String, summary: String?, iconRes: Int, trailing: Int = R.drawable.ic_chevron_right,
                       danger: Boolean = false, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            tag = SettingsPage.ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            touchable()
            setOnClickListener { onClick() }
            addView(icon(iconRes, if (danger) R.color.nt_red else R.color.nt_muted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            addView(twoLine(label, summary, if (danger) R.color.nt_red else R.color.nt_text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(trailing, R.color.nt_muted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        }
        content.addView(row)
        return row
    }

    private fun icon(res: Int, color: Int) = android.widget.ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(getColor(color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun circleIcon(res: Int, bg: Int, color: Int, size: Int, iconSize: Int) = android.widget.FrameLayout(this).apply {
        setBackgroundResource(bg)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        addView(icon(res, color), android.widget.FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), android.view.Gravity.CENTER))
    }

    private enum class Style { TONAL, TEXT, TEXT_DANGER }

    /**
     * Botão que não é a ação principal: "tonal" (preenchido num tom acima do fundo, largura toda) ou de texto (centralizado,
     * sem fundo), com ícone opcional antes do rótulo.
     */
    private fun actionButton(label: String, iconRes: Int? = null, style: Style = Style.TONAL, onClick: () -> Unit) {
        val color = when (style) {
            Style.TONAL -> R.color.nt_text
            Style.TEXT -> R.color.nt_green
            Style.TEXT_DANGER -> R.color.nt_red
        }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            minimumHeight = dp(if (style == Style.TONAL) 52 else 48)
            setPadding(dp(20), 0, dp(20), 0)
            if (style == Style.TONAL) {
                setBackgroundResource(R.drawable.btn_tonal)
                isClickable = true
                isFocusable = true
            } else touchable()
            setOnClickListener { onClick() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            if (iconRes != null) addView(icon(iconRes, color), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(getColor(color))
            })
        }
        val lp = if (style == Style.TONAL) matchWrap.apply { topMargin = dp(12) }
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
        }
        content.addLoose(b, lp)
    }

    /** Frase de abertura sob o título, fora dos grupos. */
    private fun lead(s: String) = content.addLoose(
        TextView(this).apply {
            text = s
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextColor(getColor(R.color.nt_muted))
            setPadding(dp(8), 0, dp(8), dp(4))
        },
        matchWrap,
    )

    private fun pinField(label: String, parent: ViewGroup = content, loose: Boolean = false, helper: String? = null, error: Boolean = false) =
        field(label, parent = parent, loose = loose, helper = helper, error = error) {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

    /**
     * Campo de texto com rótulo visível em cima, ligado a ele para o leitor de tela (o texto de exemplo some ao digitar e
     * não serve de rótulo), e com no mínimo 48dp de altura para o toque.
     */
    private fun field(label: String, example: String? = null, parent: ViewGroup = content, loose: Boolean = false,
                      helper: String? = null, error: Boolean = false, setup: EditText.() -> Unit = {}): EditText {
        val f = EditText(this).apply {
            id = View.generateViewId()
            hint = example
            textSize = 16f
            setTextColor(getColor(R.color.nt_text))
            setHintTextColor(getColor(R.color.nt_muted))
            setBackgroundResource(R.drawable.field_bg) // contornado, como o "outlined text field" do Material 3
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setup()
            minimumHeight = dp(56) // o da View: o inputType de uma linha troca o minHeight do TextView por minLines
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.nt_muted))
                labelFor = f.id
                setPadding(dp(4), dp(4), 0, dp(6))
            })
            addView(f, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (helper != null) addView(TextView(this@MainActivity).apply {
                text = helper
                textSize = 13f
                setTextColor(getColor(if (error) R.color.nt_red else R.color.nt_muted))
                if (error) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(dp(4), dp(6), 0, 0)
            })
        }
        if (loose) content.addLoose(box, matchWrap.apply { topMargin = dp(16) })
        else parent.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return f
    }

    /** Item com interruptor à direita; tocar em qualquer parte da linha liga ou desliga. */
    private fun switchRow(label: String, checked: Boolean, hint: String? = null): Switch {
        val sw = Switch(this).apply {
            isChecked = checked
            // a linha é o controle: o interruptor só mostra o estado (senão o leitor de tela para duas vezes no mesmo item)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(this).apply {
            tag = SettingsPage.ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            touchable()
            setOnClickListener { sw.toggle() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = sw.isChecked
                }
            }
            addView(twoLine(label, hint), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
            addView(sw)
        }
        content.addView(row)
        return sw
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
