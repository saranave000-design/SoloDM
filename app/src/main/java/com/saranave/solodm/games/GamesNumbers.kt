package com.saranave.solodm.games

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

// ------------------------------------------------------------------ 24 e Numero bersaglio

private class CardsData(val nums: List<Int>, val target: Int, val solution: String)

/** Gioco a carte: tocchi un numero, un'operazione, un altro numero → si fondono. */
private abstract class CardsGame : Game {
    abstract val intOnly: Boolean
    abstract val rule: String
    abstract fun won(values: List<Frac>, target: Int): Boolean

    private class Card(val v: Frac, val expr: String)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val d = data as CardsData
        val col = k.column()
        col.addView(k.text("${d.target}", 40f, bold = true), k.lp())
        col.addView(k.text(rule, 13f, color = k.t.dim), k.lp())

        val cardsBox = k.column()
        col.addView(cardsBox, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        val msg = k.text("", 14f, color = k.t.dim)

        var cards = d.nums.map { Card(Frac.of(it.toLong()), "$it") }.toMutableList()
        val history = ArrayList<List<Card>>()
        var sel = -1
        var op: Char? = null
        var done = false
        val opButtons = HashMap<Char, TextView>()

        lateinit var render: () -> Unit
        fun tapCard(i: Int) {
            if (done) return
            if (sel < 0 || sel == i) { sel = if (sel == i) -1 else i; op = null; render(); return }
            val o = op
            if (o == null) { sel = i; render(); return }
            val a = cards[sel]; val b = cards[i]
            val r = applyOp(a.v, o, b.v)
            if (r == null) { msg.text = "Non si divide per zero"; return }
            if (intOnly && (!r.isInt || r.n <= 0)) { msg.text = "Qui valgono solo numeri interi positivi"; return }
            history += cards.toList()
            val nc = Card(r, "(${a.expr} $o ${b.expr})")
            cards = cards.filterIndexed { idx, _ -> idx != sel && idx != i }.toMutableList()
            cards.add(minOf(i, sel).coerceAtMost(cards.size), nc)
            sel = cards.indexOf(nc); op = null; msg.text = ""
            if (won(cards.map { it.v }, d.target)) { done = true; msg.text = "✓ Fatto!"; onWin() }
            render()
        }
        render = {
            cardsBox.removeAllViews()
            for (chunk in cards.indices.chunked(3)) {
                val row = k.row()
                for (i in chunk) {
                    val b = k.button(cards[i].v.toString(), 24f, filled = i == sel) { tapCard(i) }
                    row.addView(b, k.lp(0, k.dpi(64), 1f, 5))
                }
                cardsBox.addView(row, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 0))
            }
            for ((c, b) in opButtons) k.setFilled(b, c == op)
        }

        val ops = k.row()
        for (c in listOf('+', '-', '×', '÷')) {
            val label = if (c == '-') "−" else "$c"
            val b = k.button(label, 22f) {
                if (done || sel < 0) return@button
                op = if (op == c) null else c; render()
            }
            opButtons[c] = b
            ops.addView(b, k.lp(0, k.dpi(52), 1f, 5))
        }
        col.addView(ops, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(msg, k.lp())

        val tools = k.row()
        tools.addView(k.button("Annulla", 14f) {
            if (history.isNotEmpty() && !done) { cards = history.removeAt(history.size - 1).toMutableList(); sel = -1; op = null; render() }
        }, k.lp(0, weight = 1f))
        tools.addView(k.button("Da capo", 14f) {
            if (history.isNotEmpty()) { cards = history.first().toMutableList(); history.clear(); sel = -1; op = null; done = false; msg.text = ""; render() }
        }, k.lp(0, weight = 1f))
        tools.addView(k.button("Soluzione", 14f) { msg.text = "${d.solution} = ${d.target}" }, k.lp(0, weight = 1f))
        col.addView(tools, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        render()
        return col
    }
}

object Game24 : Game {
    private val impl = object : CardsGame() {
        override val title = "24"
        override val intOnly = false
        override val rule = "Usa tutti e 4 i numeri, una volta sola ciascuno"
        override fun won(values: List<Frac>, target: Int) = values.size == 1 && values[0] == Frac.of(target.toLong())
        override fun generate(level: Int, rnd: Random): Any = Unit
    }
    override val title = "Gioco del 24"
    override fun generate(level: Int, rnd: Random): Any {
        val (n, s) = Cards.gen24(level, rnd); return CardsData(n, 24, s)
    }
    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit) = impl.build(k, data, level, onWin)
}

object GameTarget : Game {
    private val impl = object : CardsGame() {
        override val title = "Bersaglio"
        override val intOnly = true
        override val rule = "Raggiungi il numero: non serve usarli tutti"
        override fun won(values: List<Frac>, target: Int) = values.any { it == Frac.of(target.toLong()) }
        override fun generate(level: Int, rnd: Random): Any = Unit
    }
    override val title = "Numero bersaglio"
    override fun generate(level: Int, rnd: Random): Any {
        val (n, t, s) = Cards.genTarget(level, rnd); return CardsData(n, t, s)
    }
    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit) = impl.build(k, data, level, onWin)
}

// ------------------------------------------------------------------ Equazione nascosta

object GameEquation : Game {
    override val title = "Equazione nascosta"
    private const val TRIES = 6

    override fun generate(level: Int, rnd: Random): Any = Equation.generate(6 + level, rnd)

    private fun show(c: Char) = when (c) { '*' -> '×'; '/' -> '÷'; '-' -> '−'; else -> c }

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val answer = data as String
        val len = answer.length
        val guesses = ArrayList<String>()
        val marks = ArrayList<IntArray>()
        var cur = ""
        var done = false

        val col = k.column()
        col.addView(k.text("Indovina il calcolo in $TRIES tentativi", 13f, color = k.t.dim), k.lp())
        col.addView(k.text("■ giusto al posto giusto   ○ c'è ma altrove", 12f, color = k.t.dim), k.lp())
        val board = Board(k.ctx, k.t, TRIES, len)
        board.maxCellDp = 48f
        board.text = { r, c ->
            val s = when {
                r < guesses.size -> guesses[r]
                r == guesses.size -> cur
                else -> ""
            }
            if (c < s.length) "${show(s[c])}" else ""
        }
        board.style = { r, c ->
            if (r < marks.size) when (marks[r][c]) { 2 -> Board.Style.FILLED; 1 -> Board.Style.CIRCLED; else -> Board.Style.DIM }
            else Board.Style.NORMAL
        }
        col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        val msg = k.text("", 14f)
        col.addView(msg, k.lp())

        val pad = k.keypad(
            listOf(
                listOf("1", "2", "3", "4", "5"),
                listOf("6", "7", "8", "9", "0"),
                listOf("+", "−", "×", "÷", "="),
                listOf("⌫", "Invio")
            )
        ) { key ->
            if (done) return@keypad
            when (key) {
                "⌫" -> cur = cur.dropLast(1)
                "Invio" -> {
                    when {
                        cur.length < len -> msg.text = "Servono $len caratteri"
                        !Equation.isValid(cur, len) -> msg.text = "Non è un calcolo giusto"
                        else -> {
                            val fb = Equation.feedback(cur, answer)
                            guesses += cur; marks += fb; cur = ""; msg.text = ""
                            if (fb.all { it == 2 }) { done = true; msg.text = "✓ Trovata!"; onWin() }
                            else if (guesses.size == TRIES) { done = true; msg.text = "Era: " + answer.map { show(it) }.joinToString("") }
                        }
                    }
                }
                else -> if (cur.length < len) cur += when (key) { "×" -> "*"; "÷" -> "/"; "−" -> "-"; else -> key }
            }
            board.invalidate()
        }
        col.addView(pad, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        return col
    }
}

// ------------------------------------------------------------------ Calcolo a mente e Sequenze

/** Domande a raffica con tastierino numerico, partita finita (N domande). */
private fun quizView(
    k: Kit, total: Int, prompt: (Int) -> String, answer: (Int) -> Long, onWin: () -> Unit, timed: Boolean
): View {
    val col = k.column()
    val counter = k.text("", 13f, color = k.t.dim)
    val q = k.text("", 30f, bold = true)
    val input = k.text("", 30f)
    val msg = k.text("", 14f, color = k.t.dim)
    col.addView(counter, k.lp()); col.addView(q, k.lp()); col.addView(input, k.lp()); col.addView(msg, k.lp())
    var i = 0; var cur = ""; var right = 0
    val start = SystemClock.uptimeMillis()
    fun render() {
        if (i >= total) {
            val secs = (SystemClock.uptimeMillis() - start) / 1000
            counter.text = ""; q.text = "$right / $total"
            input.text = if (timed) "in $secs s" else ""
            return
        }
        counter.text = "${i + 1} / $total"
        q.text = prompt(i)
        input.text = if (cur.isEmpty()) "_" else cur
    }
    val pad = k.keypad(
        listOf(listOf("1", "2", "3", "4", "5"), listOf("6", "7", "8", "9", "0"), listOf("⌫", "OK"))
    ) { key ->
        if (i >= total) return@keypad
        when (key) {
            "⌫" -> cur = cur.dropLast(1)
            "OK" -> {
                if (cur.isEmpty()) return@keypad
                val a = answer(i)
                if (cur.toLongOrNull() == a) { right++; msg.text = "✓" } else msg.text = "✗ era $a"
                cur = ""; i++
                if (i >= total && right == total) onWin()
            }
            else -> if (cur.length < 7) cur += key
        }
        render()
    }
    col.addView(pad, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
    render()
    return col
}

object GameMental : Game {
    override val title = "Calcolo a mente"
    override fun generate(level: Int, rnd: Random): Any = List(10) { MentalMath.question(level, rnd) }
    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        @Suppress("UNCHECKED_CAST") val qs = data as List<Pair<String, Int>>
        return quizView(k, qs.size, { "${qs[it].first} =" }, { qs[it].second.toLong() }, onWin, timed = true)
    }
}

object GameSequences : Game {
    override val title = "Sequenze"
    override fun generate(level: Int, rnd: Random): Any = List(5) { Sequences.generate(level, rnd) }
    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        @Suppress("UNCHECKED_CAST") val qs = data as List<Pair<List<Long>, Long>>
        val v = quizView(k, qs.size, { qs[it].first.joinToString(", ") + ", ?" }, { qs[it].second }, onWin, timed = false)
        // le sequenze sono lunghe: testo un po' più piccolo
        ((v as LinearLayout).getChildAt(1) as TextView).textSize = 22f
        return v
    }
}
