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

    // ---- anti-lampo (interruttore in "Altro", acceso di serie)
    fun antiFlash(c: Context): Boolean = sp(c).getBoolean("anti_flash", true)
    fun setAntiFlash(c: Context, on: Boolean) { sp(c).edit().putBoolean("anti_flash", on).apply() }

    // ---- colore bloccato: uno per il tema chiaro e uno per lo scuro
    private fun themeKey(dark: Boolean) = if (dark) "dark" else "light"
    fun lockedColor(c: Context, dark: Boolean): Int? =
        if (sp(c).contains("color_lock_${themeKey(dark)}")) sp(c).getInt("color_lock_${themeKey(dark)}", 0) else null

    /** Registra un campione; dopo 3 campioni uguali di fila il colore viene bloccato. Restituisce true se ora è bloccato. */
    fun offerColor(c: Context, dark: Boolean, color: Int): Boolean {
        val k = themeKey(dark)
        val sp = sp(c)
        val cand = if (sp.contains("color_cand_$k")) sp.getInt("color_cand_$k", 0) else null
        val count = if (cand == color) sp.getInt("color_count_$k", 0) + 1 else 1
        val e = sp.edit().putInt("color_cand_$k", color).putInt("color_count_$k", count)
        if (count >= 3) e.putInt("color_lock_$k", color)
        e.apply()
        return count >= 3
    }

    fun resetColors(c: Context) {
        val e = sp(c).edit()
        for (k in listOf("dark", "light")) { e.remove("color_lock_$k"); e.remove("color_cand_$k"); e.remove("color_count_$k") }
        e.remove("bg_color").apply()
    }

    fun diagEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_DIAG, false)

    fun setDiag(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_DIAG, on).apply()
    }
}
