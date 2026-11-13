package com.saranave.solodm.games

/*
 * Risolutori "umani": usano solo deduzioni certe, mai tentativi.
 * Se un risolutore chiude lo schema, allora:
 *   1) la soluzione è unica (ogni passo era obbligato);
 *   2) in ogni momento esiste almeno una mossa certa per chi gioca.
 * Il "livello" restituito è la tecnica più difficile che è servita.
 */

private fun bit(v: Int) = 1 shl (v - 1)
private fun single(m: Int) = m != 0 && (m and (m - 1)) == 0
private fun valueOf(m: Int) = Integer.numberOfTrailingZeros(m) + 1

// ------------------------------------------------------------------ Sudoku

object SudokuLogic {
    /** Restituisce il livello usato (0..2) oppure -1 se bloccato senza indovinare. */
    fun solve(givens: Array<IntArray>, n: Int, br: Int, bc: Int, maxLevel: Int): Int {
        val all = (1 shl n) - 1
        val g = Array(n) { givens[it].clone() }
        val cand = Array(n) { IntArray(n) { all } }
        val units = ArrayList<List<Int>>()
        for (r in 0 until n) units += (0 until n).map { r * n + it }
        for (c in 0 until n) units += (0 until n).map { it * n + c }
        for (b0 in 0 until n step br) for (b1 in 0 until n step bc)
            units += (b0 until b0 + br).flatMap { r -> (b1 until b1 + bc).map { c -> r * n + c } }
        val peers = Array(n * n) { HashSet<Int>() }
        for (u in units) for (a in u) for (b in u) if (a != b) peers[a] += b

        fun place(i: Int, v: Int): Boolean {
            val r = i / n; val c = i % n
            g[r][c] = v; cand[r][c] = bit(v)
            for (p in peers[i]) {
                val pr = p / n; val pc = p % n
                if (g[pr][pc] == v) return false
                cand[pr][pc] = cand[pr][pc] and bit(v).inv()
            }
            return true
        }
        for (r in 0 until n) for (c in 0 until n) if (g[r][c] != 0 && !place(r * n + c, g[r][c])) return -1

        var used = 0
        while (true) {
            if (g.all { row -> row.all { it != 0 } }) return used
            if ((0 until n * n).any { cand[it / n][it % n] == 0 }) return -1
            // --- livello 0: single nudi e nascosti
            var progress = false
            for (i in 0 until n * n) {
                val r = i / n; val c = i % n
                if (g[r][c] == 0 && single(cand[r][c])) { if (!place(i, valueOf(cand[r][c]))) return -1; progress = true }
            }
            if (progress) continue
            for (u in units) for (v in 1..n) {
                val where = u.filter { g[it / n][it % n] == 0 && cand[it / n][it % n] and bit(v) != 0 }
                if (where.size == 1 && u.none { g[it / n][it % n] == v }) {
                    if (!place(where[0], v)) return -1; progress = true
                }
            }
            if (progress) continue
            if (maxLevel < 1) return -1
            // --- livello 1: candidati bloccati + coppie nude
            if (lockedCandidates(n, br, bc, g, cand) || nakedPairs(n, units, g, cand)) { used = maxOf(used, 1); continue }
            if (maxLevel < 2) return -1
            // --- livello 2: coppie nascoste + X-wing
            if (hiddenPairs(n, units, g, cand) || xWing(n, g, cand)) { used = 2; continue }
            return -1
        }
    }

    private fun lockedCandidates(n: Int, br: Int, bc: Int, g: Array<IntArray>, cand: Array<IntArray>): Boolean {
        var changed = false
        for (b0 in 0 until n step br) for (b1 in 0 until n step bc) for (v in 1..n) {
            val cells = (b0 until b0 + br).flatMap { r -> (b1 until b1 + bc).map { c -> r to c } }
                .filter { (r, c) -> g[r][c] == 0 && cand[r][c] and bit(v) != 0 }
            if (cells.isEmpty()) continue
            val rows = cells.map { it.first }.toSet(); val cols = cells.map { it.second }.toSet()
            if (rows.size == 1) { val r = rows.first()
                for (c in 0 until n) if ((c < b1 || c >= b1 + bc) && g[r][c] == 0 && cand[r][c] and bit(v) != 0) { cand[r][c] = cand[r][c] and bit(v).inv(); changed = true } }
            if (cols.size == 1) { val c = cols.first()
                for (r in 0 until n) if ((r < b0 || r >= b0 + br) && g[r][c] == 0 && cand[r][c] and bit(v) != 0) { cand[r][c] = cand[r][c] and bit(v).inv(); changed = true } }
        }
        // "claiming": in una riga/colonna il valore sta tutto in un riquadro
        for (line in 0 until n) for (dir in 0..1) for (v in 1..n) {
            val cells = (0 until n).map { if (dir == 0) line to it else it to line }
                .filter { (r, c) -> g[r][c] == 0 && cand[r][c] and bit(v) != 0 }
            if (cells.isEmpty()) continue
            val boxes = cells.map { (r, c) -> (r / br) to (c / bc) }.toSet()
            if (boxes.size == 1) {
                val (bR, bC) = boxes.first()
                for (r in bR * br until bR * br + br) for (c in bC * bc until bC * bc + bc) {
                    val inLine = if (dir == 0) r == line else c == line
                    if (!inLine && g[r][c] == 0 && cand[r][c] and bit(v) != 0) { cand[r][c] = cand[r][c] and bit(v).inv(); changed = true }
                }
            }
        }
        return changed
    }

    fun nakedPairs(n: Int, units: List<List<Int>>, g: Array<IntArray>, cand: Array<IntArray>): Boolean {
        var changed = false
        for (u in units) {
            val open = u.filter { g[it / n][it % n] == 0 }
            for (a in open) {
                val m = cand[a / n][a % n]
                if (Integer.bitCount(m) != 2) continue
                val twins = open.filter { cand[it / n][it % n] == m }
                if (twins.size != 2) continue
                for (o in open) if (o !in twins && cand[o / n][o % n] and m != 0) {
                    cand[o / n][o % n] = cand[o / n][o % n] and m.inv(); changed = true
                }
            }
        }
        return changed
    }

    fun hiddenPairs(n: Int, units: List<List<Int>>, g: Array<IntArray>, cand: Array<IntArray>): Boolean {
        var changed = false
        for (u in units) {
            val open = u.filter { g[it / n][it % n] == 0 }
            val pos = (1..n).associateWith { v -> open.filter { cand[it / n][it % n] and bit(v) != 0 } }
            for (v1 in 1..n) for (v2 in v1 + 1..n) {
                val p1 = pos[v1]!!; val p2 = pos[v2]!!
                if (p1.size == 2 && p1 == p2) {
                    val m = bit(v1) or bit(v2)
                    for (i in p1) if (cand[i / n][i % n] != m) { cand[i / n][i % n] = cand[i / n][i % n] and m; changed = true }
                }
            }
        }
        return changed
    }

    private fun xWing(n: Int, g: Array<IntArray>, cand: Array<IntArray>): Boolean {
        var changed = false
        for (dir in 0..1) for (v in 1..n) {
            val lines = (0 until n).map { l ->
                (0 until n).filter { k -> val (r, c) = if (dir == 0) l to k else k to l; g[r][c] == 0 && cand[r][c] and bit(v) != 0 }
            }
            for (a in 0 until n) for (b in a + 1 until n) {
                if (lines[a].size == 2 && lines[a] == lines[b]) {
                    for (k in lines[a]) for (l in 0 until n) if (l != a && l != b) {
                        val (r, c) = if (dir == 0) l to k else k to l
                        if (g[r][c] == 0 && cand[r][c] and bit(v) != 0) { cand[r][c] = cand[r][c] and bit(v).inv(); changed = true }
                    }
                }
            }
        }
        return changed
    }
}

// ------------------------------------------------------------------ KenKen

object KenKenLogic {
    /** Livello usato (0..2) o -1. */
    fun solve(p: KenKenP, maxLevel: Int): Int {
        val n = p.n
        val all = (1 shl n) - 1
        val cand = Array(n) { IntArray(n) { all } }
        val units = ArrayList<List<Int>>()
        for (r in 0 until n) units += (0 until n).map { r * n + it }
        for (c in 0 until n) units += (0 until n).map { it * n + c }
        val g = Array(n) { IntArray(n) } // solo per riusare le coppie del Sudoku
        var used = 0
        var guard = 0
        while (guard++ < 500) {
            var progress = false
            // vincoli delle gabbie: tieni solo cifre che compaiono in qualche combinazione valida
            for (cg in p.cages) {
                val allowed = cageAllowed(cg, cand)
                for ((k, rc) in cg.cells.withIndex()) {
                    val (r, c) = rc
                    val nm = cand[r][c] and allowed[k]
                    if (nm != cand[r][c]) { cand[r][c] = nm; progress = true }
                }
            }
            // quadrato latino: valori certi tolti da riga/colonna, single nascosti
            for (u in units) {
                for (i in u) {
                    val m = cand[i / n][i % n]
                    if (single(m)) for (o in u) if (o != i && cand[o / n][o % n] and m != 0) {
                        cand[o / n][o % n] = cand[o / n][o % n] and m.inv(); progress = true
                    }
                }
                for (v in 1..n) {
                    val where = u.filter { cand[it / n][it % n] and bit(v) != 0 }
                    if (where.size == 1 && cand[where[0] / n][where[0] % n] != bit(v)) {
                        cand[where[0] / n][where[0] % n] = bit(v); progress = true
                    }
                }
            }
            if ((0 until n * n).any { cand[it / n][it % n] == 0 }) return -1
            if ((0 until n * n).all { single(cand[it / n][it % n]) }) {
                val sol = Array(n) { r -> IntArray(n) { c -> valueOf(cand[r][c]) } }
                return if (KenKen.solved(p, sol)) used else -1
            }
            if (progress) continue
            if (maxLevel >= 1 && SudokuLogic.nakedPairs(n, units, g, cand)) { used = maxOf(used, 1); continue }
            if (maxLevel >= 2 && SudokuLogic.hiddenPairs(n, units, g, cand)) { used = 2; continue }
            return -1
        }
        return -1
    }

    private fun cageAllowed(cg: Cage, cand: Array<IntArray>): IntArray {
        val k = cg.cells.size
        val out = IntArray(k)
        val cur = IntArray(k)
        fun ok(): Boolean {
            val vals = cur.toList()
            return when (cg.op) {
                '=' -> vals[0] == cg.target
                '+' -> vals.sum() == cg.target
                '×' -> vals.fold(1) { a, b -> a * b } == cg.target
                '-' -> kotlin.math.abs(vals[0] - vals[1]) == cg.target
                '÷' -> maxOf(vals[0], vals[1]) == cg.target * minOf(vals[0], vals[1])
                else -> false
            }
        }
        fun rec(i: Int) {
            if (i == k) { if (ok()) for (j in 0 until k) out[j] = out[j] or bit(cur[j]); return }
            val (r, c) = cg.cells[i]
            var m = cand[r][c]
            while (m != 0) {
                val b = m and -m; m = m xor b
                val v = valueOf(b)
                // nella stessa riga/colonna della gabbia le cifre devono essere diverse
                var clash = false
                for (j in 0 until i) {
                    val (r2, c2) = cg.cells[j]
                    if ((r2 == r || c2 == c) && cur[j] == v) { clash = true; break }
                }
                if (clash) continue
                cur[i] = v; rec(i + 1)
            }
        }
        rec(0)
        return out
    }
}

// ------------------------------------------------------------------ Kakuro

object KakuroLogic {
    private class Run(val cells: List<Pair<Int, Int>>, val sum: Int)

    private fun runs(p: KakuroP): List<Run> {
        val out = ArrayList<Run>()
        for (r in 0 until p.size) for (c in 0 until p.size) if (!p.white[r][c]) {
            for (dir in 0..1) {
                val clue = if (dir == 0) p.across[r][c] else p.down[r][c]
                if (clue == 0) continue
                val cells = ArrayList<Pair<Int, Int>>()
                var rr = r + dir; var cc = c + (1 - dir)
                while (rr < p.size && cc < p.size && p.white[rr][cc]) { cells += rr to cc; rr += dir; cc += 1 - dir }
                out += Run(cells, clue)
            }
        }
        return out
    }

    /** true se si risolve solo per deduzione (combinazioni di somma + incroci). */
    fun solve(p: KakuroP): Boolean {
        val cand = propagate(p) ?: return false
        for (r in 0 until p.size) for (c in 0 until p.size) if (p.white[r][c] && !single(cand[r][c])) return false
        return true
    }

    /** Candidati dopo tutte le deduzioni possibili (null = contraddizione). */
    fun propagate(p: KakuroP): Array<IntArray>? {
        val all = (1 shl 9) - 1
        val cand = Array(p.size) { r -> IntArray(p.size) { c -> if (p.white[r][c]) all else 0 } }
        val rs = runs(p)
        var guard = 0
        while (guard++ < 200) {
            var progress = false
            for (run in rs) {
                val allowed = runAllowed(run, cand)
                for ((k, rc) in run.cells.withIndex()) {
                    val nm = cand[rc.first][rc.second] and allowed[k]
                    if (nm == 0) return null
                    if (nm != cand[rc.first][rc.second]) { cand[rc.first][rc.second] = nm; progress = true }
                }
            }
            if (!progress) return cand
        }
        return cand
    }

    fun isSingle(m: Int) = single(m)

    private fun runAllowed(run: Run, cand: Array<IntArray>): IntArray {
        val k = run.cells.size
        val out = IntArray(k)
        val cur = IntArray(k)
        fun rec(i: Int, used: Int, sum: Int) {
            if (sum > run.sum) return
            if (i == k) { if (sum == run.sum) for (j in 0 until k) out[j] = out[j] or bit(cur[j]); return }
            // limite: anche con le cifre più grandi non si arriva alla somma
            var m = cand[run.cells[i].first][run.cells[i].second] and used.inv()
            while (m != 0) {
                val b = m and -m; m = m xor b
                val v = valueOf(b)
                cur[i] = v
                rec(i + 1, used or b, sum + v)
            }
        }
        rec(0, 0, 0)
        return out
    }
}

// ------------------------------------------------------------------ Sumplete

object SumpleteLogic {
    /**
     * Restituisce il numero di "giri" di deduzione necessari (≥1), oppure -1 se bloccato.
     * Un giro = passare tutte le righe e poi tutte le colonne.
     */
    fun solve(p: SumpleteP): Int {
        val n = p.n
        // 0 = da decidere, 1 = tenuto, 2 = tolto
        val st = Array(n) { IntArray(n) }
        var rounds = 0
        while (rounds < 50) {
            rounds++
            var progress = false
            for (dir in 0..1) for (line in 0 until n) {
                val cells = (0 until n).map { if (dir == 0) line to it else it to line }
                val target = if (dir == 0) p.rowSums[line] else p.colSums[line]
                val open = cells.filter { (r, c) -> st[r][c] == 0 }
                if (open.isEmpty()) continue
                val fixed = cells.filter { (r, c) -> st[r][c] == 1 }.sumOf { (r, c) -> p.grid[r][c] }
                var andMask = (1 shl open.size) - 1
                var orMask = 0
                var any = false
                for (m in 0 until (1 shl open.size)) {
                    var s = fixed
                    for (j in open.indices) if (m and (1 shl j) != 0) s += p.grid[open[j].first][open[j].second]
                    if (s == target) { any = true; andMask = andMask and m; orMask = orMask or m }
                }
                if (!any) return -1
                for (j in open.indices) {
                    val (r, c) = open[j]
                    if (andMask and (1 shl j) != 0) { st[r][c] = 1; progress = true }
                    else if (orMask and (1 shl j) == 0) { st[r][c] = 2; progress = true }
                }
            }
            if (st.all { row -> row.all { it != 0 } }) return rounds
            if (!progress) return -1
        }
        return -1
    }
}

// ------------------------------------------------------------------ Sequenze

object SequenceCheck {
    /** Tutti i seguiti possibili secondo le regole del gioco che spiegano i numeri mostrati. */
    fun predictions(t: List<Long>): Set<Long> {
        val out = HashSet<Long>()
        val n = t.size
        fun diffs(x: List<Long>) = x.zipWithNext { a, b -> b - a }
        // differenze costanti di ordine 1, 2, 3 (aritmetiche, quadrati, cubi…)
        var d = t
        val lasts = ArrayList<Long>()
        for (order in 1..3) {
            lasts += d.last()
            d = diffs(d)
            if (d.size >= 2 && d.all { it == d[0] }) { out += lasts.sum() + d.last(); break }
        }
        // rapporto costante intero
        if (t.all { it != 0L } && (1 until n).all { t[it] % t[it - 1] == 0L }) {
            val q = t[1] / t[0]
            if (q != 1L && (1 until n).all { t[it] == t[it - 1] * q }) out += t.last() * q
        }
        // somma dei due precedenti
        if (n >= 3 && (2 until n).all { t[it] == t[it - 1] + t[it - 2] }) out += t[n - 1] + t[n - 2]
        // x → 2x + k
        if (n >= 3) {
            val k = t[1] - 2 * t[0]
            if ((1 until n).all { t[it] == 2 * t[it - 1] + k }) out += 2 * t.last() + k
        }
        // due serie aritmetiche alternate (servono almeno 3 numeri per ciascuna)
        val ev = t.filterIndexed { i, _ -> i % 2 == 0 }; val od = t.filterIndexed { i, _ -> i % 2 == 1 }
        val nextIsEven = n % 2 == 0
        val same = if (nextIsEven) ev else od
        if (ev.size >= 2 && od.size >= 2) {
            val ed = diffs(ev); val oddD = diffs(od)
            val evOk = ed.all { it == ed[0] }; val odOk = oddD.all { it == oddD[0] }
            // vale solo se la serie che continua ha almeno 3 numeri (altrimenti è un'ipotesi debole)
            if (evOk && odOk && same.size >= 3) out += same.last() + diffs(same)[0]
        }
        return out
    }

    /** La serie è "pulita" se ogni regola che la spiega porta allo stesso seguito. */
    fun unambiguous(t: List<Long>, answer: Long): Boolean {
        val p = predictions(t)
        return p.isNotEmpty() && p.all { it == answer }
    }
}
