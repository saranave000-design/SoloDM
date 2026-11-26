package com.saranave.solodm.games

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.View
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
        GameSumplete, GameKenKen, GameSudoku, GameKakuro,
        GameFutoshiki, GameHitori, GameBinario, GameR
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

    /** Se impostato, compare il tasto 🔇 (toglie l'audio al video di Instagram sotto la maschera). */
    var onMute: (() -> Unit)? = null
        set(v) { field = v; if (started) renderHeader() }

    /** Tendina dei giochi: si apre SOPRA la schermata, senza spostare niente. */
    private val dropLayer = FrameLayout(ctx).apply { visibility = View.GONE }
    private var gameIndex = Prefs.sp(ctx).getInt("game_index", 0).coerceIn(0, GameList.all.size - 1)
    private var level = Prefs.sp(ctx).getInt("game_level", 0).coerceIn(0, 2)

    init {
        GameR.appCtx = ctx.applicationContext
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        col.setPadding(k.dpi(14), k.dpi(10), k.dpi(14), k.dpi(24))
        col.addView(header)
        col.addView(content)
        scroll.addView(col)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(dropLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closeDrop() { dropLayer.visibility = View.GONE; dropLayer.removeAllViews() }

    private fun openDrop(anchor: View) {
        if (dropLayer.visibility == View.VISIBLE) { closeDrop(); return }
        dropLayer.removeAllViews()
        // tocco fuori dalla lista = chiudi
        dropLayer.addView(View(context).apply { isClickable = true; setOnClickListener { closeDrop() } },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val a = IntArray(2); anchor.getLocationInWindow(a)
        val me = IntArray(2); getLocationInWindow(me)
        val top = (a[1] - me[1] + anchor.height + k.dpi(2)).coerceAtLeast(0)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dpi(4), k.dpi(4), k.dpi(4), k.dpi(4)) }
        for ((i, g) in GameList.all.withIndex()) {
            list.addView(k.button(g.title, 15f, filled = i == gameIndex) {
                closeDrop()
                if (i != gameIndex) { gameIndex = i; save(); renderHeader(); newGame() }
            }, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 2))
        }
        val box = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            background = GradientDrawable().apply { cornerRadius = k.dp(10f); setColor(k.t.bg); setStroke(k.dpi(1).coerceAtLeast(1), k.t.fg) }
            elevation = k.dp(8f)
            addView(list)
        }
        val maxH = (height - top - k.dpi(8)).coerceAtLeast(k.dpi(160))
        list.measure(MeasureSpec.makeMeasureSpec(anchor.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = minOf(list.measuredHeight, maxH)
        dropLayer.addView(box, LayoutParams(anchor.width.coerceAtLeast(k.dpi(200)), h).apply {
            topMargin = top; leftMargin = (a[0] - me[0]).coerceAtLeast(0)
        })
        dropLayer.visibility = View.VISIBLE
    }

    /** Da chiamare quando la maschera diventa visibile: crea la prima partita solo allora. */
    fun ensureStarted() {
        if (started) return
        started = true
        renderHeader()
        newGame()
    }

    /**
     * Cambio di colore: si aggiorna solo l'intestazione. La partita in corso NON si tocca
     * (prima ricominciava da capo: era un errore); il nuovo colore vale dalla prossima partita.
     */
    fun setTheme(t: GameTheme) {
        if (t.bg == k.t.bg && t.fg == k.t.fg) return
        k = Kit(context, t)
        if (started) renderHeader()
    }

    private fun save() {
        Prefs.sp(context).edit().putInt("game_index", gameIndex).putInt("game_level", level).apply()
    }

    private fun renderHeader() {
        header.removeAllViews()
        val top = k.row()
        lateinit var pick: View
        pick = k.button("▾  ${GameList.all[gameIndex].title}", 16f) { openDrop(pick) }
        top.addView(pick, k.lp(0, weight = 1f))
        top.addView(k.button("↻ Nuova", 15f) { newGame() }, k.lp())
        onMute?.let { m -> top.addView(k.button("🔇", 17f) { m() }, k.lp()) }
        header.addView(top, k.lp(ViewGroup.LayoutParams.MATCH_PARENT, margin = 2))

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
