package com.saranave.solodm

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val PAUSE_MINUTES = 10
    const val HOLD_SECONDS = 10

    private const val KEY_PAUSED_UNTIL = "paused_until"
    private const val KEY_DIAG = "diag_enabled"

    fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("solodm", Context.MODE_PRIVATE)

    fun pausedUntil(c: Context): Long = sp(c).getLong(KEY_PAUSED_UNTIL, 0L)

    fun isPaused(c: Context): Boolean = System.currentTimeMillis() < pausedUntil(c)

    fun startPause(c: Context) {
        val until = System.currentTimeMillis() + PAUSE_MINUTES * 60_000L
        sp(c).edit().putLong(KEY_PAUSED_UNTIL, until).apply()
    }

    fun endPause(c: Context) {
        sp(c).edit().putLong(KEY_PAUSED_UNTIL, 0L).apply()
    }

    // ---- memoria delle maschere (una per tipo di apertura)

    fun saveMemory(c: Context, mode: String, w: Int, h: Int, masks: Map<String, MaskManager.Mask>) {
        val body = masks.entries.sortedBy { it.key }.joinToString(";") { (k, m) ->
            "$k:${m.kind.name}:${m.rect.left},${m.rect.top},${m.rect.right},${m.rect.bottom}"
        }
        val v = "$w,$h|$body"
        if (sp(c).getString("mem_$mode", null) != v) sp(c).edit().putString("mem_$mode", v).apply()
    }

    /** null se non c'è memoria per questo tipo di apertura o se lo schermo è diverso (es. ruotato). */
    fun loadMemory(c: Context, mode: String, w: Int, h: Int): Map<String, MaskManager.Mask>? {
        val v = sp(c).getString("mem_$mode", null) ?: return null
        val (dims, body) = v.split('|', limit = 2).let { if (it.size == 2) it[0] to it[1] else return null }
        if (dims != "$w,$h") return null
        if (body.isEmpty()) return emptyMap()
        val out = HashMap<String, MaskManager.Mask>()
        for (part in body.split(';')) {
            val f = part.split(':')
            if (f.size != 3) continue
            val n = f[2].split(',').mapNotNull { it.toIntOrNull() }
            if (n.size != 4) continue
            val kind = try { MaskManager.Kind.valueOf(f[1]) } catch (_: Exception) { MaskManager.Kind.PLAIN }
            out[f[0]] = MaskManager.Mask(android.graphics.Rect(n[0], n[1], n[2], n[3]), kind)
        }
        return out
    }

    fun bgColor(c: Context): Int? = if (sp(c).contains("bg_color")) sp(c).getInt("bg_color", 0) else null

    fun setBgColor(c: Context, color: Int) {
        sp(c).edit().putInt("bg_color", color).apply()
    }

    fun diagEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_DIAG, false)

    fun setDiag(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_DIAG, on).apply()
    }
}
