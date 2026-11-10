package com.saranave.solodm

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import com.saranave.solodm.games.GamePanel
import com.saranave.solodm.games.GameTheme

/** I giochini anche fuori da Instagram (comodo per provarli). */
class GamesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val bg = if (ui.dark) Color.BLACK else Color.WHITE
        window.statusBarColor = bg
        val panel = GamePanel(this, GameTheme.forBackground(bg))
        panel.setBackgroundColor(bg)
        panel.setPadding(0, ui.dp(24), 0, 0)
        setContentView(panel)
        panel.ensureStarted()
    }
}
