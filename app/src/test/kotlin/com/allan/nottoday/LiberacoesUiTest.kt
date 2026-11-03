package com.allan.nottoday

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.allan.nottoday.remote.BlockedItem
import com.allan.nottoday.remote.Consumer
import com.allan.nottoday.remote.Guardian
import com.allan.nottoday.remote.RemoteStore
import com.allan.nottoday.remote.RequestItem
import com.allan.nottoday.remote.Trust
import com.allan.nottoday.remote.UnblockRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig

/** A tela de Liberações no padrão do Material 3: ações da lista na barra de cima, modo de seleção e "Desfazer". */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class LiberacoesUiTest {
    private lateinit var a: MainActivity
    private val now = System.currentTimeMillis() / 1000
    private val items = listOf(
        BlockedItem("cdn.video.exemplo.com", true, "", false, 3, now - 120),
        BlockedItem("img.rastreador.net", false, "https://listas.exemplo/ads.txt", false, 9, now - 600),
        BlockedItem("jogos.exemplo.net", true, "", false, 5, now - 3 * 3600),
    )
    private val reqs = listOf(UnblockRequest(6, "done", listOf(RequestItem("docs.exemplo.org", "allowed", null))))

    private fun views(v: View = a.window.decorView, out: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return out
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
        return out
    }

    private fun header(): ViewGroup = views().first { it.tag == "blockedheader" } as ViewGroup
    private fun action(desc: String): View? = views(header()).firstOrNull { it.contentDescription == desc }
    private fun headerText(): String = views(header()).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }
    private fun texts(): List<String> = views().filterIsInstance<TextView>().map { it.text.toString() }
    /** A caixa de marcar da linha (à esquerda): marca sem abrir a escolha do domínio. */
    private fun row(text: String): View = views().first { it.contentDescription == "Marcar $text" }
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        RemoteStore(ctx).apply {
            saveConsumer(Consumer("aaaabbbbccccddddeeee", Trust("gw.example.ts.net", "https://gw.example.ts.net",
                linkedMapOf("g_ana" to Guardian("Ana", linkedMapOf("c" to "s"))))))
            gateway = "https://gw.example.ts.net"
            registered = true
        }
        PinStore(ctx).set("246810")
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.debugShowBlocked(items, reqs, "domains")
        idle()
    }

    @Test
    fun listActionsSitInTheTopBarNextToRefresh() {
        assertTrue(headerText().contains("Liberações"))
        assertTrue(action("Atualizar") != null)
        assertTrue(action("Limpar lista") != null)
        assertFalse("o botão de limpar saiu do meio da lista", texts().any { it == "Esconder desta lista" || it == "Selecionar os mais recentes" })
        assertFalse("sem nada marcado, não há barra de pedido", views().any { it.tag == "requestbar" })
    }

    @Test
    fun selectingSwitchesTheTopBarToSelectionMode() {
        row("cdn.video.exemplo.com").performClick()
        assertTrue(headerText().contains("1 selecionado"))
        assertTrue(action("Desmarcar tudo") != null)
        assertNull("no modo de seleção as ações da lista somem", action("Limpar lista"))
        val bar = views().first { it.tag == "requestbar" }
        val labels = views(bar).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains("Pedir liberação · 1"))
        assertFalse("o desmarcar não fica mais embaixo", labels.contains("Limpar"))

        action("Selecionar os mais recentes (até 20)")!!.performClick()
        assertTrue(headerText().contains("2 selecionados"))   // só os de regra própria: o de lista não se pede
        action("Desmarcar tudo")!!.performClick()
        assertTrue(action("Limpar lista") != null)
        assertFalse(views().any { it.tag == "requestbar" })
    }

    @Test
    fun backLeavesSelectionModeBeforeLeavingTheScreen() {
        row("cdn.video.exemplo.com").performClick()
        assertTrue(action("Desmarcar tudo") != null)
        // o voltar do sistema e a seta chamam o mesmo goBack
        val goBack = MainActivity::class.java.getDeclaredMethod("goBack").apply { isAccessible = true }
        goBack.invoke(a)
        assertTrue("continua nas Liberações", headerText().contains("Liberações"))
        assertTrue(action("Limpar lista") != null)
    }

    @Test
    fun clearingTheListCanBeUndone() {
        action("Limpar lista")!!.performClick()
        assertFalse(texts().contains("cdn.video.exemplo.com"))
        val undo = views().first { it.tag == "undobar" }
        assertTrue(views(undo).filterIsInstance<TextView>().any { it.text.toString() == "Lista limpa" })
        views(undo).filterIsInstance<TextView>().first { it.text.toString() == "Desfazer" }.performClick()
        assertTrue(texts().contains("cdn.video.exemplo.com"))
        assertFalse(views().any { it.tag == "undobar" })
    }

    @Test
    fun answeredRequestsAreClearedFromTheTopBarToo() {
        a.debugShowBlocked(items, reqs, "requests")
        idle()
        assertTrue(texts().contains("docs.exemplo.org"))
        assertFalse(texts().contains("Limpar os respondidos"))   // não é mais um item da lista
        action("Limpar os respondidos")!!.performClick()
        assertFalse(texts().contains("docs.exemplo.org"))
        assertEquals("Respondidos limpos", views(views().first { it.tag == "undobar" }).filterIsInstance<TextView>().first().text.toString())
    }

    private fun sheetViews(): List<View> = views(a.sheetShown!!.window!!.decorView)
    private fun tapIn(list: List<View>, text: String) {
        var v: View = list.filterIsInstance<TextView>().first { it.text.toString() == text }
        while (!v.isClickable) v = v.parent as View
        v.performClick(); idle()
    }

    @Test
    fun tappingTheNameOpensTheDomainSheetAndMarksTheChosenLevel() {
        tapIn(views(), "cdn.video.exemplo.com")
        assertTrue(a.sheetShown!!.isShowing)
        val sv = sheetViews().filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(sv.containsAll(listOf("Pedir qual domínio?", "cdn.video.exemplo.com", "video.exemplo.com", "exemplo.com", "Só este endereço")))
        tapIn(sheetViews(), "video.exemplo.com")
        tapIn(sheetViews(), "Marcar")
        assertFalse(a.sheetShown!!.isShowing)
        assertTrue(texts().contains("video.exemplo.com"))
        assertTrue(texts().any { it.startsWith("vale para *.video.exemplo.com") })
        assertTrue(headerText().contains("1 selecionado"))
    }

    @Test
    fun listBlocksShowAChipAndCannotBeMarked() {
        assertTrue(texts().contains("lista"))
        assertFalse(views().any { it.contentDescription == "Marcar img.rastreador.net" })
    }

    @Test
    fun theFabIsOnlyOnSitesAndAppsAndHidesWhileSelecting() {
        fun fab() = views().any { it.tag == "fab" }
        assertTrue(fab())
        row("cdn.video.exemplo.com").performClick()
        assertFalse("no modo de seleção o botão flutuante some", fab())
        action("Desmarcar tudo")!!.performClick()
        a.debugShowBlocked(items, reqs, "requests"); idle()
        assertFalse(fab())
    }

    @Test
    fun requestsShowStateChipsAndCancelCanBeUndone() {
        val open = listOf(UnblockRequest(7, "open", listOf(RequestItem("noticias.exemplo.com", "open", null)))) + reqs
        a.debugShowBlocked(items, open, "requests"); idle()
        assertTrue(texts().containsAll(listOf("aguardando", "liberado", "Enviar ao guardião", "Cancelar")))
        assertTrue(views().any { it.contentDescription?.toString() == "Pedidos, 1 aberto" })
        tapIn(views(), "Cancelar")
        assertFalse(texts().contains("noticias.exemplo.com"))
        val undo = views().first { it.tag == "undobar" }
        assertTrue(views(undo).filterIsInstance<TextView>().any { it.text.toString() == "Pedido cancelado" })
        views(undo).filterIsInstance<TextView>().first { it.text.toString() == "Desfazer" }.performClick(); idle()
        assertTrue(texts().contains("noticias.exemplo.com"))
    }
}
