package com.saranave.solodm

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.saranave.solodm.games.GamePanel
import com.saranave.solodm.games.GameTheme

/**
 * Maschere disegnate sopra Instagram, nello stesso colore dello sfondo.
 * Le finestre restano sempre "montate": quando non servono diventano invisibili
 * e lasciano passare i tocchi. Accenderle è molto più veloce che crearle da zero.
 */
class MaskManager(private val ctx: Context) {
    enum class Kind { PLAIN, GAME }

    class Mask(val rect: Rect, val kind: Kind = Kind.PLAIN)

    private class Slot(val root: FrameLayout, val lp: WindowManager.LayoutParams, var shown: Boolean, val panel: GamePanel?)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val slots = HashMap<String, Slot>()
    var color: Int = 0xFF000000.toInt()
        private set

    fun setColor(c: Int) {
        if (c == color) return
        color = c
        val theme = GameTheme.forBackground(c)
        for (s in slots.values) {
            s.root.setBackgroundColor(c)
            s.panel?.setTheme(theme)
        }
    }

    /** Mostra esattamente le maschere indicate; le altre diventano invisibili. */
    fun apply(desired: Map<String, Mask>) {
        for ((key, s) in slots) if (key !in desired && s.shown) hide(s)
        for ((key, m) in desired) {
            if (m.rect.width() <= 0 || m.rect.height() <= 0) { slots[key]?.let { if (it.shown) hide(it) }; continue }
            val existing = slots[key]
            if (existing == null) create(key, m) else show(existing, m.rect)
        }
    }

    /** Crea in anticipo (invisibili) le finestre che serviranno, così poi basta accenderle. */
    fun prepare(masks: Map<String, Mask>) {
        for ((key, m) in masks) {
            if (key in slots || m.rect.width() <= 0 || m.rect.height() <= 0) continue
            create(key, m, visible = false)
        }
    }

    fun hideAll() {
        for (s in slots.values) if (s.shown) hide(s)
    }

    fun visibleKeys(): Set<String> = slots.filterValues { it.shown }.keys

    fun destroy() {
        for (s in slots.values) try { wm.removeView(s.root) } catch (_: Exception) {}
        slots.clear()
    }

    private fun create(key: String, m: Mask, visible: Boolean = true): Slot? {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(color)
        root.isClickable = true
        var panel: GamePanel? = null
        if (m.kind == Kind.GAME) {
            panel = GamePanel(ctx, GameTheme.forBackground(color))
            root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val lp = baseParams(m.rect)
        if (!visible) {
            root.visibility = View.INVISIBLE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return try {
            wm.addView(root, lp)
            val s = Slot(root, lp, visible, panel)
            slots[key] = s
            if (visible) panel?.ensureStarted()
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun show(s: Slot, r: Rect) {
        val lp = s.lp
        val geometryChanged = lp.x != r.left || lp.y != r.top || lp.width != r.width() || lp.height != r.height()
        if (s.shown && !geometryChanged) return
        lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height()
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        s.root.visibility = View.VISIBLE
        s.shown = true
        try { wm.updateViewLayout(s.root, lp) } catch (_: Exception) {}
        s.panel?.ensureStarted()
    }

    private fun hide(s: Slot) {
        s.shown = false
        s.root.visibility = View.INVISIBLE
        s.lp.flags = s.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(s.root, s.lp) } catch (_: Exception) {}
    }

    private fun baseParams(r: Rect): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = r.left
        lp.y = r.top
        lp.windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        }
        return lp
    }
}
