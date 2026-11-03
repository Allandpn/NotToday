package com.allan.nottoday

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
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

/** Itens da auditoria pela skill ui-ux-pro-max: rótulo visível nos campos, um só foco por interruptor e o teclado. */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class AcessibilidadeTest {
    private fun views(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return out
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
        return out
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun everyFieldHasAVisibleLabelLinkedToIt() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val all = views(a.window.decorView)
        val fields = all.filterIsInstance<EditText>()
        assertEquals(2, fields.size) // novo PIN e repetir; o atraso fica recolhido em "Avançado"
        for (f in fields) {
            val label = all.filterIsInstance<TextView>().firstOrNull { it !is EditText && it.labelFor == f.id }
            assertTrue("campo sem rótulo: ${f.hint}", label != null && label.text.isNotBlank())
            assertTrue("campo com menos de 48dp: ${f.height}", f.height >= (48 * a.resources.displayMetrics.density).toInt() - 1)
        }
        val labels = all.filterIsInstance<TextView>().filter { it.labelFor != View.NO_ID }.map { it.text.toString() }
        assertEquals(listOf("Novo PIN", "Repita o PIN"), labels)
        // "Avançado" abre o atraso, também com rótulo
        var adv: View = all.filterIsInstance<TextView>().first { it.text.toString() == "Avançado" }
        while (!adv.isClickable) adv = adv.parent as View
        adv.performClick(); idle()
        val after = views(a.window.decorView)
        val delay = after.filterIsInstance<TextView>().first { it.text.toString() == "Atraso (horas)" }
        assertEquals("24", a.findViewById<EditText>(delay.labelFor).text.toString())
    }

    @Test
    fun aSwitchRowIsOneControlForTheScreenReader() {
        val ctx = RuntimeEnvironment.getApplication()
        PinStore(ctx).set("246810")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val pinLabel = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "PIN" && it.labelFor != View.NO_ID }
        a.findViewById<EditText>(pinLabel.labelFor).setText("246810")
        fun tap(text: String) {
            var v: View = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
            while (!v.isClickable) v = v.parent as View
            v.performClick(); idle()
        }
        tap("Entrar")
        tap("Filtro DNS")
        val sw = views(a.window.decorView).filterIsInstance<Switch>().first()
        assertFalse(sw.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, sw.importantForAccessibility)
        val row = sw.parent as View
        assertTrue(row.isFocusable && row.isClickable)
        val before = sw.isChecked
        val info = AccessibilityNodeInfo.obtain()
        row.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.isCheckable)
        assertEquals(before, info.isChecked)
        assertEquals(Switch::class.java.name, info.className)
        row.performClick()
        assertEquals(!before, sw.isChecked)
    }

    @Test
    fun theKeyboardPushesTheBottomBarUp() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 40))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 800))
            .build()
        root.dispatchApplyWindowInsets(insets)
        assertEquals(60, root.paddingTop)
        assertEquals(800, root.paddingBottom)
    }
}
