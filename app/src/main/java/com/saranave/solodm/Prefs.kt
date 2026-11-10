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

    fun diagEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_DIAG, false)

    fun setDiag(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_DIAG, on).apply()
    }
}
