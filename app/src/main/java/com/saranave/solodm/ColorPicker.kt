package com.saranave.solodm

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Mirino per campionare a mano il colore di sfondo di Instagram.
 * Trascini il mirino su un punto vuoto, premi ✓: le finestre del mirino spariscono,
 * si legge quel pixel esatto dallo schermo e si mostra l'anteprima. "Salva" lo tiene per sempre.
 */
class ColorPicker(
    private val svc: AccessibilityService,
    private val onSave: (Int) -> Unit,
    private val onClose: () -> Unit
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val shots = Executors.newSingleThreadExecutor()
    private val d = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val size = dp(84)
    private var cross: View? = null
    private var crossLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var closed = false

    /** Centro del mirino in coordinate dello schermo. */
    private var cx = 0
    private var cy = 0

    fun start() {
        val dm = svc.resources.displayMetrics
        cx = dm.widthPixels / 2
        cy = dm.heightPixels * 2 / 5
        showCross()
        showPanel(choose = true, color = null)
    }

    fun close() {
        if (closed) return
        closed = true
        removeCross(); removePanel()
        shots.shutdown()
        onClose()
    }

    // ------------------------------------------------------------------ mirino

    @SuppressLint("ClickableViewAccessibility")
    private fun showCross() {
        if (cross != null) return
        val v = object : View(svc) {
            private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f * d / 2 }
            private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.5f * d / 2 }
            override fun onDraw(c: Canvas) {
                val m = width / 2f
                val r = width / 2f - 4 * d
                val gap = 6 * d
                for (p in listOf(white, black)) {
                    c.drawCircle(m, m, r, p)
                    c.drawLine(m, m - r, m, m - gap, p); c.drawLine(m, m + gap, m, m + r, p)
                    c.drawLine(m - r, m, m - gap, m, p); c.drawLine(m + gap, m, m + r, m, p)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = cx - size / 2; lp.y = cy - size / 2
        lp.windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        }
        var lastX = 0f; var lastY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dm = svc.resources.displayMetrics
                    cx = (cx + (e.rawX - lastX)).toInt().coerceIn(0, dm.widthPixels - 1)
                    cy = (cy + (e.rawY - lastY)).toInt().coerceIn(0, dm.heightPixels - 1)
                    lastX = e.rawX; lastY = e.rawY
                    lp.x = cx - size / 2; lp.y = cy - size / 2
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
            }
            true
        }
        try { wm.addView(v, lp); cross = v; crossLp = lp } catch (_: Exception) {}
    }

    private fun removeCross() {
        cross?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cross = null
    }

    // ------------------------------------------------------------------ pannello

    private fun button(s: String, onClick: () -> Unit) = TextView(svc).apply {
        text = s; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { setStroke(dp(1), Color.WHITE); cornerRadius = dp(10).toFloat() }
        setOnClickListener { try { onClick() } catch (t: Throwable) { CrashGuard.record(svc, "mirino", t) } }
    }

    private fun showPanel(choose: Boolean, color: Int?) {
        removePanel()
        val p = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xEE202020.toInt()); cornerRadius = dp(16).toFloat() }
        }
        fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        val msg = TextView(svc).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        p.addView(msg, lp())
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (choose) {
            msg.text = "Trascina il mirino su un punto di sfondo vuoto,\npoi premi ✓"
            row.addView(button("✓ Campiona") { sample() }, lp())
            row.addView(button("✕") { close() }, lp())
        } else if (color != null) {
            msg.text = "Colore trovato: #%06X".format(color and 0xFFFFFF)
            val sw = View(svc).apply {
                background = GradientDrawable().apply { setColor(color); setStroke(dp(1), Color.WHITE); cornerRadius = dp(8).toFloat() }
            }
            p.addView(sw, LinearLayout.LayoutParams(dp(150), dp(44)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            row.addView(button("Salva") { onSave(color); close() }, lp())
            row.addView(button("Riprova") { showCross(); showPanel(true, null) }, lp())
            row.addView(button("✕") { close() }, lp())
        } else {
            msg.text = "Lettura dello schermo non riuscita.\nRiprova."
            row.addView(button("Riprova") { showCross(); showPanel(true, null) }, lp())
            row.addView(button("✕") { close() }, lp())
        }
        p.addView(row, lp())
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        wlp.y = dp(110)
        wlp.windowAnimations = 0
        try { wm.addView(p, wlp); panel = p; panelLp = wlp } catch (_: Exception) {}
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    // ------------------------------------------------------------------ campionamento

    private fun sample() {
        if (Build.VERSION.SDK_INT < 30) { showPanel(false, null); return }
        // via mirino e pannello, poi si legge lo schermo "pulito"
        removeCross(); removePanel()
        val x = cx; val y = cy
        main.postDelayed({
            if (closed) return@postDelayed
            try {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, shots, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        var c: Int? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            if (bmp != null && x in 0 until bmp.width && y in 0 until bmp.height) c = bmp.getPixel(x, y) or 0xFF000000.toInt()
                            bmp?.recycle(); hw?.recycle()
                        } catch (_: Throwable) {
                        } finally {
                            try { buffer.close() } catch (_: Throwable) {}
                        }
                        main.post { if (!closed) showPanel(false, c) }
                    }

                    override fun onFailure(errorCode: Int) {
                        main.post { if (!closed) showPanel(false, null) }
                    }
                })
            } catch (_: Throwable) {
                showPanel(false, null)
            }
        }, 180)
    }
}
