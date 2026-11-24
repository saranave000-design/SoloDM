package com.saranave.solodm.games

import android.view.View
import android.view.ViewGroup
import kotlin.random.Random

object GameFutoshiki : Game {
    override val title = "Futoshiki"
    override fun generate(level: Int, rnd: Random): Any = Futoshiki.generate(level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as FutoshikiP
        val n = p.n
        val g = Array(n) { p.givens[it].clone() }
        val board = Board(k.ctx, k.t, n, n)
        board.maxCellDp = 56f
        board.text = { r, c -> if (g[r][c] == 0) "" else "${g[r][c]}" }
        board.rightSign = { r, c -> if (c + 1 < n) when (p.right[r][c]) { -1 -> "<"; 1 -> ">"; else -> null } else null }
        board.bottomSign = { r, c -> if (r + 1 < n) when (p.down[r][c]) { -1 -> "∧"; 1 -> "∨"; else -> null } else null }
        board.style = { r, c ->
            when {
                p.givens[r][c] != 0 -> Board.Style.GIVEN
                g[r][c] != 0 && ((r to c) in Grids.conflicts(g, n, 0, 0) || (r to c) in Futoshiki.signErrors(p, g)) -> Board.Style.ERROR
                else -> Board.Style.NORMAL
            }
        }
        val col = k.column()
        col.addView(k.text("Numeri da 1 a $n, mai ripetuti in riga o colonna.\nRispetta i segni < > tra le caselle (∧ ∨ in verticale).", 13f, color = k.t.dim), k.lp())
        val msg = k.text("", 15f)
        var done = false
        board.onTap = { r, c -> if (!done && p.givens[r][c] == 0) { board.selected = r to c; board.invalidate() } }
        col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(msg, k.lp())
        col.addView(k.numberPad(n) { key ->
            val sel = board.selected ?: return@numberPad
            if (done) return@numberPad
            g[sel.first][sel.second] = if (key == "⌫") 0 else key.toInt()
            if (g.all { row -> row.all { it != 0 } } && Futoshiki.solved(p, g)) { done = true; board.selected = null; msg.text = "✓ Risolto!"; onWin() }
            board.invalidate()
        }, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        return col
    }
}

object GameHitori : Game {
    override val title = "Hitori"
    override fun generate(level: Int, rnd: Random): Any = Hitori.generate(level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as HitoriP
        val n = p.n
        // 0 = normale, 1 = annerita, 2 = cerchiata (sicuramente bianca)
        val st = Array(n) { IntArray(n) }
        var done = false
        val board = Board(k.ctx, k.t, n, n)
        board.maxCellDp = 56f
        board.text = { r, c -> "${p.grid[r][c]}" }
        board.style = { r, c -> when (st[r][c]) { 1 -> Board.Style.FILLED; 2 -> Board.Style.CIRCLED; else -> Board.Style.NORMAL } }
        val col = k.column()
        col.addView(k.text("Annerisci i numeri ripetuti: in ogni riga e colonna\nnessun numero bianco doppio. Le caselle nere non si toccano\ne le bianche restano tutte collegate. Tocca: nero → cerchio → vuoto.", 13f, color = k.t.dim), k.lp())
        val msg = k.text("", 15f)
        board.onTap = { r, c ->
            if (!done) {
                st[r][c] = (st[r][c] + 1) % 3
                val black = Array(n) { rr -> BooleanArray(n) { cc -> st[rr][cc] == 1 } }
                if (Hitori.solved(p, black)) { done = true; msg.text = "✓ Risolto!"; onWin() }
                board.invalidate()
            }
        }
        col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(msg, k.lp())
        return col
    }
}

object GameBinario : Game {
    override val title = "Binario"
    override fun generate(level: Int, rnd: Random): Any = Binario.generate(level, rnd)

    override fun build(k: Kit, data: Any, level: Int, onWin: () -> Unit): View {
        val p = data as BinarioP
        val n = p.n
        val g = Array(n) { p.givens[it].clone() }
        var done = false
        val board = Board(k.ctx, k.t, n, n)
        board.maxCellDp = 46f
        board.text = { r, c -> if (g[r][c] < 0) "" else "${g[r][c]}" }
        board.style = { r, c ->
            when {
                p.givens[r][c] >= 0 -> Board.Style.GIVEN
                g[r][c] >= 0 && (r to c) in Binario.errors(p, g) -> Board.Style.ERROR
                else -> Board.Style.NORMAL
            }
        }
        val col = k.column()
        col.addView(k.text("Riempi con 0 e 1: mai tre uguali di fila, tanti 0 quanti 1\nin ogni riga e colonna, nessuna riga o colonna uguale a un'altra.\nTocca: 0 → 1 → vuoto.", 13f, color = k.t.dim), k.lp())
        val msg = k.text("", 15f)
        board.onTap = { r, c ->
            if (!done && p.givens[r][c] < 0) {
                g[r][c] = when (g[r][c]) { -1 -> 0; 0 -> 1; else -> -1 }
                if (Binario.solved(p, g)) { done = true; msg.text = "✓ Risolto!"; onWin() }
                board.invalidate()
            }
        }
        col.addView(board, k.lp(ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(msg, k.lp())
        return col
    }
}
