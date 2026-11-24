package com.saranave.solodm.games

import kotlin.random.Random

/*
 * Futoshiki, Hitori, Binario: generatori + risolutori logici (solo deduzioni certe).
 * Come per gli altri giochi: se il risolutore chiude lo schema, la soluzione è unica
 * e c'è sempre una mossa certa.
 */

private fun b(v: Int) = 1 shl (v - 1)
private fun one(m: Int) = m != 0 && (m and (m - 1)) == 0
private fun valOf(m: Int) = Integer.numberOfTrailingZeros(m) + 1
private fun minOf(m: Int) = Integer.numberOfTrailingZeros(m) + 1
private fun maxOf(m: Int) = 32 - Integer.numberOfLeadingZeros(m)

// ================================================================== FUTOSHIKI

/**
 * right[r][c]: segno tra (r,c) e (r,c+1): 0 nessuno, -1 "<", +1 ">".
 * down[r][c]:  segno tra (r,c) e (r+1,c): 0 nessuno, -1 "<" (sopra minore), +1 ">".
 */
class FutoshikiP(
    val n: Int,
    val givens: Array<IntArray>,
    val right: Array<IntArray>,
    val down: Array<IntArray>,
    val solution: Array<IntArray>
)

object Futoshiki {
    fun generate(level: Int, rnd: Random): FutoshikiP {
        val n = 4 + level
        val deadline = System.nanoTime() + 1_500_000_000L
        var fallback: FutoshikiP? = null
        while (true) {
            val sol = Latin.generate(n, rnd)
            val right = Array(n) { IntArray(n) }
            val down = Array(n) { IntArray(n) }
            val givens = Array(n) { IntArray(n) }
            // segni su circa metà dei bordi
            for (r in 0 until n) for (c in 0 until n) {
                if (c + 1 < n && rnd.nextInt(100) < 45) right[r][c] = if (sol[r][c] < sol[r][c + 1]) -1 else 1
                if (r + 1 < n && rnd.nextInt(100) < 45) down[r][c] = if (sol[r][c] < sol[r + 1][c]) -1 else 1
            }
            var p = FutoshikiP(n, givens, right, down, sol)
            // aggiungi indizi finché la logica non chiude lo schema
            var guard = 0
            while (FutoshikiLogic.solve(p, level) < 0 && guard++ < n * n * 2) {
                val cand = FutoshikiLogic.candidates(p, level)
                val open = (0 until n * n).filter { cand == null || !one(cand[it / n][it % n]) }
                    .filter { givens[it / n][it % n] == 0 }
                if (open.isEmpty()) break
                val i = open[rnd.nextInt(open.size)]
                val r = i / n; val c = i % n
                // preferisci un segno nuovo; se non c'è spazio, un numero dato
                val edges = mutableListOf<() -> Unit>()
                if (c + 1 < n && right[r][c] == 0) edges += { right[r][c] = if (sol[r][c] < sol[r][c + 1]) -1 else 1 }
                if (c > 0 && right[r][c - 1] == 0) edges += { right[r][c - 1] = if (sol[r][c - 1] < sol[r][c]) -1 else 1 }
                if (r + 1 < n && down[r][c] == 0) edges += { down[r][c] = if (sol[r][c] < sol[r + 1][c]) -1 else 1 }
                if (r > 0 && down[r - 1][c] == 0) edges += { down[r - 1][c] = if (sol[r - 1][c] < sol[r][c]) -1 else 1 }
                if (edges.isNotEmpty() && rnd.nextInt(100) < 70) edges[rnd.nextInt(edges.size)]() else givens[r][c] = sol[r][c]
                p = FutoshikiP(n, givens, right, down, sol)
            }
            if (FutoshikiLogic.solve(p, level) < 0) continue
            // togli indizi superflui (prima i numeri, poi i segni) finché resta risolvibile
            for (i in (0 until n * n).shuffled(rnd)) {
                val r = i / n; val c = i % n
                if (givens[r][c] != 0) { val k = givens[r][c]; givens[r][c] = 0; if (FutoshikiLogic.solve(p, level) < 0) givens[r][c] = k }
            }
            for (i in (0 until n * n).shuffled(rnd)) {
                val r = i / n; val c = i % n
                if (right[r][c] != 0) { val k = right[r][c]; right[r][c] = 0; if (FutoshikiLogic.solve(p, level) < 0) right[r][c] = k }
                if (down[r][c] != 0) { val k = down[r][c]; down[r][c] = 0; if (FutoshikiLogic.solve(p, level) < 0) down[r][c] = k }
            }
            val used = FutoshikiLogic.solve(p, level)
            if (used == level || level == 0) return p
            if (fallback == null) fallback = p
            if (System.nanoTime() > deadline) return fallback
        }
    }

    fun solved(p: FutoshikiP, g: Array<IntArray>): Boolean =
        Grids.latinOk(g, p.n) && signErrors(p, g).isEmpty()

    fun signErrors(p: FutoshikiP, g: Array<IntArray>): Set<Pair<Int, Int>> {
        val bad = HashSet<Pair<Int, Int>>()
        for (r in 0 until p.n) for (c in 0 until p.n) {
            if (c + 1 < p.n && p.right[r][c] != 0 && g[r][c] != 0 && g[r][c + 1] != 0) {
                val ok = if (p.right[r][c] < 0) g[r][c] < g[r][c + 1] else g[r][c] > g[r][c + 1]
                if (!ok) { bad += r to c; bad += r to c + 1 }
            }
            if (r + 1 < p.n && p.down[r][c] != 0 && g[r][c] != 0 && g[r + 1][c] != 0) {
                val ok = if (p.down[r][c] < 0) g[r][c] < g[r + 1][c] else g[r][c] > g[r + 1][c]
                if (!ok) { bad += r to c; bad += r + 1 to c }
            }
        }
        return bad
    }
}

object FutoshikiLogic {
    fun solve(p: FutoshikiP, maxLevel: Int): Int = run(p, maxLevel).first

    fun candidates(p: FutoshikiP, maxLevel: Int): Array<IntArray>? = run(p, maxLevel).second

    private fun run(p: FutoshikiP, maxLevel: Int): Pair<Int, Array<IntArray>?> {
        val n = p.n
        val all = (1 shl n) - 1
        val cand = Array(n) { r -> IntArray(n) { c -> if (p.givens[r][c] != 0) b(p.givens[r][c]) else all } }
        val units = ArrayList<List<Int>>()
        for (r in 0 until n) units += (0 until n).map { r * n + it }
        for (c in 0 until n) units += (0 until n).map { it * n + c }
        val g = Array(n) { IntArray(n) }
        var used = 0
        var guard = 0
        while (guard++ < 400) {
            var progress = false
            // disuguaglianze: a < b ⇒ a sotto il massimo di b, b sopra il minimo di a
            fun lt(r1: Int, c1: Int, r2: Int, c2: Int) {
                val a = cand[r1][c1]; val bb = cand[r2][c2]
                if (a == 0 || bb == 0) return
                val maxB = maxOf(bb); val minA = minOf(a)
                var na = a; var nb = bb
                for (v in 1..n) { if (v >= maxB) na = na and b(v).inv(); if (v <= minA) nb = nb and b(v).inv() }
                if (na != a) { cand[r1][c1] = na; progress = true }
                if (nb != bb) { cand[r2][c2] = nb; progress = true }
            }
            for (r in 0 until n) for (c in 0 until n) {
                if (c + 1 < n) when (p.right[r][c]) { -1 -> lt(r, c, r, c + 1); 1 -> lt(r, c + 1, r, c) }
                if (r + 1 < n) when (p.down[r][c]) { -1 -> lt(r, c, r + 1, c); 1 -> lt(r + 1, c, r, c) }
            }
            for (u in units) {
                for (i in u) {
                    val m = cand[i / n][i % n]
                    if (one(m)) for (o in u) if (o != i && cand[o / n][o % n] and m != 0) { cand[o / n][o % n] = cand[o / n][o % n] and m.inv(); progress = true }
                }
                for (v in 1..n) {
                    val where = u.filter { cand[it / n][it % n] and b(v) != 0 }
                    if (where.size == 1 && cand[where[0] / n][where[0] % n] != b(v)) { cand[where[0] / n][where[0] % n] = b(v); progress = true }
                }
            }
            if ((0 until n * n).any { cand[it / n][it % n] == 0 }) return -1 to null
            if ((0 until n * n).all { one(cand[it / n][it % n]) }) {
                val sol = Array(n) { r -> IntArray(n) { c -> valOf(cand[r][c]) } }
                return (if (Futoshiki.solved(p, sol)) used else -1) to cand
            }
            if (progress) continue
            if (maxLevel >= 1 && SudokuLogic.nakedPairs(n, units, g, cand)) { used = kotlin.math.max(used, 1); continue }
            if (maxLevel >= 2 && SudokuLogic.hiddenPairs(n, units, g, cand)) { used = 2; continue }
            return -1 to cand
        }
        return -1 to cand
    }
}

// ================================================================== HITORI

/** grid = numeri; black = soluzione (caselle da annerire). */
class HitoriP(val n: Int, val grid: Array<IntArray>, val black: Array<BooleanArray>)

object Hitori {
    /**
     * Facile 5×5, Medio 6×6, Difficile 7×7. Tutti risolvibili con la logica;
     * nel Difficile deve servire il ragionamento sul collegamento delle bianche.
     */
    fun generate(level: Int, rnd: Random): HitoriP {
        val n = 5 + level
        val deadline = System.nanoTime() + 1_500_000_000L
        var fallback: HitoriP? = null
        while (true) {
            val sol = Latin.generate(n, rnd)
            val black = Array(n) { BooleanArray(n) }
            val target = (n * n * 0.33).toInt()
            var count = 0
            for (i in (0 until n * n).shuffled(rnd)) {
                if (count >= target) break
                val r = i / n; val c = i % n
                if (neighbors(n, r, c).any { (a, bb) -> black[a][bb] }) continue
                black[r][c] = true
                if (!whitesConnected(n, black)) black[r][c] = false else count++
            }
            // ogni casella nera prende il numero di una bianca della sua riga o colonna
            val grid = Array(n) { sol[it].clone() }
            for (r in 0 until n) for (c in 0 until n) if (black[r][c]) {
                val opts = (0 until n).filter { !black[r][it] }.map { grid[r][it] } +
                    (0 until n).filter { !black[it][c] }.map { grid[it][c] }
                grid[r][c] = opts[rnd.nextInt(opts.size)]
            }
            val p = HitoriP(n, grid, black)
            val used = HitoriLogic.solve(p, 2)
            if (used < 0) continue
            if (level < 2 || used == 2) return p
            if (fallback == null) fallback = p
            if (System.nanoTime() > deadline) return fallback
        }
    }

    fun neighbors(n: Int, r: Int, c: Int) = listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1)
        .filter { (a, bb) -> a in 0 until n && bb in 0 until n }

    fun whitesConnected(n: Int, black: Array<BooleanArray>): Boolean {
        val start = (0 until n * n).firstOrNull { !black[it / n][it % n] } ?: return false
        val seen = BooleanArray(n * n); val q = ArrayDeque<Int>(); q += start; seen[start] = true
        var cnt = 1
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            for ((a, bb) in neighbors(n, i / n, i % n)) {
                val j = a * n + bb
                if (!black[a][bb] && !seen[j]) { seen[j] = true; cnt++; q += j }
            }
        }
        return cnt == (0 until n * n).count { !black[it / n][it % n] }
    }

    /** Controllo per regole: nessun doppione bianco, nere non adiacenti, bianche collegate. */
    fun solved(p: HitoriP, black: Array<BooleanArray>): Boolean {
        val n = p.n
        for (r in 0 until n) for (c in 0 until n) if (black[r][c] && neighbors(n, r, c).any { (a, bb) -> black[a][bb] }) return false
        for (i in 0 until n) {
            val row = (0 until n).filter { !black[i][it] }.map { p.grid[i][it] }
            val col = (0 until n).filter { !black[it][i] }.map { p.grid[it][i] }
            if (row.size != row.toSet().size || col.size != col.toSet().size) return false
        }
        return whitesConnected(n, black)
    }
}

object HitoriLogic {
    /**
     * Livelli: 0 = doppioni accanto a un bianco certo + vicini di un nero + numeri unici;
     * 1 = + schemi "a b a" e coppie "a a"; 2 = + collegamento delle bianche.
     */
    fun solve(p: HitoriP, maxLevel: Int): Int {
        val n = p.n
        val st = Array(n) { IntArray(n) } // 0 ?, 1 bianca, 2 nera
        var used = 0
        fun set(r: Int, c: Int, v: Int): Boolean {
            if (st[r][c] == v) return false
            if (st[r][c] != 0) throw IllegalStateException()
            st[r][c] = v; return true
        }
        try {
            // numeri che non si ripetono né in riga né in colonna: bianchi
            for (r in 0 until n) for (c in 0 until n) {
                val v = p.grid[r][c]
                if ((0 until n).count { p.grid[r][it] == v } == 1 && (0 until n).count { p.grid[it][c] == v } == 1) set(r, c, 1)
            }
            var guard = 0
            while (guard++ < 300) {
                var progress = false
                for (r in 0 until n) for (c in 0 until n) {
                    if (st[r][c] == 2) for ((a, bb) in Hitori.neighbors(n, r, c)) if (set(a, bb, 1)) progress = true
                    if (st[r][c] == 1) {
                        val v = p.grid[r][c]
                        for (k in 0 until n) {
                            if (k != c && p.grid[r][k] == v && set(r, k, 2)) progress = true
                            if (k != r && p.grid[k][c] == v && set(k, c, 2)) progress = true
                        }
                    }
                }
                if (progress) continue
                if (maxLevel >= 1) {
                    for (r in 0 until n) for (c in 0 until n) for (dir in 0..1) {
                        val (r1, c1) = if (dir == 0) r to c + 1 else r + 1 to c
                        val (r2, c2) = if (dir == 0) r to c + 2 else r + 2 to c
                        // "a b a": quella in mezzo è bianca
                        if (r2 < n && c2 < n && p.grid[r][c] == p.grid[r2][c2] && st[r1][c1] == 0) { set(r1, c1, 1); progress = true; used = kotlin.math.max(used, 1) }
                        // "a a": una delle due è nera ⇒ le altre "a" della stessa linea sono nere
                        if (r1 < n && c1 < n && p.grid[r][c] == p.grid[r1][c1]) {
                            val v = p.grid[r][c]
                            for (k in 0 until n) {
                                val (rr, cc) = if (dir == 0) r to k else k to c
                                if ((rr to cc) != (r to c) && (rr to cc) != (r1 to c1) && p.grid[rr][cc] == v && st[rr][cc] == 0) {
                                    set(rr, cc, 2); progress = true; used = kotlin.math.max(used, 1)
                                }
                            }
                        }
                    }
                    if (progress) continue
                }
                if (maxLevel >= 2) {
                    // una casella che, se nera, staccherebbe le bianche: è bianca
                    for (r in 0 until n) for (c in 0 until n) if (st[r][c] == 0) {
                        val test = Array(n) { rr -> BooleanArray(n) { cc -> st[rr][cc] == 2 } }
                        test[r][c] = true
                        if (!Hitori.whitesConnected(n, test)) { set(r, c, 1); progress = true; used = 2 }
                    }
                    if (progress) continue
                }
                break
            }
        } catch (_: IllegalStateException) {
            return -1
        }
        if (st.any { row -> row.any { it == 0 } }) return -1
        val black = Array(n) { r -> BooleanArray(n) { c -> st[r][c] == 2 } }
        return if (Hitori.solved(p, black)) used else -1
    }
}

// ================================================================== BINARIO

/** givens: -1 vuota, 0 o 1 data. */
class BinarioP(val n: Int, val givens: Array<IntArray>, val solution: Array<IntArray>)

object Binario {
    fun generate(level: Int, rnd: Random): BinarioP {
        val n = when (level) { 0 -> 6; 1 -> 8; else -> 10 }
        while (true) {
            val sol = Array(n) { IntArray(n) { -1 } }
            if (!fill(sol, n, 0, rnd) || !linesUnique(sol, n)) continue
            val g = Array(n) { sol[it].clone() }
            for (i in (0 until n * n).shuffled(rnd)) {
                val r = i / n; val c = i % n
                val k = g[r][c]; g[r][c] = -1
                if (BinarioLogic.solve(BinarioP(n, g, sol), level) < 0) g[r][c] = k
            }
            val p = BinarioP(n, g, sol)
            if (BinarioLogic.solve(p, level) >= 0) return p
        }
    }

    private fun fill(g: Array<IntArray>, n: Int, i: Int, rnd: Random): Boolean {
        if (i == n * n) return true
        val r = i / n; val c = i % n
        for (v in listOf(0, 1).shuffled(rnd)) {
            g[r][c] = v
            if (partialOk(g, n, r, c) && fill(g, n, i + 1, rnd)) return true
        }
        g[r][c] = -1
        return false
    }

    private fun partialOk(g: Array<IntArray>, n: Int, r: Int, c: Int): Boolean {
        val v = g[r][c]
        if (c >= 2 && g[r][c - 1] == v && g[r][c - 2] == v) return false
        if (r >= 2 && g[r - 1][c] == v && g[r - 2][c] == v) return false
        if ((0..c).count { g[r][it] == v } > n / 2) return false
        if ((0..r).count { g[it][c] == v } > n / 2) return false
        if (c == n - 1) for (rr in 0 until r) if (g[rr].contentEquals(g[r])) return false
        return true
    }

    private fun linesUnique(g: Array<IntArray>, n: Int): Boolean {
        val cols = (0 until n).map { c -> (0 until n).map { g[it][c] } }
        return cols.toSet().size == n && g.map { it.toList() }.toSet().size == n
    }

    fun solved(p: BinarioP, g: Array<IntArray>): Boolean = errors(p, g).isEmpty() && g.all { row -> row.all { it >= 0 } } &&
        linesUnique(g, p.n)

    /** Caselle che violano una regola (tre uguali, troppi di un tipo). */
    fun errors(p: BinarioP, g: Array<IntArray>): Set<Pair<Int, Int>> {
        val n = p.n
        val bad = HashSet<Pair<Int, Int>>()
        for (r in 0 until n) for (c in 0 until n) {
            val v = g[r][c]; if (v < 0) continue
            if (c + 2 < n && g[r][c + 1] == v && g[r][c + 2] == v) { bad += r to c; bad += r to c + 1; bad += r to c + 2 }
            if (r + 2 < n && g[r + 1][c] == v && g[r + 2][c] == v) { bad += r to c; bad += r + 1 to c; bad += r + 2 to c }
        }
        for (i in 0 until n) for (v in 0..1) {
            if ((0 until n).count { g[i][it] == v } > n / 2) (0 until n).filter { g[i][it] == v }.forEach { bad += i to it }
            if ((0 until n).count { g[it][i] == v } > n / 2) (0 until n).filter { g[it][i] == v }.forEach { bad += it to i }
        }
        return bad
    }
}

object BinarioLogic {
    /**
     * 0 = coppie e "x _ x" + conteggi pieni; 1 = + tutte le combinazioni possibili della riga;
     * 2 = + righe/colonne tutte diverse.
     */
    fun solve(p: BinarioP, maxLevel: Int): Int {
        val n = p.n
        val g = Array(n) { p.givens[it].clone() }
        var used = 0
        var guard = 0
        fun line(i: Int, dir: Int) = (0 until n).map { k -> if (dir == 0) i to k else k to i }
        while (guard++ < 500) {
            var progress = false
            // livello 0
            for (dir in 0..1) for (i in 0 until n) {
                val cells = line(i, dir)
                val vals = cells.map { (r, c) -> g[r][c] }
                for (k in 0 until n) {
                    if (vals[k] != -1) continue
                    val opp = { v: Int -> 1 - v }
                    var force = -1
                    if (k >= 2 && vals[k - 1] != -1 && vals[k - 1] == vals[k - 2]) force = opp(vals[k - 1])
                    if (k + 2 < n && vals[k + 1] != -1 && vals[k + 1] == vals[k + 2]) force = opp(vals[k + 1])
                    if (k >= 1 && k + 1 < n && vals[k - 1] != -1 && vals[k - 1] == vals[k + 1]) force = opp(vals[k - 1])
                    if (force >= 0) { val (r, c) = cells[k]; g[r][c] = force; progress = true }
                }
                for (v in 0..1) if (cells.count { (r, c) -> g[r][c] == v } == n / 2) {
                    for ((r, c) in cells) if (g[r][c] == -1) { g[r][c] = 1 - v; progress = true }
                }
            }
            if (progress) continue
            if (maxLevel >= 1) {
                val done: Pair<Set<List<Int>>, Set<List<Int>>> =
                    if (maxLevel >= 2) completeLines(g, n) else Pair(emptySet(), emptySet())
                for (dir in 0..1) for (i in 0 until n) {
                    val cells = line(i, dir)
                    if (cells.none { (r, c) -> g[r][c] == -1 }) continue
                    var andM = -1; var orM = 0; var any = false
                    var excludedByUnique = false
                    for (m in 0 until (1 shl n)) {
                        if (Integer.bitCount(m) != n / 2) continue
                        var ok = true
                        for (k in 0 until n) {
                            val bitV = (m shr k) and 1
                            val (r, c) = cells[k]
                            if (g[r][c] != -1 && g[r][c] != bitV) { ok = false; break }
                            if (k >= 2 && ((m shr (k - 1)) and 1) == bitV && ((m shr (k - 2)) and 1) == bitV) { ok = false; break }
                        }
                        if (!ok) continue
                        if (maxLevel >= 2) {
                            val asList = (0 until n).map { (m shr it) and 1 }
                            if (asList in (if (dir == 0) done.first else done.second)) { excludedByUnique = true; continue }
                        }
                        any = true; andM = andM and m; orM = orM or m
                    }
                    if (!any) return -1
                    for (k in 0 until n) {
                        val (r, c) = cells[k]
                        if (g[r][c] != -1) continue
                        if ((andM shr k) and 1 == 1) { g[r][c] = 1; progress = true }
                        else if ((orM shr k) and 1 == 0) { g[r][c] = 0; progress = true }
                    }
                    if (progress) { used = kotlin.math.max(used, if (excludedByUnique) 2 else 1); break }
                }
                if (progress) continue
            }
            break
        }
        if (g.any { row -> row.any { it == -1 } }) return -1
        return if (Binario.solved(p, g)) used else -1
    }

    private fun completeLines(g: Array<IntArray>, n: Int): Pair<Set<List<Int>>, Set<List<Int>>> {
        val rows = g.filter { row -> row.all { it >= 0 } }.map { it.toList() }.toSet()
        val cols = (0 until n).map { c -> (0 until n).map { g[it][c] } }.filter { col -> col.all { it >= 0 } }.toSet()
        return rows to cols
    }
}
