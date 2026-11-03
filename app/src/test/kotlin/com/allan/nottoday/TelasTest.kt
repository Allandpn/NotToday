package com.allan.nottoday

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.allan.nottoday.remote.Consumer
import com.allan.nottoday.remote.Guardian
import com.allan.nottoday.remote.RemoteStore
import com.allan.nottoday.remote.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig

/** Telas do PR 3 do redesenho: Parear, Aguardando o guardião, Restrições e Filtro DNS. */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class TelasTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun views(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return out
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
        return out
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun texts(a: MainActivity) = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun tap(a: MainActivity, text: String) {
        var v: View = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
        while (!v.isClickable) v = v.parent as View
        v.performClick(); idle()
    }

    private fun unlocked(): MainActivity {
        PinStore(ctx).set("246810")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val label = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "PIN" && it.labelFor != View.NO_ID }
        a.findViewById<EditText>(label.labelFor).setText("246810")
        tap(a, "Entrar")
        return a
    }

    @Test
    fun restrictionsAreGroupedWithShortTitles() {
        val a = unlocked()
        tap(a, "Restrições")
        val t = texts(a)
        assertTrue(t.containsAll(listOf("Rede", "Apps", "Contas e usuários", "Sistema", "VPN", "Modo seguro")))
        assertFalse("o grupo já diz que é bloqueio", t.any { it.startsWith("Bloquear ") })
        assertFalse("nenhuma restrição fica de fora dos grupos", t.contains("Outras"))
        assertEquals(Catalog.restrictions.size, views(a.window.decorView).filterIsInstance<Switch>().size)
    }

    @Test
    fun theFilterMainSwitchGoesToTheDraft() {
        val a = unlocked()
        tap(a, "Filtro DNS")
        val main = views(a.window.decorView).first { it.tag == "mainswitch" }
        val sw = views(main).filterIsInstance<Switch>().first()
        val before = sw.isChecked
        main.performClick(); idle()
        assertEquals(!before, sw.isChecked)
        assertTrue("a barra de salvar aparece", views(a.window.decorView).any { it.tag == "savebar" })
        assertTrue(texts(a).contains("Saiba mais"))
    }

    @Test
    fun pairingHasTheBackArrowAndNoBackRow() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        tap(a, "Prefiro parear com o guardião")
        assertTrue(texts(a).containsAll(listOf("Parear com o guardião", "Código de pareamento", "Colar")))
        assertFalse(texts(a).contains("Voltar"))
        views(a.window.decorView).first { it.contentDescription == "Voltar" }.performClick(); idle()
        assertTrue(texts(a).contains("Definir PIN"))
    }

    @Test
    fun awaitingShowsTheStepsAndConfirmsBeforeUnpairing() {
        RemoteStore(ctx).apply {
            saveConsumer(Consumer("aaaabbbbccccddddeeee", Trust("gw.example.ts.net", "https://gw.example.ts.net",
                linkedMapOf("g_ana" to Guardian("Ana", linkedMapOf("c" to "s"))))))
            gateway = "https://gw.example.ts.net"
            fingerprint = "ut3n-vwhl-icrd-ngl4-7mdl"
            registered = true
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val t = texts(a)
        assertTrue(t.containsAll(listOf("Aguardando o guardião", "Pareado", "PIN", "Ativação", "aguardando", "depois")))
        assertTrue(views(a.window.decorView).any { it.contentDescription == "Atualizar" })
        tap(a, "Desfazer o pareamento")
        assertTrue("pede confirmação antes de desfazer", RemoteStore(ctx).isPaired)
    }
}
