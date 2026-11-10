package com.saranave.solodm

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Piccoli aiuti per costruire le schermate senza librerie esterne. */
class Ui(private val a: Activity) {
    val dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val fg = if (dark) Color.parseColor("#FFF2F2F2") else Color.parseColor("#FF1C1C1E")
    val muted = if (dark) Color.parseColor("#FF9A9AA0") else Color.parseColor("#FF6E6E73")
    val card = if (dark) Color.parseColor("#FF1E1E20") else Color.WHITE
    val accent = Color.parseColor("#FF1C1C1E")
    val accentDark = Color.parseColor("#FFF2F2F2")

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(a)
        sv.isFillViewport = true
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(36), dp(22), dp(32))
        sv.addView(col)
        return sv to col
    }

    fun title(col: LinearLayout, s: String) = text(col, s, 28f, fg, bold = true, bottom = 6)

    fun text(
        col: LinearLayout, s: CharSequence, size: Float = 15f, color: Int = fg,
        bold: Boolean = false, bottom: Int = 10
    ): TextView {
        val tv = TextView(a)
        tv.text = s
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tv.setTextColor(color)
        tv.setLineSpacing(0f, 1.15f)
        if (bold) tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        col.addView(tv, lp(bottom))
        return tv
    }

    fun cardBox(col: LinearLayout): LinearLayout {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(16), dp(18), dp(10))
        box.background = GradientDrawable().apply {
            setColor(card); cornerRadius = dp(18).toFloat()
        }
        col.addView(box, lp(14))
        return box
    }

    fun button(col: LinearLayout, s: String, primary: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(a)
        b.text = s
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val bg = if (primary) (if (dark) accentDark else accent) else Color.TRANSPARENT
        val tc = if (primary) (if (dark) accent else Color.WHITE) else fg
        b.setTextColor(tc)
        b.background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(14).toFloat()
            if (!primary) setStroke(dp(1), muted)
        }
        b.stateListAnimator = null
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { onClick() }
        col.addView(b, lp(10))
        return b
    }

    fun link(col: LinearLayout, s: String, onClick: () -> Unit): TextView {
        val tv = text(col, s, 13f, muted, bottom = 0)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(28), 0, dp(8))
        tv.setOnClickListener { onClick() }
        return tv
    }

    fun spacer(col: LinearLayout, h: Int) {
        col.addView(View(a), LinearLayout.LayoutParams(1, dp(h)))
    }

    private fun lp(bottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }
}
