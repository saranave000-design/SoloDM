package com.saranave.solodm.games

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.saranave.solodm.Prefs
import java.util.concurrent.Executors
import kotlin.random.Random

object GameList {
    val all: List<Game> = listOf(
        Game24, GameTarget, GameEquation, GameMental, GameSequences,
        GameSumplete, GameKenKen, GameSudoku, GameKakuro
    )
}

/**
 * Pannello con tendina dei giochi, livello e "Nuova".
 * Vive dentro una maschera: la partita resta finché non ne chiedi una nuova.
 */
@SuppressLint("ViewConstructor")
class GamePanel(ctx: Context, theme: GameTheme) : FrameLayout(ctx) {
    private var k = Kit(ctx, theme)
    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var started = false

    private val scroll = ScrollView(ctx)
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(ctx)

    private var listOpen = false
    private var gameIndex = Prefs.sp(ctx).getInt("game_index", 0).coerceIn(0, GameList.all.size - 1)
    private var level = Prefs.sp(ctx).getInt("game_level", 0).coerceIn(0, 2)

    init {
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        col.setPadding(k.dpi(14), k.dpi(10), k.dpi(14), k.dpi(24))
        col.addView(header)
        col.addView(content)
        scroll.addView(col)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Da chiamare quando la maschera diventa visibile: crea la prima partita solo allora. */
    fun ensureStarted() {
        if (started) return
        started = true
        renderHeader()
        newGame()
    }

    fun setTheme(t: GameTheme) {
        if (t.bg == k.t.bg && t.fg == k.t.fg) return
        k = Kit(context, t)
        if (started) { renderHeader(); newGame() }
    }

    private fun save() {
        Prefs.sp(context).edit().putInt("game_index", gameIndex).putInt("game_level", level).apply()
    }

    private fun renderHeader() {
        header.removeAllViews()
        val top = k.row()
        val pick = k.button("▾  ${GameList.all[gameIndex].title}", 16f) { listOpen = !listOpen; renderHeader() }
        top.addView(pick, k.lp(0, weight = 1f))
        top.addView(k.button("↻ Nuova", 15f) { newGame() }, k.lp())
        header.addView(top, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 2))

        if (listOpen) {
            for ((i, g) in GameList.all.withIndex()) {
                val b = k.button(g.title, 15f, filled = i == gameIndex) {
                    gameIndex = i; listOpen = false; save(); renderHeader(); newGame()
                }
                header.addView(b, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 2))
            }
        }

        val levels = k.row()
        for ((i, name) in listOf("Facile", "Medio", "Difficile").withIndex()) {
            val b = k.button(name, 14f, filled = i == level) {
                if (level != i) { level = i; save(); renderHeader(); newGame() }
            }
            levels.addView(b, k.lp(0, weight = 1f, margin = 3))
        }
        header.addView(levels, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 2))
    }

    private fun newGame() {
        val game = GameList.all[gameIndex]
        val lv = level
        val my = ++token
        content.removeAllViews()
        content.addView(k.text("…", 22f, color = k.t.dim), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, k.dpi(120)
        ))
        worker.execute {
            val data = try {
                game.generate(lv, Random(System.nanoTime()))
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (my != token) return@post
                content.removeAllViews()
                if (data == null) {
                    content.addView(k.text("Qualcosa è andato storto: tocca ↻ Nuova", 14f, color = k.t.dim))
                    return@post
                }
                val v = try {
                    game.build(k, data, lv) {}
                } catch (_: Throwable) {
                    k.text("Qualcosa è andato storto: tocca ↻ Nuova", 14f, color = k.t.dim)
                }
                content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                scroll.scrollTo(0, 0)
            }
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }
}
