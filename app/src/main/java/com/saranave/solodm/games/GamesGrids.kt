package com.saranave.solodm.games

import android.view.View
import android.view.ViewGroup
import kotlin.random.Random

// ------------------------------------------------------------------ Sumplete

object GameSumplete : Game {
    override val title = "Sumplete"
    override fun generate(level: Int, rnd: Random): Any = Sumplete.generate(4 + level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as SumpleteP
        val n = p.n
        // 0 = da decidere, 1 = tolto (X), 2 = tenuto (cerchio)
        val state = Array(n) { IntArray(n) }
        var done = false
        val col = k.column()
        col.addView(k.text("Togli i numeri (X) finché ogni riga e colonna\ndà la somma indicata. Tocca di nuovo per cerchiare.", 13f, color = k.t.dim), k.lp())
        val board = Board(k.ctx, k.t, n, n)
        fun crossed() = Array(n) { r -> BooleanArray(n) { c -> state[r][c] == 1 } }
        board.text = { r, c -> "${p.grid[r][c]}" }
        board.style = { r, c -> when (state[r][c]) { 1 -> Board.Style.CROSSED; 2 -> Board.Style.CIRCLED; else -> Board.Style.NORMAL } }
        board.rightLabels = { r -> "${p.rowSums[r]}" to Sumplete.rowOk(p, crossed(), r) }
        board.bottomLabels = { c -> "${p.colSums[c]}" to Sumplete.colOk(p, crossed(), c) }
        val msg = k.text("", 15f)
        board.onTap = { r, c ->
            if (!done) {
                state[r][c] = (state[r][c] + 1) % 3
                if (Sumplete.solved(p, crossed())) { done = true; msg.text = "✓ Risolto!"; onWin() }
                board.invalidate()
            }
        }
        col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(msg, k.lp())
        return col
    }
}

// ------------------------------------------------------------------ griglie con numeri da inserire

/** Base per KenKen, Sudoku e Kakuro: tocchi una casella, poi un numero. */
private fun numberGrid(
    k: Kit,
    board: Board,
    maxNum: Int,
    editable: (Int, Int) -> Boolean,
    values: Array<IntArray>,
    check: () -> Boolean,
    onWin: () -> Unit,
    hint: String
): View {
    val col = k.column()
    col.addView(k.text(hint, 13f, color = k.t.dim), k.lp())
    val msg = k.text("", 15f)
    var done = false
    board.onTap = { r, c ->
        if (!done && editable(r, c)) { board.selected = r to c; board.invalidate() }
    }
    col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
    col.addView(msg, k.lp())
    val pad = k.numberPad(maxNum) { key ->
        val sel = board.selected ?: return@numberPad
        if (done) return@numberPad
        values[sel.first][sel.second] = if (key == "⌫") 0 else key.toInt()
        if (check()) { done = true; board.selected = null; msg.text = "✓ Risolto!"; onWin() }
        board.invalidate()
    }
    col.addView(pad, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
    return col
}

object GameKenKen : Game {
    override val title = "KenKen"
    override fun generate(level: Int, rnd: Random): Any = KenKen.generate(4 + level, level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as KenKenP
        val n = p.n
        val g = Array(n) { IntArray(n) }
        val board = Board(k.ctx, k.t, n, n)
        val firstCell = p.cages.map { cg -> cg.cells.minWith(compareBy({ it.first }, { it.second })) }
        board.text = { r, c -> if (g[r][c] == 0) "" else "${g[r][c]}" }
        board.corner = { r, c ->
            val i = p.cageOf[r][c]
            if (firstCell[i] == (r to c)) p.cages[i].label.replace('-', '−') else null
        }
        board.thickRight = { r, c -> c + 1 < n && p.cageOf[r][c] != p.cageOf[r][c + 1] }
        board.thickBottom = { r, c -> r + 1 < n && p.cageOf[r][c] != p.cageOf[r + 1][c] }
        board.style = { r, c ->
            val bad = (r to c) in Grids.conflicts(g, n, 0, 0) ||
                KenKen.cageOk(p.cages[p.cageOf[r][c]], g) == false
            if (bad && g[r][c] != 0) Board.Style.ERROR else Board.Style.NORMAL
        }
        return numberGrid(
            k, board, n, { _, _ -> true }, g, { KenKen.solved(p, g) }, onWin,
            "Numeri da 1 a $n, mai ripetuti in riga o colonna.\nOgni gruppo deve dare il risultato indicato."
        )
    }
}

object GameSudoku : Game {
    override val title = "Sudoku"
    override fun generate(level: Int, rnd: Random): Any = Sudoku.generate(level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as SudokuP
        val n = p.n
        val g = Array(n) { p.givens[it].clone() }
        val board = Board(k.ctx, k.t, n, n)
        board.maxCellDp = if (n == 9) 40f else 56f
        board.text = { r, c -> if (g[r][c] == 0) "" else "${g[r][c]}" }
        board.thickRight = { _, c -> (c + 1) % p.boxC == 0 }
        board.thickBottom = { r, _ -> (r + 1) % p.boxR == 0 }
        board.style = { r, c ->
            when {
                p.givens[r][c] != 0 -> Board.Style.GIVEN
                g[r][c] != 0 && (r to c) in Grids.conflicts(g, n, p.boxR, p.boxC) -> Board.Style.ERROR
                else -> Board.Style.NORMAL
            }
        }
        return numberGrid(
            k, board, n, { r, c -> p.givens[r][c] == 0 }, g, { Sudoku.solved(p, g) }, onWin,
            "Numeri da 1 a $n, mai ripetuti in riga, colonna o riquadro."
        )
    }
}

object GameKakuro : Game {
    override val title = "Kakuro"
    override fun generate(level: Int, rnd: Random): Any = Kakuro.generate(level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as KakuroP
        val g = Array(p.size) { IntArray(p.size) }
        val board = Board(k.ctx, k.t, p.size, p.size)
        board.maxCellDp = 52f
        board.text = { r, c -> if (p.white[r][c] && g[r][c] != 0) "${g[r][c]}" else "" }
        board.split = { r, c -> if (!p.white[r][c]) p.down[r][c] to p.across[r][c] else null }
        board.style = { r, c ->
            when {
                !p.white[r][c] -> Board.Style.BLOCK
                g[r][c] != 0 && (r to c) in Kakuro.errors(p, g) -> Board.Style.ERROR
                else -> Board.Style.NORMAL
            }
        }
        return numberGrid(
            k, board, 9, { r, c -> p.white[r][c] }, g, { Kakuro.solved(p, g) }, onWin,
            "Ogni gruppo di caselle deve dare la somma indicata\n(sotto la diagonale: verso il basso; sopra: verso destra).\nNumeri 1–9, mai ripetuti nello stesso gruppo."
        )
    }
}
