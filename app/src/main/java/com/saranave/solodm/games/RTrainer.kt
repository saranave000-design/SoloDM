package com.saranave.solodm.games

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.saranave.solodm.Prefs
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * "R · allenamento": porta nell'app il prototipo (5 giochi, 3 livelli, tutti gli esercizi).
 * Funziona offline: gli esercizi stanno in assets/r_esercizi.json
 * ("Cosa stampa?" = 1058 partite già generate dagli stessi schemi del prototipo).
 * Solo tocchi, niente tastiera. Errore → "✗ No, riprova." e la soluzione solo col tasto.
 */
object GameR : Game {
    override val title = "R · allenamento"

    // ------------------------------------------------------------------ dati

    private class Out(val lvl: Int, val g: Int, val code: String, val opts: List<String>, val why: String)
    private class Fill(val lvl: Int, val mod: String, val goal: String, val code: String, val opts: List<String>, val why: String)
    private class Bug(val lvl: Int, val mod: String, val goal: String, val lines: List<String>, val bug: Int, val fix: String, val why: String)
    private class OLine(val t: String, val dep: List<Int>)
    private class Ord(val lvl: Int, val mod: String, val goal: String, val lines: List<OLine>, val distr: String?)
    private class Comp(val lvl: Int, val mod: String, val goal: String, val tok: List<String>, val distr: List<String>, val alt: List<String>, val why: String)

    private class Bank(val ctx: String, val out: List<Out>, val fill: List<Fill>, val bug: List<Bug>, val ord: List<Ord>, val comp: List<Comp>)

    @Volatile private var bank: Bank? = null

    private fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }
    private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }

    private fun load(c: Context): Bank {
        bank?.let { return it }
        val txt = c.assets.open("r_esercizi.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val j = JSONObject(txt)
        fun arr(k: String) = j.getJSONArray(k).let { a -> List(a.length()) { a.getJSONObject(it) } }
        val b = Bank(
            j.getString("ctx"),
            arr("out").map { Out(it.getInt("lvl"), it.getInt("g"), it.getString("code"), strs(it.getJSONArray("opts")), it.getString("why")) },
            arr("fill").map { Fill(it.getInt("lvl"), it.getString("mod"), it.getString("goal"), it.getString("code"), strs(it.getJSONArray("opts")), it.getString("why")) },
            arr("bug").map { Bug(it.getInt("lvl"), it.getString("mod"), it.getString("goal"), strs(it.getJSONArray("lines")), it.getInt("bug"), it.getString("fix"), it.getString("why")) },
            arr("order").map { o ->
                val ls = o.getJSONArray("lines")
                Ord(o.getInt("lvl"), o.getString("mod"), o.getString("goal"),
                    List(ls.length()) { val l = ls.getJSONObject(it); OLine(l.getString("t"), ints(l.getJSONArray("dep"))) },
                    if (o.isNull("distr")) null else o.getString("distr"))
            },
            arr("comp").map { Comp(it.getInt("lvl"), it.getString("mod"), it.getString("goal"), strs(it.getJSONArray("tok")),
                strs(it.getJSONArray("distr")), strs(it.getJSONArray("alt")), it.getString("why")) }
        )
        bank = b
        return b
    }

    /** Impostato dal pannello dei giochi: serve per leggere gli esercizi. */
    @Volatile var appCtx: Context? = null

    // il caricamento si fa qui, fuori dal thread principale, la prima volta che serve
    override fun generate(level: Int, rnd: Random): Any {
        appCtx?.let { load(it) }
        return level
    }

    // code per non ripetere gli esercizi finché non sono finiti tutti (come nel prototipo)
    private val queues = HashMap<String, ArrayList<Int>>()
    private fun <T> draw(list: List<T>, key: String): T {
        val q = queues.getOrPut(key) { ArrayList() }
        if (q.isEmpty()) q.addAll(list.indices.shuffled())
        return list[q.removeAt(q.size - 1)]
    }

    private fun joinR(a: List<String>): String = a.joinToString(" ")
        .replace("( ", "(").replace(" )", ")").replace("[ ", "[").replace(" [", "[")
        .replace(" ]", "]").replace(" ,", ",").replace(" $", "$")
    private fun norm(s: String) = s.replace(Regex("\\s+"), "")

    private val MODES = listOf("mix" to "Mix", "out" to "Cosa stampa?", "fill" to "Completa", "comp" to "Componi", "order" to "Riordina", "bug" to "Trova il bug")

    // ------------------------------------------------------------------ schermata

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val lvl = (level + 1).coerceIn(1, 3)
        val b = load(k.ctx)
        return Screen(k, b, lvl).root
    }

    private class Screen(val k: Kit, val b: Bank, val lvl: Int) {
        val sp = Prefs.sp(k.ctx)
        val root = k.column().apply { gravity = Gravity.START }
        val score = k.text("", 13f, bold = true, color = k.t.dim).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.START }
        val modeBox = LinearLayout(k.ctx).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(k.ctx).apply { orientation = LinearLayout.VERTICAL }
        var mode = sp.getString("r_mode", "mix") ?: "mix"

        init {
            root.addView(score, full())
            root.addView(modeBox, full())
            root.addView(card, full())
            val n = b.out.map { it.lvl * 100 + it.g }.toSet().size
            root.addView(note("\"Cosa stampa?\": ${b.out.size} partite da $n schemi. Poi: ${b.fill.size} da completare, ${b.comp.size} righe da comporre, ${b.ord.size} script da riordinare, ${b.bug.size} bug."), full())
            drawScore(); drawModes(); next()
        }

        // ---------- aiuti grafici (solo bianco e nero)
        fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, k.dpi(4), 0, k.dpi(4)) }
        fun note(s: String) = k.text(s, 12f, color = k.t.dim).apply { gravity = Gravity.START }
        fun label(s: String) = k.text(s, 13f, color = k.t.dim).apply { gravity = Gravity.START }
        fun title(s: String) = k.text(s, 17f, bold = true).apply { gravity = Gravity.START }
        fun codeBox(cs: CharSequence) = TextView(k.ctx).apply {
            text = cs; typeface = Typeface.MONOSPACE; setTextColor(k.t.fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(k.dpi(10), k.dpi(10), k.dpi(10), k.dpi(10))
            background = GradientDrawable().apply { cornerRadius = k.dp(8f); setColor(k.t.faint) }
        }

        /** Pulsante con testo a sinistra in carattere "da codice". */
        fun opt(s: String, chip: Boolean = false, onClick: () -> Unit) = TextView(k.ctx).apply {
            text = s; typeface = Typeface.MONOSPACE; setTextColor(k.t.fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = if (chip) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = k.dpi(if (chip) 42 else 46)
            setPadding(k.dpi(10), k.dpi(8), k.dpi(10), k.dpi(8))
            background = GradientDrawable().apply { cornerRadius = k.dp(8f); setStroke(k.dpi(1).coerceAtLeast(1), k.t.fg) }
            isClickable = true
            setOnClickListener { if (isEnabled) safe(onClick) }
        }
        fun setOk(v: TextView) { (v.background as GradientDrawable).setColor(k.t.fg); v.setTextColor(k.t.bg) }
        fun setNo(v: TextView) {
            (v.background as GradientDrawable).setStroke(k.dpi(1).coerceAtLeast(1), k.t.dim, k.dp(6f), k.dp(4f))
            v.setTextColor(k.t.dim); v.paintFlags = v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        fun setDim(v: View) { v.alpha = 0.45f }
        fun mainBtn(s: String, onClick: () -> Unit) = k.button(s, 16f, filled = true, onClick = onClick)
        fun safe(f: () -> Unit) { try { f() } catch (t: Throwable) { com.saranave.solodm.CrashGuard.record(k.ctx, "R allenamento", t) } }

        // ---------- punteggio
        fun drawScore() { score.text = "✓ ${sp.getInt("r_ok", 0)}/${sp.getInt("r_tot", 0)} · serie ${sp.getInt("r_streak", 0)}" }
        fun mark(good: Boolean) {
            val e = sp.edit().putInt("r_tot", sp.getInt("r_tot", 0) + 1)
            if (good) e.putInt("r_ok", sp.getInt("r_ok", 0) + 1).putInt("r_streak", sp.getInt("r_streak", 0) + 1) else e.putInt("r_streak", 0)
            e.apply(); drawScore()
        }

        fun drawModes() {
            modeBox.removeAllViews()
            for (rowModes in MODES.chunked(3)) {
                val r = k.row()
                for ((key, name) in rowModes) {
                    r.addView(k.button(name, 13f, filled = key == mode) {
                        if (mode != key) { mode = key; sp.edit().putString("r_mode", key).apply(); drawModes(); next() }
                    }, k.lp(0, weight = 1f, margin = 3))
                }
                modeBox.addView(r, full())
            }
        }

        fun next() {
            card.removeAllViews()
            msg.removeAllViews()
            val m = if (mode == "mix") listOf("out", "fill", "comp", "comp", "order", "bug", "bug").random() else mode
            when (m) {
                "out" -> playOut(); "fill" -> playFill(); "comp" -> playComp(); "order" -> playOrd(); else -> playBug()
            }
            // torna in cima al pannello
            var p = root.parent
            while (p != null && p !is ScrollView) p = p.parent
            (p as? ScrollView)?.scrollTo(0, 0)
        }

        // ---------- esito
        val msg = LinearLayout(k.ctx).apply { orientation = LinearLayout.VERTICAL }

        /** Primo errore: "No, riprova" e la soluzione solo a richiesta. */
        fun retry(onSol: () -> Unit) {
            msg.removeAllViews()
            msg.addView(title("✗ No, riprova."), full())
            msg.addView(k.button("Mostra soluzione", 15f) { onSol() }, full())
        }

        /** good: true = giusto al primo colpo, null = giusto dopo un errore, false = soluzione mostrata. */
        fun showFb(good: Boolean?, why: String, fix: String?) {
            msg.removeAllViews()
            val fb = LinearLayout(k.ctx).apply { orientation = LinearLayout.VERTICAL }
            fb.addView(View(k.ctx).apply { setBackgroundColor(k.t.fg) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, k.dpi(1).coerceAtLeast(2)).apply { setMargins(0, k.dpi(10), 0, k.dpi(8)) })
            fb.addView(title(when (good) { true -> "✓ Giusto."; null -> "✓ Giusto, al secondo giro."; false -> "✗ Ecco la soluzione." }), full())
            if (fix != null) { fb.addView(label("Corretto:"), full()); fb.addView(codeBox(fix), full()) }
            fb.addView(k.text(why, 15f).apply { gravity = Gravity.START }, full())
            fb.addView(mainBtn("Avanti →") { next() }, full())
            msg.addView(fb, full())
        }

        fun head(tag: String, q: String) {
            card.addView(label(tag), full())
            card.addView(title(q), full())
        }

        // ---------- scelta multipla (Cosa stampa? e Completa)
        fun mcq(tag: String, q: String, code: CharSequence, opts: List<String>, why: String, after: CharSequence?) {
            head(tag, q)
            val codeV = codeBox(code)
            card.addView(codeV, full())
            val btns = ArrayList<Pair<Int, TextView>>()
            var tried = false; var done = false
            fun end(good: Boolean?) {
                done = true
                for ((i, v) in btns) { v.isEnabled = false; if (i == 0) setOk(v) else if (v.alpha == 1f && v.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG == 0) setDim(v) }
                if (after != null) codeV.text = after
                showFb(good, why, null)
            }
            for (i in opts.indices.shuffled()) {
                lateinit var v: TextView
                v = opt(opts[i]) {
                    if (done) return@opt
                    if (i == 0) { if (!tried) mark(true); end(if (tried) null else true); return@opt }
                    setNo(v); v.isEnabled = false
                    if (!tried) { mark(false); tried = true }
                    retry { end(false) }
                }
                btns += i to v
                card.addView(v, full())
            }
            card.addView(msg, full())
        }

        fun playOut() {
            val list = b.out.filter { it.lvl == lvl }
            val g = list.map { it.g }.distinct().random()
            val o = list.filter { it.g == g }.random()
            mcq("Cosa stampa? · " + listOf("", "Modulo 1", "Moduli 1–2", "Moduli 1–2 combinati")[lvl], "Cosa stampa la console?", o.code, o.opts, o.why, null)
        }

        fun playFill() {
            val f = draw(b.fill.filter { it.lvl == lvl }, "fill$lvl")
            val parts = f.code.split("___")
            val blank = SpannableStringBuilder(parts[0]).apply {
                val s = length; append("    "); setSpan(UnderlineSpan(), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); append(parts.getOrElse(1) { "" })
            }
            val filled = SpannableStringBuilder(parts[0]).apply {
                val s = length; append(f.opts[0])
                setSpan(UnderlineSpan(), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(parts.getOrElse(1) { "" })
            }
            mcq("Completa · ${f.mod}", f.goal, blank, f.opts.map { if (it.isEmpty()) "(niente)" else it }, f.why, filled)
        }

        // ---------- Trova il bug
        fun playBug() {
            val bg = draw(b.bug.filter { it.lvl == lvl }, "bug$lvl")
            head("Trova il bug · ${bg.mod}", "Obiettivo: ${bg.goal}. Tocca la riga sbagliata.")
            val btns = ArrayList<TextView>()
            var tried = false; var done = false
            fun end(good: Boolean?) {
                done = true
                btns.forEachIndexed { i, v -> v.isEnabled = false; if (i == bg.bug) setOk(v) }
                showFb(good, bg.why, bg.fix)
            }
            bg.lines.forEachIndexed { i, l ->
                lateinit var v: TextView
                v = opt(l) {
                    if (done) return@opt
                    if (i == bg.bug) { if (!tried) mark(true); end(if (tried) null else true); return@opt }
                    setNo(v); v.isEnabled = false
                    if (!tried) { mark(false); tried = true }
                    retry { end(false) }
                }
                btns += v
                card.addView(v, full())
            }
            card.addView(msg, full())
        }

        // ---------- Riordina le righe
        fun zoneBox() = LinearLayout(k.ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = k.dpi(56)
            setPadding(k.dpi(6), k.dpi(6), k.dpi(6), k.dpi(6))
            background = GradientDrawable().apply { cornerRadius = k.dp(8f); setStroke(k.dpi(1).coerceAtLeast(1), k.t.dim, k.dp(6f), k.dp(4f)) }
        }

        fun playOrd() {
            val o = draw(b.ord.filter { it.lvl == lvl }, "ord$lvl")
            class It(val t: String, val dep: List<Int>, val i: Int)
            val items = o.lines.mapIndexed { i, l -> It(l.t, l.dep, i) }.toMutableList()
            val intruder = o.distr != null && lvl > 1
            if (intruder) items += It(o.distr!!, emptyList(), -1)
            val pool = items.shuffled().toMutableList(); val ans = ArrayList<It>()
            val need = o.lines.size
            var tried = false; var done = false
            head("Riordina · ${o.mod}", o.goal)
            card.addView(label("Il tuo script" + if (intruder) " · attenta: una riga è di troppo" else ""), full())
            val zone = zoneBox(); card.addView(zone, full())
            card.addView(label("Righe disponibili"), full())
            val poolV = LinearLayout(k.ctx).apply { orientation = LinearLayout.VERTICAL }; card.addView(poolV, full())
            val rowB = k.row()
            lateinit var check: TextView
            fun paint() {
                zone.removeAllViews(); poolV.removeAllViews()
                if (ans.isEmpty()) zone.addView(note("Tocca le righe qui sotto nell'ordine giusto"), full())
                ans.forEachIndexed { n, it -> zone.addView(opt("${n + 1}  ${it.t}") { if (!done) { pool += ans.removeAt(n); paint() } }, full()) }
                pool.forEachIndexed { n, it -> poolV.addView(opt(it.t) { if (!done) { ans += pool.removeAt(n); paint() } }, full()) }
                check.isEnabled = !done && ans.size == need; check.alpha = if (check.isEnabled) 1f else 0.45f
            }
            rowB.addView(k.button("↺ Svuota", 15f) { if (!done) { pool += ans; ans.clear(); paint() } }, k.lp(0, weight = 1f))
            check = mainBtn("Verifica") {
                if (done || ans.size != need) return@mainBtn
                val pos = HashMap<Int, Int>(); ans.forEachIndexed { n, it -> pos[it.i] = n }
                val good = ans.none { it.i < 0 } && ans.withIndex().all { (n, it) -> it.dep.all { d -> pos[d] != null && pos[d]!! < n } }
                fun end(g: Boolean?) {
                    done = true; paint()
                    val why = if (g != false) "Ogni riga usa solo oggetti già creati sopra di lei: è questo che decide l'ordine."
                    else "Regola: un oggetto va creato prima di essere usato, e i grafici si aprono prima di aggiungerci sopra." + if (intruder) " La riga intrusa non serve." else ""
                    showFb(g, why, if (g == false) o.lines.joinToString("\n") { it.t } else null)
                }
                if (good) { if (!tried) mark(true); end(if (tried) null else true); return@mainBtn }
                if (!tried) { mark(false); tried = true }
                retry { end(false) }
            }
            rowB.addView(check, k.lp(0, weight = 2f))
            card.addView(rowB, full())
            card.addView(msg, full())
            paint()
        }

        // ---------- Componi la riga
        fun playComp() {
            val c = draw(b.comp.filter { it.lvl == lvl }, "comp$lvl")
            val items = (c.tok + (if (lvl == 1) c.distr.take(1) else c.distr)).toMutableList()
            val pool = items.shuffled().toMutableList(); val ans = ArrayList<String>()
            val sols = listOf(norm(c.tok.joinToString(""))) + c.alt.map { norm(it) }
            var tried = false; var done = false
            head("Componi la riga · ${c.mod}", c.goal)
            card.addView(label(b.ctx), full())
            card.addView(label("La tua riga"), full())
            val prev = codeBox(" "); card.addView(prev, full())
            val zone = Flow(k.ctx, k.dpi(6)).apply {
                minimumHeight = k.dpi(56); setPadding(k.dpi(6), k.dpi(6), k.dpi(6), k.dpi(6))
                background = GradientDrawable().apply { cornerRadius = k.dp(8f); setStroke(k.dpi(1).coerceAtLeast(1), k.t.dim, k.dp(6f), k.dp(4f)) }
            }
            card.addView(zone, full())
            card.addView(label("Pezzi (alcuni sono di troppo)"), full())
            val poolV = Flow(k.ctx, k.dpi(6)); card.addView(poolV, full())
            val rowB = k.row()
            lateinit var check: TextView
            fun paint() {
                prev.text = if (ans.isEmpty()) " " else joinR(ans)
                zone.removeAllViews(); poolV.removeAllViews()
                ans.forEachIndexed { n, t -> zone.addView(opt(t, chip = true) { if (!done) { pool += ans.removeAt(n); paint() } }) }
                pool.forEachIndexed { n, t -> poolV.addView(opt(t, chip = true) { if (!done) { ans += pool.removeAt(n); paint() } }) }
                check.isEnabled = !done && ans.isNotEmpty(); check.alpha = if (check.isEnabled) 1f else 0.45f
            }
            fun finish(good: Boolean?, show: Boolean) { done = true; paint(); showFb(good, c.why, if (show) joinR(c.tok) else null) }
            rowB.addView(k.button("↺ Svuota", 15f) { if (!done) { pool += ans; ans.clear(); paint() } }, k.lp(0, weight = 1f))
            check = mainBtn("Verifica") {
                if (done || ans.isEmpty()) return@mainBtn
                val good = norm(ans.joinToString("")) in sols
                if (good) { if (!tried) mark(true); finish(if (tried) null else true, false); return@mainBtn }
                if (!tried) { mark(false); tried = true }
                retry { finish(false, true) }
            }
            rowB.addView(check, k.lp(0, weight = 2f))
            card.addView(rowB, full())
            card.addView(msg, full())
            paint()
        }
    }

    /** Disposizione "a capo" per i pezzi di Componi la riga. */
    private class Flow(ctx: Context, val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
                x += c.measuredWidth + gap; lineH = maxOf(lineH, c.measuredHeight)
            }
            val h = paddingTop + paddingBottom + y + lineH
            setMeasuredDimension(MeasureSpec.getSize(wSpec), maxOf(h, suggestedMinimumHeight))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
                c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + gap; lineH = maxOf(lineH, c.measuredHeight)
            }
        }
    }
}
