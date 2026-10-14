package com.allan.nottoday

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import com.allan.nottoday.remote.Consumer
import com.allan.nottoday.remote.Guardian
import com.allan.nottoday.remote.RemoteStore
import com.allan.nottoday.remote.Trust
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.robolectric.annotation.Config as RoboConfig

/**
 * Capturas de tela das telas do app (gráficos nativos do Robolectric), para conferir o visual sem emulador.
 * Só roda com NOTTODAY_SHOTS=<pasta>: `NOTTODAY_SHOTS=/tmp/capturas ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RoboConfig(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    private val dir: String? = System.getenv("NOTTODAY_SHOTS")

    private fun views(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i), out)
        return out
    }

    private fun button(a: Activity, text: String) = views(a.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }

    private fun shot(a: Activity, name: String) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val scroll = views(a.window.decorView).filterIsInstance<ScrollView>().first()
        val child = scroll.getChildAt(0)
        val w = scroll.width.coerceAtLeast(1)
        child.measure(View.MeasureSpec.makeMeasureSpec(w - scroll.paddingLeft - scroll.paddingRight, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = (child.measuredHeight + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(scroll.height)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(a.getColor(R.color.nt_bg))
        c.translate(scroll.paddingLeft.toFloat(), scroll.paddingTop.toFloat())
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.draw(c)
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun paired(fingerprint: String = "ut3n-vwhl-icrd-ngl4-7mdl") {
        val ctx = RuntimeEnvironment.getApplication()
        val store = RemoteStore(ctx)
        store.saveConsumer(Consumer("aaaabbbbccccddddeeee", Trust("gw.example.ts.net", "https://gw.example.ts.net",
            linkedMapOf("g_ana" to Guardian("Ana", linkedMapOf("c" to "s"))))))
        store.gateway = "https://gw.example.ts.net"
        store.fingerprint = fingerprint
        store.registered = true
    }

    @Test
    fun screens() {
        assumeTrue("NOTTODAY_SHOTS não definido", dir != null)
        val ctx = RuntimeEnvironment.getApplication()

        // 1) primeira abertura: definir PIN (com o botão de parear)
        var a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(a, "01-definir-pin")
        button(a, "Parear com Guardian remoto").performClick()
        shot(a, "02-parear")
        a.finish()

        // 2) pareado e sem PIN: aguardando o guardião
        paired()
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(a, "03-aguardando-guardiao")
        a.finish()

        // 3) com PIN: tela de bloqueio e configurações
        PinStore(ctx).set("246810")
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(a, "04-bloqueado")
        views(a.window.decorView).filterIsInstance<EditText>().first { it.hint == "PIN" }.setText("246810")
        button(a, "Entrar").performClick()
        shot(a, "05-configuracoes")
    }
}
