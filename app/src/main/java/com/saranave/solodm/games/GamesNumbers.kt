package com.saranave.solodm.games

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

// ------------------------------------------------------------------ 24 e Numero bersaglio

private class CardsData(val nums: List<Int>, val target: Int, val solution: String) : java.io.Serializable

/** Gioco a carte: tocchi un numero, un'operazione, un altro numero → si fondono. */
private abstract class CardsGame : Game {
    abstract val intOnly: Boolean
    abstract val rule: String
    abstract fun won(values: List<Frac>, target: Int): Boolean

    private class Card(val v: Frac, val expr: String) : java.io.Serializable

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val d = data as CardsData
        val col = k.column()
        col.addView(k.text("${d.target}", 40f, bold = true), k.lp())
        col.addView(k.text(rule, 13f, color = k.t.dim), k.lp())

        val cardsBox = k.column()
        col.addView(cardsBox, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        val msg = k.text("", 14f, color = k.t.dim)

        val cardsB = k.state("carte") { Box<MutableList<Card>>(ArrayList(d.nums.map { Card(Frac.of(it.toLong()), "$it") })) }
        val history = k.state("storia") { ArrayList<List<Card>>() }
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
            val a = cardsB.v[sel]; val b = cardsB.v[i]
            val r = applyOp(a.v, o, b.v)
            if (r == null) { msg.text = "Non si divide per zero"; return }
            if (intOnly && (!r.isInt || r.n <= 0)) { msg.text = "Qui valgono solo numeri interi positivi"; return }
            history += ArrayList(cardsB.v)
            val nc = Card(r, "(${a.expr} $o ${b.expr})")
            val nl = cardsB.v.filterIndexed { idx, _ -> idx != sel && idx != i }.toMutableList()
            nl.add(minOf(i, sel).coerceAtMost(nl.size), nc)
            cardsB.v = ArrayList(nl)
            sel = nl.indexOf(nc); op = null; msg.text = ""
            if (won(cardsB.v.map { it.v }, d.target)) { done = true; msg.text = "✓ Fatto!"; onWin() }
            render()
        }
        render = {
            cardsBox.removeAllViews()
            for (chunk in cardsB.v.indices.chunked(3)) {
                val row = k.row()
                for (i in chunk) {
                    val b = k.button(cardsB.v[i].v.toString(), 24f, filled = i == sel) { tapCard(i) }
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
            if (history.isNotEmpty() && !done) { cardsB.v = ArrayList(history.removeAt(history.size - 1)); sel = -1; op = null; render() }
        }, k.lp(0, weight = 1f))
        tools.addView(k.button("Da capo", 14f) {
            if (history.isNotEmpty()) { cardsB.v = ArrayList(history.first()); history.clear(); sel = -1; op = null; done = false; msg.text = ""; render() }
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
        val guesses = k.state("tentativi") { ArrayList<String>() }
        val marks = k.state("segni") { ArrayList<IntArray>() }
        val curB = k.state("scritto") { Box("") }
        var done = guesses.size >= TRIES || marks.any { m -> m.all { it == 2 } }

        val col = k.column()
        col.addView(k.text("Indovina il calcolo in $TRIES tentativi", 13f, color = k.t.dim), k.lp())
        col.addView(k.text("■ giusto al posto giusto   ○ c'è ma altrove", 12f, color = k.t.dim), k.lp())
        val board = Board(k.ctx, k.t, TRIES, len)
        board.maxCellDp = 48f
        board.text = { r, c ->
            val s = when {
                r < guesses.size -> guesses[r]
                r == guesses.size -> curB.v
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
                listOf("1", "2", "3", "+"),
                listOf("4", "5", "6", "−"),
                listOf("7", "8", "9", "×"),
                listOf("⌫", "0", "=", "÷"),
                listOf("Invio")
            )
        ) { key ->
            if (done) return@keypad
            when (key) {
                "⌫" -> curB.v = curB.v.dropLast(1)
                "Invio" -> {
                    when {
                        curB.v.length < len -> msg.text = "Servono $len caratteri"
                        !Equation.isValid(curB.v, len) -> msg.text = "Non è un calcolo giusto"
                        else -> {
                            val fb = Equation.feedback(curB.v, answer)
                            guesses += curB.v; marks += fb; curB.v = ""; msg.text = ""
                            if (fb.all { it == 2 }) { done = true; msg.text = "✓ Trovata!"; onWin() }
                            else if (guesses.size == TRIES) { done = true; msg.text = "Era: " + answer.map { show(it) }.joinToString(""); onWin() }
                        }
                    }
                }
                else -> if (curB.v.length < len) curB.v += when (key) { "×" -> "*"; "÷" -> "/"; "−" -> "-"; else -> key }
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
    // [domanda, giuste] e il testo scritto: salvati, così la partita riprende dov'era
    val prog = k.state("quiz") { IntArray(2) }
    val curB = k.state("scritto") { Box("") }
    val startB = k.state("inizio") { Box(System.currentTimeMillis()) }

    fun render() {
        if (prog[0] >= total) {
            val secs = (System.currentTimeMillis() - startB.v) / 1000
            counter.text = ""; q.text = "${prog[1]} / $total"
            input.text = if (timed) "in $secs s" else ""
            return
        }
        counter.text = "${prog[0] + 1} / $total"
        q.text = prompt(prog[0])
        input.text = if (curB.v.isEmpty()) "_" else curB.v
    }
    val pad = k.phonePad("OK") { key ->
        if (prog[0] >= total) return@phonePad
        when (key) {
            "⌫" -> curB.v = curB.v.dropLast(1)
            "OK" -> {
                if (curB.v.isEmpty()) return@phonePad
                val a = answer(prog[0])
                if (curB.v.toLongOrNull() == a) { prog[1]++; msg.text = "✓" } else msg.text = "✗ era $a"
                curB.v = ""; prog[0]++
                if (prog[0] >= total) onWin()
            }
            else -> if (curB.v.length < 7) curB.v += key
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
