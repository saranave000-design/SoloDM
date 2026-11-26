package com.saranave.solodm

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 *
 * Livello: se Solo DM ha il permesso "mostra sopra le altre app", le maschere usano
 * il livello delle app (TYPE_APPLICATION_OVERLAY), che sta SOTTO le notifiche di sistema:
 * così le notifiche restano visibili. Senza permesso (o se qualcosa va storto) si torna
 * al livello accessibilità, che copre tutto ma funziona sempre.
 */
class MaskManager(private val ctx: Context) {
    enum class Kind { PLAIN, GAME }

    /**
     * touchable = false: la maschera copre ma lascia passare i tocchi (schermata app recenti).
     * Le maschere trasparenti ai tocchi restano sul livello accessibilità: è l'unico su cui
     * Android lascia passare i tocchi attraverso una finestra opaca.
     */
    class Mask(val rect: Rect, val kind: Kind = Kind.PLAIN, val touchable: Boolean = true)

    private class Slot(
        val root: FrameLayout,
        val lp: WindowManager.LayoutParams,
        var shown: Boolean,
        val panel: GamePanel?,
        val type: Int,
        val touchable: Boolean,
        val kind: Kind
    )

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val slots = HashMap<String, Slot>()
    var color: Int = 0xFF000000.toInt()
        private set

    /** Tasto 🔇 sul pannello della home. */
    var onMute: (() -> Unit)? = null

    /** Se il livello delle app ha dato problemi in questa sessione, non lo si riprova. */
    private var appLayerBroken = false

    fun setColor(c: Int) {
        if (c == color) return
        color = c
        val theme = GameTheme.forBackground(c)
        for (s in slots.values) {
            s.root.setBackgroundColor(c)
            s.panel?.setTheme(theme)
        }
    }

    /** true se le maschere stanno sotto le notifiche di sistema. */
    fun appLayerActive(): Boolean = !appLayerBroken && canUseAppLayer()

    private fun canUseAppLayer(): Boolean =
        try { Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }

    private fun wantedType(m: Mask): Int =
        if (m.touchable && !appLayerBroken && canUseAppLayer()) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    /** Mostra esattamente le maschere indicate; le altre diventano invisibili. */
    fun apply(desired: Map<String, Mask>) {
        for ((key, s) in slots) if (key !in desired && s.shown) hide(s)
        for ((key, m) in desired) showOne(key, m)
    }

    /** Crea in anticipo (invisibili) le finestre che serviranno, così poi basta accenderle. */
    fun prepare(masks: Map<String, Mask>) {
        for ((key, m) in masks) {
            if (m.rect.width() <= 0 || m.rect.height() <= 0) continue
            val s = slots[key]
            if (s != null && compatible(s, m)) continue
            if (s != null && s.shown) continue
            if (s != null) remove(key)
            create(key, m, visible = false)
        }
    }

    /** Accende una sola maschera senza toccare le altre. */
    fun showOne(key: String, m: Mask) {
        if (m.rect.width() <= 0 || m.rect.height() <= 0) { slots[key]?.let { if (it.shown) hide(it) }; return }
        val s = slots[key]
        when {
            s == null -> create(key, m)
            !compatible(s, m) -> { remove(key); create(key, m) }
            else -> show(key, s, m.rect)
        }
    }

    fun hideKeys(keys: Collection<String>) {
        for (k in keys) slots[k]?.let { if (it.shown) hide(it) }
    }

    fun hideAllExcept(keep: Set<String>) {
        for ((k, s) in slots) if (k !in keep && s.shown) hide(s)
    }

    fun hideAll() {
        for (s in slots.values) if (s.shown) hide(s)
    }

    fun visibleKeys(): Set<String> = slots.filterValues { it.shown }.keys

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        for (s in slots.values) try { wm.removeView(s.root) } catch (_: Exception) {}
        slots.clear()
    }

    private fun compatible(s: Slot, m: Mask) =
        s.type == wantedType(m) && s.touchable == m.touchable && s.kind == m.kind

    private fun remove(key: String) {
        val s = slots.remove(key) ?: return
        try { wm.removeView(s.root) } catch (_: Exception) {}
    }

    private fun create(key: String, m: Mask, visible: Boolean = true): Slot? {
        val type = wantedType(m)
        val s = tryCreate(key, m, visible, type)
        if (s != null) return s
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            // permesso revocato o finestra rifiutata: si torna al livello accessibilità
            appLayerBroken = true
            CrashGuard.record(ctx, "Livello app non disponibile: torno al livello accessibilità", IllegalStateException("livello app"))
            return tryCreate(key, m, visible, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }
        return null
    }

    private fun tryCreate(key: String, m: Mask, visible: Boolean, type: Int): Slot? {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(color)
        root.isClickable = m.touchable
        var panel: GamePanel? = null
        if (m.kind == Kind.GAME) {
            panel = GamePanel(ctx, GameTheme.forBackground(color))
            if (key == "feed") panel.onMute = { onMute?.invoke() }
            root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val lp = baseParams(m.rect, type, m.touchable)
        if (!visible) {
            root.visibility = View.INVISIBLE
            lp.alpha = 0f
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return try {
            wm.addView(root, lp)
            val s = Slot(root, lp, visible, panel, type, m.touchable, m.kind)
            slots[key] = s
            if (visible) { panel?.ensureStarted(); watch(key, s) }
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun show(key: String, s: Slot, r: Rect) {
        val lp = s.lp
        val geometryChanged = lp.x != r.left || lp.y != r.top || lp.width != r.width() || lp.height != r.height()
        if (s.shown && !geometryChanged) return
        lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height()
        lp.alpha = 1f
        if (s.touchable) lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val wasShown = s.shown
        s.root.visibility = View.VISIBLE
        s.shown = true
        try { wm.updateViewLayout(s.root, lp) } catch (_: Exception) {}
        s.panel?.ensureStarted()
        if (!wasShown) watch(key, s)
    }

    private fun hide(s: Slot) {
        s.shown = false
        s.root.visibility = View.INVISIBLE
        s.lp.alpha = 0f
        s.lp.flags = s.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(s.root, s.lp) } catch (_: Exception) {}
    }

    /**
     * Controllo di sicurezza: se una maschera del livello app non risulta davvero visibile
     * (qualche app può nascondere le finestre delle altre app), si passa al livello accessibilità.
     */
    private fun watch(key: String, s: Slot) {
        if (s.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) return
        main.postDelayed({
            try {
                if (slots[key] === s && s.shown && (!s.root.isAttachedToWindow || s.root.windowVisibility != View.VISIBLE)) {
                    appLayerBroken = true
                    CrashGuard.record(ctx, "Maschera nascosta sul livello app: torno al livello accessibilità", IllegalStateException("livello app"))
                    val r = Rect(s.lp.x, s.lp.y, s.lp.x + s.lp.width, s.lp.y + s.lp.height)
                    remove(key)
                    create(key, Mask(r, s.kind, s.touchable))
                }
            } catch (_: Throwable) {}
        }, 250)
    }

    private fun baseParams(r: Rect, type: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(r.width(), r.height(), type, flags, PixelFormat.OPAQUE)
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
