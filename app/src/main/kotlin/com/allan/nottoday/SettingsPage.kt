package com.allan.nottoday

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Página no estilo das Configurações do Android (Opções do desenvolvedor): título grande, nome da categoria em destaque
 * e os itens em grupos arredondados, separados por linhas finas. Tudo que entra por [addView] vai para o grupo atual;
 * se não houver grupo aberto, abre um sem nome. [addLoose] põe algo fora dos grupos (título, botão principal).
 */
class SettingsPage(context: Context) : LinearLayout(context) {
    private var group: LinearLayout? = null

    init {
        orientation = VERTICAL
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun addLoose(v: View, lp: LayoutParams) {
        group = null
        super.addView(v, -1, lp)
    }

    /** Começa uma categoria: o nome (em destaque) e um grupo novo logo abaixo. */
    fun startGroup(header: String?) {
        if (header != null) super.addView(
            TextView(context).apply {
                text = header
                textSize = 14f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(context.getColor(R.color.nt_green))
                setPadding(dp(16), dp(24), dp(16), dp(8))
            },
            -1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        val g = LinearLayout(context).apply {
            orientation = VERTICAL
            tag = GROUP
            setBackgroundResource(R.drawable.group_bg)
            clipToOutline = true // o efeito de toque dos itens respeita os cantos arredondados
        }
        super.addView(g, -1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (header == null) topMargin = dp(12) })
        group = g
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (group == null) startGroup(null)
        val g = group!!
        if (g.childCount > 0 && child.tag == ROW) g.addView( // campo logo abaixo do texto que o explica: sem linha entre eles
            View(context).apply { setBackgroundColor(context.getColor(R.color.nt_line)) },
            LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, params.height)
        if (child.tag != ROW) lp.setMargins(dp(16), dp(8), dp(16), dp(8)) // campo de texto, lista etc.: afastado das bordas
        g.addView(child, lp)
    }

    override fun removeAllViews() {
        for (i in 0 until childCount) (getChildAt(i) as? ViewGroup)?.takeIf { it.tag == GROUP }?.removeAllViews()
        group = null
        super.removeAllViews()
    }

    companion object {
        const val ROW = "row"
        private const val GROUP = "group"
    }
}
