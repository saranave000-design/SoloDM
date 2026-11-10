package com.saranave.solodm

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Rettangoli coprenti disegnati sopra Instagram. Bloccano anche il tocco:
 * quello che c'è sotto non si vede e non si può premere né scorrere.
 */
class MaskManager(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val shown = HashMap<String, Pair<View, Rect>>()

    class Mask(val rect: Rect, val label: String?)

    fun apply(desired: Map<String, Mask>) {
        // Togli quelle non più necessarie
        val toRemove = shown.keys.filter { it !in desired.keys }
        for (k in toRemove) remove(k)
        // Aggiungi / sposta le altre
        for ((key, m) in desired) {
            if (m.rect.width() <= 0 || m.rect.height() <= 0) {
                remove(key); continue
            }
            val cur = shown[key]
            if (cur == null) {
                val v = makeView(m.label, m.rect)
                try {
                    wm.addView(v, params(m.rect))
                    shown[key] = v to Rect(m.rect)
                } catch (_: Exception) {
                }
            } else if (cur.second != m.rect) {
                try {
                    wm.updateViewLayout(cur.first, params(m.rect))
                    shown[key] = cur.first to Rect(m.rect)
                } catch (_: Exception) {
                    remove(key)
                }
            }
        }
    }

    fun clear() {
        for (k in shown.keys.toList()) remove(k)
    }

    fun isEmpty() = shown.isEmpty()

    private fun remove(key: String) {
        val v = shown.remove(key)?.first ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun params(r: Rect): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = r.left
        lp.y = r.top
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        }
        return lp
    }

    private fun makeView(label: String?, r: Rect): View {
        val dp = ctx.resources.displayMetrics.density
        val small = r.height() < 140 * dp
        val box = FrameLayout(ctx)
        box.background = GradientDrawable().apply {
            setColor(Color.parseColor("#FF1C1C1E"))
            cornerRadius = if (small) 14 * dp else 0f
        }
        if (label != null && !small) {
            val tv = TextView(ctx)
            tv.text = label
            tv.setTextColor(Color.parseColor("#FF8E8E93"))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tv.gravity = Gravity.CENTER
            box.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        // Assorbe i tocchi
        box.isClickable = true
        box.setOnTouchListener { _, _ -> true }
        return box
    }
}
