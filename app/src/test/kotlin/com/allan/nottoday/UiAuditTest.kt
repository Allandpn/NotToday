package com.allan.nottoday

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.allan.nottoday.remote.BlockedItem
import com.allan.nottoday.remote.Consumer
import com.allan.nottoday.remote.Guardian
import com.allan.nottoday.remote.RemoteStore
import com.allan.nottoday.remote.RequestItem
import com.allan.nottoday.remote.Trust
import com.allan.nottoday.remote.UnblockRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import org.robolectric.annotation.Config as RoboConfig

/**
 * Auditoria de UI pelo checklist da skill ui-ux-pro-max: alvo de toque (48dp), nome acessível, tamanho de texto,
 * contraste do texto com o fundo e emoji usado como ícone. Só roda com NOTTODAY_AUDIT=<arquivo>; grava um achado por linha.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class UiAuditTest {
    private val out: String? = System.getenv("NOTTODAY_AUDIT")
    private val found = linkedSetOf<String>()

    private fun views(v: View, o: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return o
        o.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), o)
        return o
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun color(d: Drawable?): Int? = when (d) {
        null -> null
        is ColorDrawable -> d.color
        is GradientDrawable -> d.color?.defaultColor
        is RippleDrawable -> (0 until d.numberOfLayers).firstNotNullOfOrNull { if (d.getId(it) == android.R.id.mask) null else color(d.getDrawable(it)) }
        is LayerDrawable -> (d.numberOfLayers - 1 downTo 0).firstNotNullOfOrNull { color(d.getDrawable(it)) }
        is InsetDrawable -> color(d.drawable)
        else -> null
    }

    private fun bg(v: View, a: Activity): Int {
        var p: View? = v
        while (p != null) {
            color(p.background)?.let { if (Color.alpha(it) > 200) return it }
            p = p.parent as? View
        }
        return a.getColor(R.color.nt_bg)
    }

    private fun lum(c: Int): Double {
        fun ch(x: Int): Double { val s = x / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun ratio(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    private fun name(v: View): String {
        v.contentDescription?.let { if (it.isNotBlank()) return it.toString() }
        if (v is TextView && v.text.isNotBlank()) return v.text.toString()
        if (v is ViewGroup) return views(v).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }.trim()
        return ""
    }

    private val emoji = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}]")

    private fun audit(a: Activity, screen: String) {
        idle()
        val d = a.resources.displayMetrics.density
        val sd = a.resources.displayMetrics.scaledDensity
        for (v in views(a.window.decorView)) {
            val label = name(v).replace("\n", " ").take(40)
            val coveredByRow = generateSequence(v.parent as? View) { it.parent as? View }
                .any { it.isClickable && it.height / d >= 48 && it.width / d >= 48 }
            if (v.isClickable && v.isEnabled && v.width > 0) {
                val w = (v.width / d).toInt(); val h = (v.height / d).toInt()
                if (coveredByRow) {
                    if (v.isFocusable && v.isImportantForAccessibility) found += "$screen|foco-duplo|\"$label\" (a linha e o controle recebem foco separados)"
                } else if (w < 48 || h < 48) found += "$screen|toque<48dp|\"$label\" ${w}x${h}dp"
                if (v !is EditText && label.isBlank()) found += "$screen|sem-nome|${v.javaClass.simpleName} ${w}x${h}dp"
            }
            if (v is EditText) {
                val hasVisibleLabel = views(a.window.decorView).any { it is TextView && it !is EditText && it.labelFor == v.id && v.id != View.NO_ID }
                if (!hasVisibleLabel && v.contentDescription.isNullOrBlank()) found += "$screen|rótulo-só-no-placeholder|campo \"${v.hint ?: ""}\""
                if (v.height / d < 48) found += "$screen|campo<48dp|\"${v.hint ?: ""}\" ${(v.height / d).toInt()}dp"
            }
            if (v is TextView && v !is EditText && v.text.isNotBlank() && v.width > 0) {
                val txt = v.text.toString().replace("\n", " ").take(40)
                val sp = v.textSize / sd
                if (sp < 12f) found += "$screen|texto<12sp|\"$txt\" ${"%.1f".format(sp)}sp"
                val r = ratio(v.currentTextColor or (0xFF shl 24), bg(v, a))
                val large = sp >= 18f || (sp >= 14f && (v.typeface?.isBold == true))
                if (r < (if (large) 3.0 else 4.5)) found += "$screen|contraste|\"$txt\" ${"%.2f".format(r)}:1 (${"%.0f".format(sp)}sp)"
                if (emoji.containsMatchIn(v.text)) found += "$screen|emoji|\"$txt\""
                val l = v.layout
                if (l != null && ((0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } || l.height > v.height - v.paddingTop - v.paddingBottom + 2))
                    found += "$screen|texto-cortado|\"$txt\""
            }
        }
    }

    private fun tap(a: Activity, text: String) {
        var v: View = views(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    @Test
    fun normal() = run("", "")

    @Test
    @RoboConfig(fontScale = 1.3f)
    fun bigFont() = run("@fonte130%", "-fonte130")

    @Test
    @RoboConfig(qualifiers = "w320dp-h568dp-mdpi")
    fun smallPhone() = run("@320dp", "-320dp")

    @Test
    @RoboConfig(qualifiers = "w740dp-h360dp-land-xxhdpi")
    fun landscape() = run("@paisagem", "-paisagem")

    private fun run(s: String, suffix: String) {
        assumeTrue("NOTTODAY_AUDIT não definido", out != null)
        val ctx = RuntimeEnvironment.getApplication()
        run {
            var a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            audit(a, "definir-pin$s")
            tap(a, "Parear com Guardian remoto"); audit(a, "parear$s")
            a.finish()

            val store = RemoteStore(ctx)
            store.saveConsumer(Consumer("aaaabbbbccccddddeeee", Trust("gw.example.ts.net", "https://gw.example.ts.net",
                linkedMapOf("g_ana" to Guardian("Ana", linkedMapOf("c" to "s"))))))
            store.gateway = "https://gw.example.ts.net"; store.fingerprint = "ut3n-vwhl-icrd-ngl4-7mdl"; store.registered = true
            PinStore(ctx).clear()
            a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            audit(a, "aguardando-guardiao$s"); a.finish()

            PinStore(ctx).set("246810")
            a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            audit(a, "bloqueado$s")
            a.findViewById<EditText>(views(a.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "PIN" && it.labelFor != View.NO_ID }.labelFor).setText("246810")
            tap(a, "Entrar"); audit(a, "configuracoes$s")
            for (sec in listOf("Restrições", "Filtro DNS")) {
                tap(a, sec); audit(a, "$sec$s")
                views(a.window.decorView).first { it.contentDescription == "Voltar" }.performClick(); idle()
            }
            val now = System.currentTimeMillis() / 1000
            val items = listOf(BlockedItem("cdn.video.exemplo.com", true, "", false, 3, now - 120),
                BlockedItem("img.rastreador.net", false, "https://listas.exemplo/ads.txt", false, 9, now - 600))
            val reqs = listOf(UnblockRequest(7, "open", listOf(RequestItem("noticias.exemplo.com", "open", null))),
                UnblockRequest(6, "done", listOf(RequestItem("docs.exemplo.org", "allowed", null), RequestItem("apostas.exemplo.com", "denied", null))))
            a.debugShowBlocked(items, reqs, "domains"); audit(a, "liberacoes-sites$s")
            a.debugShowBlocked(items, reqs, "domains", setOf("cdn.video.exemplo.com")); audit(a, "liberacoes-selecao$s")
            a.debugShowBlocked(items, reqs, "requests"); audit(a, "liberacoes-pedidos$s")
            a.debugShowBlocked(items, emptyList(), "apps"); audit(a, "liberacoes-apps$s")
            a.finish()
            PinStore(ctx).clear(); store.clear()
        }
        File(out!! + suffix).writeText(found.joinToString("\n") + "\n")
    }
}
