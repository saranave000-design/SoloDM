package com.saranave.solodm.games

import kotlin.random.Random

/*
 * Logica pura dei giochi (niente Android): generatori casuali e controlli.
 * Ogni partita è generata da zero: le basi di partenza non si ripetono.
 * I controlli verificano le REGOLE, non una soluzione memorizzata:
 * qualsiasi soluzione corretta viene accettata.
 */

// ------------------------------------------------------------------ frazioni esatte

class Frac private constructor(val n: Long, val d: Long) {
    companion object {
        fun of(n: Long, d: Long = 1L): Frac {
            require(d != 0L)
            var a = n; var b = d
            if (b < 0) { a = -a; b = -b }
            val g = gcd(kotlin.math.abs(a), b).coerceAtLeast(1)
            return Frac(a / g, b / g)
        }
        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
    operator fun plus(o: Frac) = of(n * o.d + o.n * d, d * o.d)
    operator fun minus(o: Frac) = of(n * o.d - o.n * d, d * o.d)
    operator fun times(o: Frac) = of(n * o.n, d * o.d)
    operator fun div(o: Frac): Frac? = if (o.n == 0L) null else of(n * o.d, d * o.n)
    val isInt get() = d == 1L
    override fun equals(other: Any?) = other is Frac && other.n == n && other.d == d
    override fun hashCode() = (n * 31 + d).toInt()
    override fun toString() = if (d == 1L) "$n" else "$n/$d"
}

fun applyOp(a: Frac, op: Char, b: Frac): Frac? = when (op) {
    '+' -> a + b
    '-' -> a - b
    '×' -> a * b
    '÷' -> a / b
    else -> null
}

// ------------------------------------------------------------------ 24 e Numero bersaglio

object Cards {
    private class Item(val v: Frac, val expr: String)

    /** Cerca un modo di ottenere target. intOnly = regole "bersaglio" (solo interi positivi). */
    fun solve(nums: List<Int>, target: Int, intOnly: Boolean, allowDiv: Boolean = true, useAll: Boolean = true): String? {
        val items = nums.map { Item(Frac.of(it.toLong()), "$it") }
        return search(items, Frac.of(target.toLong()), intOnly, allowDiv, useAll)
    }

    private fun search(items: List<Item>, target: Frac, intOnly: Boolean, allowDiv: Boolean, useAll: Boolean): String? {
        if (!useAll) items.firstOrNull { it.v == target }?.let { return it.expr }
        if (items.size == 1) return if (items[0].v == target) items[0].expr else null
        for (i in items.indices) for (j in items.indices) {
            if (i == j) continue
            val a = items[i]; val b = items[j]
            val rest = items.filterIndexed { k, _ -> k != i && k != j }
            val ops = if (allowDiv) "+-×÷" else "+-×"
            for (op in ops) {
                if ((op == '+' || op == '×') && i > j) continue // commutative
                val r = applyOp(a.v, op, b.v) ?: continue
                if (intOnly && (!r.isInt || r.n <= 0)) continue
                val e = "(${a.expr} $op ${b.expr})"
                val res = search(rest + Item(r, e), target, intOnly, allowDiv, useAll)
                if (res != null) return res
            }
        }
        return null
    }

    /** level 0..2 → numeri del 24. Facile: risolvibile senza divisioni. */
    fun gen24(level: Int, rnd: Random): Pair<List<Int>, String> {
        val max = when (level) { 0 -> 9; 1 -> 10; else -> 13 }
        while (true) {
            val nums = List(4) { rnd.nextInt(1, max + 1) }
            val sol = solve(nums, 24, intOnly = false, allowDiv = level > 0) ?: continue
            if (level == 2) {
                // Difficile: niente soluzioni "solo + e ×" banali
                if (solve(nums, 24, intOnly = true, allowDiv = false) != null && rnd.nextInt(3) != 0) continue
            }
            return nums to stripOuter(sol)
        }
    }

    /** Numero bersaglio: 6 numeri, si costruisce il bersaglio combinandoli (quindi è sempre risolvibile). */
    fun genTarget(level: Int, rnd: Random): Triple<List<Int>, Int, String> {
        val bigCount = when (level) { 0 -> 1; 1 -> rnd.nextInt(1, 3); else -> rnd.nextInt(1, 4) }
        val steps = when (level) { 0 -> 2; 1 -> 3; else -> rnd.nextInt(4, 6) }
        val (lo, hi) = when (level) { 0 -> 20 to 150; 1 -> 101 to 600; else -> 201 to 999 }
        while (true) {
            val big = listOf(25, 50, 75, 100).shuffled(rnd).take(bigCount)
            val small = List(6 - bigCount) { rnd.nextInt(1, 11) }
            val nums = (big + small).shuffled(rnd)
            var pool = nums.map { Item(Frac.of(it.toLong()), "$it") }.toMutableList()
            var ok = true
            repeat(steps) {
                if (pool.size < 2) { ok = false; return@repeat }
                pool.shuffle(rnd)
                val a = pool.removeAt(0); val b = pool.removeAt(0)
                val (x, y) = if (a.v.n >= b.v.n) a to b else b to a
                val candidates = mutableListOf<Item>()
                candidates += Item(x.v + y.v, "(${x.expr} + ${y.expr})")
                if (x.v.n - y.v.n > 0) candidates += Item(x.v - y.v, "(${x.expr} - ${y.expr})")
                if (y.v.n > 1 && x.v.n > 1) candidates += Item(x.v * y.v, "(${x.expr} × ${y.expr})")
                if (y.v.n > 1 && x.v.n % y.v.n == 0L) candidates += Item(Frac.of(x.v.n / y.v.n), "(${x.expr} ÷ ${y.expr})")
                pool.add(candidates[rnd.nextInt(candidates.size)])
            }
            if (!ok) continue
            val last = pool.last()
            val t = last.v.n.toInt()
            if (t in lo..hi && t !in nums) return Triple(nums, t, stripOuter(last.expr))
        }
    }

    private fun stripOuter(s: String) = if (s.startsWith("(") && s.endsWith(")")) s.substring(1, s.length - 1) else s
}

// ------------------------------------------------------------------ Equazione nascosta (tipo Nerdle)

object Equation {
    /** Valuta "12+3*4" con le precedenze. null se non valida o non intera. */
    fun evalExpr(s: String): Long? {
        if (s.isEmpty()) return null
        val nums = ArrayList<Long>(); val ops = ArrayList<Char>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isDigit()) return null
            val st = i
            while (i < s.length && s[i].isDigit()) i++
            val tok = s.substring(st, i)
            if (tok.length > 1 && tok[0] == '0') return null
            if (tok.length > 6) return null
            nums += tok.toLong()
            if (i < s.length) {
                if (s[i] !in "+-*/") return null
                ops += s[i]; i++
                if (i == s.length) return null
            }
        }
        // prima * e /
        val n2 = ArrayList<Frac>(); val o2 = ArrayList<Char>()
        n2 += Frac.of(nums[0])
        for (k in ops.indices) {
            val b = Frac.of(nums[k + 1])
            when (ops[k]) {
                '*' -> n2[n2.size - 1] = n2.last() * b
                '/' -> n2[n2.size - 1] = (n2.last() / b) ?: return null
                else -> { o2 += ops[k]; n2 += b }
            }
        }
        var acc = n2[0]
        for (k in o2.indices) acc = if (o2[k] == '+') acc + n2[k + 1] else acc - n2[k + 1]
        return if (acc.isInt) acc.n else null
    }

    fun isValid(eq: String, len: Int): Boolean {
        if (eq.length != len || eq.count { it == '=' } != 1) return false
        val (l, r) = eq.split('=')
        if (r.isEmpty() || !r.all { it.isDigit() } || (r.length > 1 && r[0] == '0')) return false
        if (l.none { it in "+-*/" }) return false
        val v = evalExpr(l) ?: return false
        return v == r.toLong()
    }

    fun generate(len: Int, rnd: Random): String {
        while (true) {
            val ops = if (len >= 8 && rnd.nextBoolean()) 2 else 1
            val sb = StringBuilder()
            repeat(ops + 1) { k ->
                val digits = if (len <= 6) 1 else rnd.nextInt(1, 3)
                val v = if (digits == 1) rnd.nextInt(1, 10) else rnd.nextInt(10, 100)
                sb.append(v)
                if (k < ops) sb.append("+-*/"[rnd.nextInt(4)])
            }
            val v = evalExpr(sb.toString()) ?: continue
            if (v < 0) continue
            val eq = "$sb=$v"
            if (eq.length == len && isValid(eq, len)) return eq
        }
    }

    /** 2 = posto giusto, 1 = c'è ma altrove, 0 = non c'è (gestisce i doppioni come Wordle). */
    fun feedback(guess: String, answer: String): IntArray {
        val res = IntArray(guess.length)
        val left = HashMap<Char, Int>()
        for (i in guess.indices) {
            if (guess[i] == answer[i]) res[i] = 2 else left[answer[i]] = (left[answer[i]] ?: 0) + 1
        }
        for (i in guess.indices) {
            if (res[i] == 2) continue
            val c = left[guess[i]] ?: 0
            if (c > 0) { res[i] = 1; left[guess[i]] = c - 1 }
        }
        return res
    }
}

// ------------------------------------------------------------------ Sumplete

class SumpleteP(val n: Int, val grid: Array<IntArray>, val rowSums: IntArray, val colSums: IntArray)

object Sumplete {
    /** level 0..2 → 5×5, 6×6, 7×7; più il livello è alto, più giri di ragionamento servono. */
    fun generateLevel(level: Int, rnd: Random): SumpleteP {
        val n = 5 + level
        val minRounds = when (level) { 0 -> 2; 1 -> 3; else -> 4 }
        val deadline = System.nanoTime() + 1_500_000_000L
        var best: SumpleteP? = null
        var bestRounds = 0
        while (true) {
            val p = generate(n, rnd)
            val rounds = SumpleteLogic.solve(p)
            if (rounds < 0) continue
            if (rounds >= minRounds) return p
            if (rounds > bestRounds) { best = p; bestRounds = rounds }
            if (System.nanoTime() > deadline && best != null) return best
        }
    }

    fun generate(n: Int, rnd: Random): SumpleteP {
        while (true) {
            val g = Array(n) { IntArray(n) { rnd.nextInt(1, 10) } }
            val keep = Array(n) { BooleanArray(n) { rnd.nextInt(100) < 55 } }
            // almeno uno tenuto e uno tolto per riga/colonna
            var ok = true
            for (r in 0 until n) if (keep[r].all { it } || keep[r].none { it }) ok = false
            for (c in 0 until n) if ((0 until n).all { keep[it][c] } || (0 until n).none { keep[it][c] }) ok = false
            if (!ok) continue
            val rs = IntArray(n) { r -> (0 until n).sumOf { c -> if (keep[r][c]) g[r][c] else 0 } }
            val cs = IntArray(n) { c -> (0 until n).sumOf { r -> if (keep[r][c]) g[r][c] else 0 } }
            return SumpleteP(n, g, rs, cs)
        }
    }

    /** crossed[r][c] = true se il numero è stato tolto. */
    fun rowOk(p: SumpleteP, crossed: Array<BooleanArray>, r: Int) =
        (0 until p.n).sumOf { c -> if (crossed[r][c]) 0 else p.grid[r][c] } == p.rowSums[r]

    fun colOk(p: SumpleteP, crossed: Array<BooleanArray>, c: Int) =
        (0 until p.n).sumOf { r -> if (crossed[r][c]) 0 else p.grid[r][c] } == p.colSums[c]

    fun solved(p: SumpleteP, crossed: Array<BooleanArray>) =
        (0 until p.n).all { rowOk(p, crossed, it) } && (0 until p.n).all { colOk(p, crossed, it) }
}

// ------------------------------------------------------------------ KenKen

class Cage(val cells: List<Pair<Int, Int>>, val op: Char, val target: Int) {
    val label get() = if (op == '=') "$target" else "$target$op"
}

class KenKenP(val n: Int, val cages: List<Cage>, val solution: Array<IntArray>) {
    val cageOf = Array(n) { IntArray(n) }.also { a -> cages.forEachIndexed { i, cg -> cg.cells.forEach { (r, c) -> a[r][c] = i } } }
}

object Latin {
    fun generate(n: Int, rnd: Random): Array<IntArray> {
        val base = Array(n) { r -> IntArray(n) { c -> (r + c) % n + 1 } }
        val rows = (0 until n).shuffled(rnd); val cols = (0 until n).shuffled(rnd)
        val sym = (1..n).shuffled(rnd)
        return Array(n) { r -> IntArray(n) { c -> sym[base[rows[r]][cols[c]] - 1] } }
    }
}

object KenKen {
    /** Schema a soluzione unica, risolvibile con la logica del livello richiesto. */
    fun generate(n: Int, level: Int, rnd: Random): KenKenP {
        val deadline = System.nanoTime() + 1_500_000_000L
        var fallback: KenKenP? = null
        while (true) {
            val p = generateRaw(n, level, rnd)
            val used = KenKenLogic.solve(p, level)
            if (used < 0) continue
            if (used == level || level == 0) return p
            if (fallback == null) fallback = p
            if (System.nanoTime() > deadline) return fallback
        }
    }

    fun generateRaw(n: Int, level: Int, rnd: Random): KenKenP {
        val sol = Latin.generate(n, rnd)
        val owner = Array(n) { IntArray(n) { -1 } }
        val cages = ArrayList<List<Pair<Int, Int>>>()
        val maxSize = if (level == 0) 2 else 3
        val order = (0 until n * n).shuffled(rnd)
        for (idx in order) {
            val r0 = idx / n; val c0 = idx % n
            if (owner[r0][c0] >= 0) continue
            val want = when {
                level == 0 -> if (rnd.nextInt(100) < 20) 1 else 2
                level == 1 -> listOf(1, 2, 2, 2, 3)[rnd.nextInt(5)]
                else -> listOf(2, 2, 3, 3, 3)[rnd.nextInt(5)]
            }.coerceAtMost(maxSize)
            val cells = mutableListOf(r0 to c0)
            owner[r0][c0] = cages.size
            while (cells.size < want) {
                val nb = cells.flatMap { (r, c) -> listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1) }
                    .filter { (r, c) -> r in 0 until n && c in 0 until n && owner[r][c] < 0 }
                if (nb.isEmpty()) break
                val pick = nb[rnd.nextInt(nb.size)]
                owner[pick.first][pick.second] = cages.size
                cells += pick
            }
            cages += cells
        }
        val out = cages.map { cells ->
            val vals = cells.map { (r, c) -> sol[r][c] }
            if (cells.size == 1) return@map Cage(cells, '=', vals[0])
            if (cells.size == 2) {
                val a = maxOf(vals[0], vals[1]); val b = minOf(vals[0], vals[1])
                val opts = mutableListOf('+', '-', '×')
                if (a % b == 0) { opts += '÷'; opts += '÷' }
                if (level == 0) opts.remove('×')
                return@map when (opts[rnd.nextInt(opts.size)]) {
                    '+' -> Cage(cells, '+', a + b)
                    '-' -> Cage(cells, '-', a - b)
                    '×' -> Cage(cells, '×', a * b)
                    else -> Cage(cells, '÷', a / b)
                }
            }
            if (rnd.nextBoolean()) Cage(cells, '+', vals.sum()) else Cage(cells, '×', vals.fold(1) { x, y -> x * y })
        }
        return KenKenP(n, out, sol)
    }

    fun cageOk(cg: Cage, g: Array<IntArray>): Boolean? {
        val vals = cg.cells.map { (r, c) -> g[r][c] }
        if (vals.any { it == 0 }) return null
        return when (cg.op) {
            '=' -> vals[0] == cg.target
            '+' -> vals.sum() == cg.target
            '×' -> vals.fold(1) { x, y -> x * y } == cg.target
            '-' -> kotlin.math.abs(vals[0] - vals[1]) == cg.target
            '÷' -> maxOf(vals[0], vals[1]) == cg.target * minOf(vals[0], vals[1])
            else -> false
        }
    }

    fun solved(p: KenKenP, g: Array<IntArray>): Boolean =
        Grids.latinOk(g, p.n) && p.cages.all { cageOk(it, g) == true }
}

object Grids {
    fun latinOk(g: Array<IntArray>, n: Int): Boolean {
        for (i in 0 until n) {
            if ((0 until n).map { g[i][it] }.toSet() != (1..n).toSet()) return false
            if ((0 until n).map { g[it][i] }.toSet() != (1..n).toSet()) return false
        }
        return true
    }

    /** Celle in conflitto (stesso numero nella stessa riga/colonna/riquadro). */
    fun conflicts(g: Array<IntArray>, n: Int, boxR: Int, boxC: Int): Set<Pair<Int, Int>> {
        val bad = HashSet<Pair<Int, Int>>()
        for (r in 0 until n) for (c in 0 until n) {
            val v = g[r][c]; if (v == 0) continue
            for (k in 0 until n) {
                if (k != c && g[r][k] == v) bad += r to c
                if (k != r && g[k][c] == v) bad += r to c
            }
            if (boxR > 0) {
                val br = r / boxR * boxR; val bc = c / boxC * boxC
                for (rr in br until br + boxR) for (cc in bc until bc + boxC)
                    if ((rr != r || cc != c) && g[rr][cc] == v) bad += r to c
            }
        }
        return bad
    }
}

// ------------------------------------------------------------------ Sudoku

class SudokuP(val n: Int, val boxR: Int, val boxC: Int, val givens: Array<IntArray>)

object Sudoku {
    fun generate(level: Int, rnd: Random): SudokuP {
        val (n, br, bc) = if (level == 0) Triple(6, 2, 3) else Triple(9, 3, 3)
        val full = Array(n) { IntArray(n) }
        fill(full, n, br, bc, rnd)
        val maxLevel = when (level) { 0 -> 0; 1 -> 1; else -> 2 }
        val minGivens = when (level) { 0 -> 12; 1 -> 26; else -> 22 }
        val deadline = System.nanoTime() + 1_500_000_000L
        var fallback: SudokuP? = null
        while (true) {
            val puzzle = Array(n) { full[it].clone() }
            var givens = n * n
            for (idx in (0 until n * n).shuffled(rnd)) {
                if (givens <= minGivens) break
                val r = idx / n; val c = idx % n
                val keep = puzzle[r][c]
                puzzle[r][c] = 0
                // si toglie solo se resta risolvibile con il ragionamento del livello
                if (SudokuLogic.solve(puzzle, n, br, bc, maxLevel) < 0) puzzle[r][c] = keep else givens--
            }
            val p = SudokuP(n, br, bc, puzzle)
            val used = SudokuLogic.solve(puzzle, n, br, bc, maxLevel)
            // Difficile: deve servire davvero almeno una tecnica avanzata
            if (level < 2 || used >= 1) {
                if (level < 2 || used == 2 || System.nanoTime() > deadline) return p
                if (fallback == null) fallback = p
            }
            if (System.nanoTime() > deadline) return fallback ?: p
            for (row in full) java.util.Arrays.fill(row, 0)
            fill(full, n, br, bc, rnd)
        }
    }

    private fun ok(g: Array<IntArray>, n: Int, br: Int, bc: Int, r: Int, c: Int, v: Int): Boolean {
        for (k in 0 until n) if (g[r][k] == v || g[k][c] == v) return false
        val r0 = r / br * br; val c0 = c / bc * bc
        for (rr in r0 until r0 + br) for (cc in c0 until c0 + bc) if (g[rr][cc] == v) return false
        return true
    }

    private fun fill(g: Array<IntArray>, n: Int, br: Int, bc: Int, rnd: Random): Boolean {
        for (r in 0 until n) for (c in 0 until n) if (g[r][c] == 0) {
            for (v in (1..n).shuffled(rnd)) {
                if (ok(g, n, br, bc, r, c, v)) {
                    g[r][c] = v
                    if (fill(g, n, br, bc, rnd)) return true
                    g[r][c] = 0
                }
            }
            return false
        }
        return true
    }

    fun countSolutions(g: Array<IntArray>, n: Int, br: Int, bc: Int, limit: Int): Int {
        // cella vuota con meno candidati
        var bestR = -1; var bestC = -1; var bestCount = n + 1
        for (r in 0 until n) for (c in 0 until n) if (g[r][c] == 0) {
            var cnt = 0
            for (v in 1..n) if (ok(g, n, br, bc, r, c, v)) cnt++
            if (cnt < bestCount) { bestCount = cnt; bestR = r; bestC = c }
            if (cnt == 0) return 0
        }
        if (bestR < 0) return 1
        var total = 0
        for (v in 1..n) if (ok(g, n, br, bc, bestR, bestC, v)) {
            g[bestR][bestC] = v
            total += countSolutions(g, n, br, bc, limit - total)
            g[bestR][bestC] = 0
            if (total >= limit) return total
        }
        return total
    }

    fun solved(p: SudokuP, g: Array<IntArray>): Boolean =
        g.all { row -> row.all { it != 0 } } && Grids.conflicts(g, p.n, p.boxR, p.boxC).isEmpty()
}

// ------------------------------------------------------------------ Kakuro

/** size = lato totale (riga/colonna 0 sono solo indizi). white[r][c] = cella da riempire. */
class KakuroP(
    val size: Int,
    val white: Array<BooleanArray>,
    val across: Array<IntArray>, // indizio della corsa verso destra che parte da (r,c+1); 0 = nessuno
    val down: Array<IntArray>,   // indizio della corsa verso il basso
    val solution: Array<IntArray>
)

object Kakuro {
    /**
     * Schema a soluzione unica e risolvibile per deduzione.
     * Metodo: griglia con gruppi corti → riempimento → se il ragionamento si blocca,
     * si cambia la cifra di una casella ancora incerta e si ricalcolano le somme ("riparazione").
     */
    fun generate(level: Int, rnd: Random): KakuroP {
        val deadline = System.nanoTime() + 2_500_000_000L
        while (true) {
            val (size, white) = layout(level, rnd)
            val sol = Array(size) { IntArray(size) }
            val cells = (1 until size).flatMap { r -> (1 until size).filter { white[r][it] }.map { r to it } }
            if (!fill(sol, white, cells, 0, size, rnd)) continue
            repeat(80) {
                val p = build(size, white, sol)
                val cand = KakuroLogic.propagate(p) ?: return@repeat
                val open = cells.filter { (r, c) -> !KakuroLogic.isSingle(cand[r][c]) }
                if (open.isEmpty()) return p
                // ripara: cambia una cifra in una casella incerta, senza ripetizioni nei suoi gruppi
                val (r, c) = open[rnd.nextInt(open.size)]
                val options = (1..9).filter { v -> v != sol[r][c] && !usedInRun(sol, white, r, c, v, size) }
                if (options.isNotEmpty()) sol[r][c] = options[rnd.nextInt(options.size)]
            }
            if (System.nanoTime() > deadline && level > 0) return generate(level - 1, rnd)
        }
    }

    /** Griglia con caselle nere: gruppi lunghi al massimo maxRun, nessun gruppo da 1. */
    private fun layout(level: Int, rnd: Random): Pair<Int, Array<BooleanArray>> {
        val inner = when (level) { 0 -> 4; 1 -> 5; else -> 6 }
        val maxRun = when (level) { 0 -> 3; 1 -> 4; else -> 4 }
        val size = inner + 1
        while (true) {
            val white = Array(size) { r -> BooleanArray(size) { c -> r > 0 && c > 0 && rnd.nextInt(100) >= 18 } }
            // spezza i gruppi troppo lunghi
            for (dir in 0..1) for (line in 1 until size) {
                var run = 0
                for (k in 1 until size) {
                    val (r, c) = if (dir == 0) line to k else k to line
                    if (white[r][c]) { run++; if (run > maxRun) { white[r][c] = false; run = 0 } } else run = 0
                }
            }
            var changed = true
            while (changed) {
                changed = false
                for (r in 1 until size) for (c in 1 until size) if (white[r][c]) {
                    val h = (c > 1 && white[r][c - 1]) || (c < size - 1 && white[r][c + 1])
                    val v = (r > 1 && white[r - 1][c]) || (r < size - 1 && white[r + 1][c])
                    if (!h || !v) { white[r][c] = false; changed = true }
                }
            }
            val whites = (1 until size).sumOf { r -> (1 until size).count { c -> white[r][c] } }
            if (whites >= inner * inner * 0.55) return size to white
        }
    }

    private fun build(size: Int, white: Array<BooleanArray>, sol: Array<IntArray>): KakuroP {
        val across = Array(size) { IntArray(size) }
        val down = Array(size) { IntArray(size) }
        for (r in 0 until size) for (c in 0 until size) if (!white[r][c]) {
            if (c + 1 < size && white[r][c + 1]) {
                var k = c + 1; var s = 0
                while (k < size && white[r][k]) { s += sol[r][k]; k++ }
                across[r][c] = s
            }
            if (r + 1 < size && white[r + 1][c]) {
                var k = r + 1; var s = 0
                while (k < size && white[k][c]) { s += sol[k][c]; k++ }
                down[r][c] = s
            }
        }
        return KakuroP(size, white, across, down, Array(size) { sol[it].clone() })
    }

    private fun fill(g: Array<IntArray>, w: Array<BooleanArray>, cells: List<Pair<Int, Int>>, i: Int, size: Int, rnd: Random): Boolean {
        if (i == cells.size) return true
        val (r, c) = cells[i]
        for (v in (1..9).shuffled(rnd)) {
            if (usedInRun(g, w, r, c, v, size)) continue
            g[r][c] = v
            if (fill(g, w, cells, i + 1, size, rnd)) return true
            g[r][c] = 0
        }
        return false
    }

    private fun usedInRun(g: Array<IntArray>, w: Array<BooleanArray>, r: Int, c: Int, v: Int, size: Int): Boolean {
        var k = c - 1; while (k >= 0 && w[r][k]) { if (g[r][k] == v) return true; k-- }
        k = c + 1; while (k < size && w[r][k]) { if (g[r][k] == v) return true; k++ }
        k = r - 1; while (k >= 0 && w[k][c]) { if (g[k][c] == v) return true; k-- }
        k = r + 1; while (k < size && w[k][c]) { if (g[k][c] == v) return true; k++ }
        return false
    }

    /** Celle sbagliate: doppioni nella corsa o somma completa errata. */
    fun errors(p: KakuroP, g: Array<IntArray>): Set<Pair<Int, Int>> {
        val bad = HashSet<Pair<Int, Int>>()
        for (r in 0 until p.size) for (c in 0 until p.size) if (!p.white[r][c]) {
            for (dir in 0..1) {
                val clue = if (dir == 0) p.across[r][c] else p.down[r][c]
                if (clue == 0) continue
                val run = ArrayList<Pair<Int, Int>>()
                var rr = r + dir; var cc = c + (1 - dir)
                while (rr < p.size && cc < p.size && p.white[rr][cc]) { run += rr to cc; rr += dir; cc += 1 - dir }
                val vals = run.map { (a, b) -> g[a][b] }
                val filled = vals.filter { it != 0 }
                if (filled.size != filled.toSet().size) {
                    run.filter { (a, b) -> g[a][b] != 0 && vals.count { it == g[a][b] } > 1 }.forEach { bad += it }
                }
                if (filled.size == vals.size && vals.sum() != clue) bad.addAll(run)
                if (filled.sum() > clue) bad.addAll(run.filter { (a, b) -> g[a][b] != 0 })
            }
        }
        return bad
    }

    fun solved(p: KakuroP, g: Array<IntArray>): Boolean {
        for (r in 0 until p.size) for (c in 0 until p.size) if (p.white[r][c] && g[r][c] == 0) return false
        return errors(p, g).isEmpty()
    }
}

// ------------------------------------------------------------------ Calcolo a mente

object MentalMath {
    /** Restituisce (testo, risultato). */
    fun question(level: Int, rnd: Random): Pair<String, Int> {
        when (level) {
            0 -> {
                val a = rnd.nextInt(5, 60); val b = rnd.nextInt(2, 40)
                return if (rnd.nextBoolean()) "$a + $b" to a + b else "${maxOf(a, b)} − ${minOf(a, b)}" to maxOf(a, b) - minOf(a, b)
            }
            1 -> return when (rnd.nextInt(4)) {
                0 -> { val a = rnd.nextInt(3, 13); val b = rnd.nextInt(3, 13); "$a × $b" to a * b }
                1 -> { val b = rnd.nextInt(2, 13); val q = rnd.nextInt(2, 13); "${b * q} ÷ $b" to q }
                2 -> { val a = rnd.nextInt(40, 250); val b = rnd.nextInt(20, 150); "$a + $b" to a + b }
                else -> { val a = rnd.nextInt(100, 300); val b = rnd.nextInt(20, 99); "$a − $b" to a - b }
            }
            else -> return when (rnd.nextInt(4)) {
                0 -> { val a = rnd.nextInt(11, 30); val b = rnd.nextInt(6, 20); "$a × $b" to a * b }
                1 -> { val a = rnd.nextInt(2, 12); val b = rnd.nextInt(2, 12); val c = rnd.nextInt(10, 60); "$a × $b + $c" to a * b + c }
                2 -> { val b = rnd.nextInt(3, 16); val q = rnd.nextInt(6, 25); "${b * q} ÷ $b" to q }
                else -> { val a = rnd.nextInt(20, 90); val b = rnd.nextInt(2, 9); val c = rnd.nextInt(5, 50); "($a − $c) × $b".let { s -> if (a > c) s to (a - c) * b else "($c − $a) × $b" to (c - a) * b } }
            }
        }
    }
}

// ------------------------------------------------------------------ Sequenze

object Sequences {
    /** Restituisce i numeri mostrati e il prossimo da indovinare. */
    fun generate(level: Int, rnd: Random): Pair<List<Long>, Long> {
        while (true) {
            val (shown, next) = generateRaw(level, rnd)
            if (SequenceCheck.unambiguous(shown, next)) return shown to next
        }
    }

    fun generateRaw(level: Int, rnd: Random): Pair<List<Long>, Long> {
        val kinds = when (level) { 0 -> 3; 1 -> 6; else -> 9 }
        val kind = rnd.nextInt(kinds)
        val len = if (kind == 6) 6 else 5
        val seq: List<Long> = when (kind) {
            0 -> { val a = rnd.nextLong(1, 30); val d = rnd.nextLong(2, 12); List(len + 1) { a + d * it } }
            1 -> { val a = rnd.nextLong(60, 120); val d = rnd.nextLong(3, 11); List(len + 1) { a - d * it } }
            2 -> { val a = rnd.nextLong(1, 5); val q = rnd.nextLong(2, 4); List(len + 1) { var v = a; repeat(it) { v *= q }; v } }
            3 -> { val k = rnd.nextLong(0, 6); val s = rnd.nextLong(1, 4); List(len + 1) { (it + s) * (it + s) + k } }
            4 -> { val a = rnd.nextLong(1, 6); val b = rnd.nextLong(1, 6); val l = mutableListOf(a, b); while (l.size < len + 1) l += l[l.size - 1] + l[l.size - 2]; l }
            5 -> { val a = rnd.nextLong(1, 20); val d = rnd.nextLong(1, 4); var v = a; var step = d; List(len + 1) { val out = v; v += step; step += d; out } }
            6 -> { val a = rnd.nextLong(1, 10); val p = rnd.nextLong(2, 9); val m = rnd.nextLong(1, 6); List(len + 1) { a + (it / 2) * (p - m) + if (it % 2 == 1) p else 0 } }
            7 -> { val a = rnd.nextLong(2, 6); val k = rnd.nextLong(1, 4); var v = a; List(len + 1) { val o = v; v = v * 2 + k; o } }
            else -> { val s = rnd.nextLong(1, 3); List(len + 1) { (it + s) * (it + s) * (it + s) } }
        }
        return seq.take(len) to seq[len]
    }
}
