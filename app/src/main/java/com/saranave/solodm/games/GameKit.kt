package com.saranave.solodm.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Solo bianco e nero: fg = colore del testo/linee, bg = sfondo. */
class GameTheme(val bg: Int, val fg: Int) {
    val dim: Int = blend(fg, bg, 0.45f)
    val faint: Int = blend(fg, bg, 0.12f)

    companion object {
        fun forBackground(bg: Int): GameTheme {
            val lum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
            return GameTheme(bg, if (lum < 128) Color.WHITE else Color.BLACK)
        }

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
            (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
            (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt()
        )
    }
}

/** Contesto che ogni gioco riceve per costruire la sua schermata. */
class Kit(val ctx: Context, val t: GameTheme) {
    fun dp(v: Float) = v * ctx.resources.displayMetrics.density
    fun dpi(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun text(s: String, size: Float = 16f, bold: Boolean = false, color: Int = t.fg): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    /** Pulsante con bordo sottile. filled = pieno (invertito). */
    fun button(s: String, size: Float = 18f, filled: Boolean = false, onClick: () -> Unit): TextView {
        val tv = text(s, size, bold = true, color = if (filled) t.bg else t.fg)
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(if (filled) t.fg else Color.TRANSPARENT)
            setStroke(dpi(1).coerceAtLeast(1), if (filled) t.fg else t.dim)
        }
        tv.setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
        tv.isClickable = true
        tv.setOnClickListener {
            try {
                onClick()
            } catch (t: Throwable) {
                com.saranave.solodm.CrashGuard.record(ctx, "gioco (pulsante)", t)
            }
        }
        return tv
    }

    fun setFilled(tv: TextView, filled: Boolean) {
        (tv.background as? GradientDrawable)?.apply {
            setColor(if (filled) t.fg else Color.TRANSPARENT)
            setStroke(dpi(1).coerceAtLeast(1), if (filled) t.fg else t.dim)
        }
        tv.setTextColor(if (filled) t.bg else t.fg)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Int = 4) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dpi(margin), dpi(margin), dpi(margin), dpi(margin)) }

    /**
     * Tastierino a righe; "" = spazio vuoto (tiene il posto, così i numeri
     * restano sempre nella stessa posizione).
     */
    fun keypad(rows: List<List<String>>, onKey: (String) -> Unit): LinearLayout {
        val col = column()
        for (r in rows) {
            val rowL = row()
            for (k in r) {
                val v: View = if (k.isEmpty()) View(ctx) else button(k, 22f) { onKey(k) }
                rowL.addView(v, lp(0, dpi(54), 1f, 4))
            }
            col.addView(rowL, lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 0))
        }
        return col
    }

    /** Disposizione del telefono: 1 2 3 / 4 5 6 / 7 8 9, sotto ⌫. */
    fun numberPad(max: Int, onKey: (String) -> Unit): LinearLayout {
        fun k(n: Int) = if (n <= max) "$n" else ""
        val rows = mutableListOf(listOf(k(1), k(2), k(3)), listOf(k(4), k(5), k(6)))
        if (max >= 7) rows += listOf(k(7), k(8), k(9))
        rows += listOf("", "⌫", "")
        return keypad(rows, onKey)
    }

    /** Tastierino con lo 0 e il tasto di conferma (per i quiz). */
    fun phonePad(okLabel: String, onKey: (String) -> Unit): LinearLayout = keypad(
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", okLabel)),
        onKey
    )
}

/**
 * Griglia disegnata a mano (veloce e leggera). Ogni gioco dice cosa disegnare
 * in ogni cella tramite le funzioni qui sotto.
 */
@SuppressLint("ViewConstructor")
class Board(ctx: Context, private val t: GameTheme, val rows: Int, val cols: Int) : View(ctx) {
    enum class Style { NORMAL, GIVEN, FILLED, DIM, CROSSED, CIRCLED, BLOCK, ERROR }

    var text: (Int, Int) -> String = { _, _ -> "" }
    var style: (Int, Int) -> Style = { _, _ -> Style.NORMAL }
    var corner: (Int, Int) -> String? = { _, _ -> null }
    /** Per il Kakuro: (indizio in basso, indizio a destra) nelle caselle nere. */
    var split: (Int, Int) -> Pair<Int, Int>? = { _, _ -> null }
    var thickRight: (Int, Int) -> Boolean = { _, _ -> false }
    var thickBottom: (Int, Int) -> Boolean = { _, _ -> false }
    var selected: Pair<Int, Int>? = null
    var onTap: (Int, Int) -> Unit = { _, _ -> }
    /** Etichette laterali (es. somme di Sumplete). */
    var rightLabels: ((Int) -> Pair<String, Boolean>)? = null
    var bottomLabels: ((Int) -> Pair<String, Boolean>)? = null
    var maxCellDp = 64f

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f

    private fun extraCols() = if (rightLabels != null) 1 else 0
    private fun extraRows() = if (bottomLabels != null) 1 else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val c = min(w.toFloat() / (cols + extraCols()), maxCellDp * d)
        setMeasuredDimension(w, (c * (rows + extraRows()) + 2 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        try {
            drawAll(canvas)
        } catch (t: Throwable) {
            com.saranave.solodm.CrashGuard.record(context, "gioco (disegno)", t)
        }
    }

    private fun drawAll(canvas: Canvas) {
        cell = min(width.toFloat() / (cols + extraCols()), maxCellDp * d)
        ox = (width - cell * (cols + extraCols())) / 2f
        oy = d
        for (r in 0 until rows) for (c in 0 until cols) drawCell(canvas, r, c)
        // linee spesse
        line.color = t.fg
        line.strokeWidth = 3f * d
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ox + c * cell; val y = oy + r * cell
            if (thickRight(r, c) && c < cols - 1) canvas.drawLine(x + cell, y, x + cell, y + cell, line)
            if (thickBottom(r, c) && r < rows - 1) canvas.drawLine(x, y + cell, x + cell, y + cell, line)
        }
        line.strokeWidth = 2.5f * d
        canvas.drawRect(ox, oy, ox + cell * cols, oy + cell * rows, line)
        rightLabels?.let { f ->
            for (r in 0 until rows) {
                val (s, ok) = f(r)
                label(canvas, s, ox + cols * cell + cell / 2, oy + r * cell + cell / 2, ok)
            }
        }
        bottomLabels?.let { f ->
            for (c in 0 until cols) {
                val (s, ok) = f(c)
                label(canvas, s, ox + c * cell + cell / 2, oy + rows * cell + cell / 2, ok)
            }
        }
    }

    private fun label(canvas: Canvas, s: String, cx: Float, cy: Float, ok: Boolean) {
        txt.color = if (ok) t.dim else t.fg
        txt.textSize = cell * 0.38f
        txt.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        txt.isStrikeThruText = false
        canvas.drawText(s, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        if (ok) {
            line.color = t.dim; line.strokeWidth = 1.5f * d
            canvas.drawCircle(cx, cy, cell * 0.36f, line)
        }
    }

    private fun drawCell(canvas: Canvas, r: Int, c: Int) {
        val x = ox + c * cell; val y = oy + r * cell
        val st = style(r, c)
        val isSel = selected?.let { it.first == r && it.second == c } == true
        when {
            st == Style.BLOCK || st == Style.FILLED -> {
                fill.color = t.fg; canvas.drawRect(x, y, x + cell, y + cell, fill)
            }
            isSel -> { fill.color = t.faint; canvas.drawRect(x, y, x + cell, y + cell, fill) }
        }
        line.color = t.dim; line.strokeWidth = 1f * d
        canvas.drawRect(x, y, x + cell, y + cell, line)
        if (isSel) {
            line.color = t.fg; line.strokeWidth = 2.5f * d
            canvas.drawRect(x + 2 * d, y + 2 * d, x + cell - 2 * d, y + cell - 2 * d, line)
        }
        split(r, c)?.let { (down, across) ->
            line.color = t.bg; line.strokeWidth = 1f * d
            canvas.drawLine(x, y, x + cell, y + cell, line)
            txt.color = t.bg; txt.textSize = cell * 0.27f
            txt.typeface = Typeface.DEFAULT_BOLD; txt.isStrikeThruText = false
            if (down > 0) canvas.drawText("$down", x + cell * 0.3f, y + cell * 0.86f, txt)
            if (across > 0) canvas.drawText("$across", x + cell * 0.72f, y + cell * 0.38f, txt)
        }
        corner(r, c)?.let { s ->
            txt.color = t.fg; txt.textSize = cell * 0.22f
            txt.typeface = Typeface.DEFAULT_BOLD; txt.isStrikeThruText = false
            txt.textAlign = Paint.Align.LEFT
            canvas.drawText(s, x + 3 * d, y + cell * 0.25f, txt)
            txt.textAlign = Paint.Align.CENTER
        }
        val s = text(r, c)
        if (s.isNotEmpty()) {
            txt.textSize = cell * (if (s.length > 2) 0.36f else 0.5f)
            txt.typeface = if (st == Style.GIVEN) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            txt.color = when (st) {
                Style.FILLED -> t.bg
                Style.DIM, Style.CROSSED -> t.dim
                else -> t.fg
            }
            txt.isStrikeThruText = false
            val cy = y + cell / 2 - (txt.descent() + txt.ascent()) / 2 + if (corner(r, c) != null) cell * 0.08f else 0f
            canvas.drawText(s, x + cell / 2, cy, txt)
        }
        when (st) {
            Style.CROSSED -> {
                line.color = t.fg; line.strokeWidth = 2f * d
                val m = cell * 0.25f
                canvas.drawLine(x + m, y + m, x + cell - m, y + cell - m, line)
                canvas.drawLine(x + cell - m, y + m, x + m, y + cell - m, line)
            }
            Style.CIRCLED -> {
                line.color = t.fg; line.strokeWidth = 2f * d
                canvas.drawCircle(x + cell / 2, y + cell / 2, cell * 0.36f, line)
            }
            Style.ERROR -> {
                line.color = t.fg; line.strokeWidth = 2f * d
                canvas.drawLine(x + cell * 0.25f, y + cell * 0.85f, x + cell * 0.75f, y + cell * 0.85f, line)
                canvas.drawLine(x + cell * 0.25f, y + cell * 0.9f, x + cell * 0.75f, y + cell * 0.9f, line)
            }
            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && cell > 0) {
            val c = ((e.x - ox) / cell).toInt(); val r = ((e.y - oy) / cell).toInt()
            try {
                if (e.x >= ox && e.y >= oy && r in 0 until rows && c in 0 until cols) onTap(r, c)
            } catch (t: Throwable) {
                com.saranave.solodm.CrashGuard.record(context, "gioco (tocco)", t)
            }
        }
        return true
    }
}

/** Interfaccia comune: genera (in background) e costruisce la schermata. */
interface Game {
    val title: String
    /** Lavoro pesante: va fatto fuori dal thread principale. Restituisce i dati della partita. */
    fun generate(level: Int, rnd: kotlin.random.Random): Any
    /** Costruisce la schermata per una partita già generata. */
    fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View
}
