package com.saranave.solodm

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro di diagnosi. Salva SOLO nomi tecnici delle schermate e
 * identificativi dei pulsanti di Instagram: mai testi, messaggi o nomi utente.
 * Serve per ricalibrare il blocco quando Instagram si aggiorna.
 */
object DiagLog {
    private const val FILE = "diagnosi.txt"
    private const val MAX_BYTES = 180_000L
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY)

    private fun file(c: Context) = File(c.filesDir, FILE)

    @Synchronized
    fun add(c: Context, line: String) {
        try {
            val f = file(c)
            f.appendText("[${fmt.format(Date())}] $line\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
        } catch (_: Exception) {
        }
    }

    fun read(c: Context): String {
        val header = buildString {
            append("Solo DM ").append(BuildConfigLite.versionName(c)).append('\n')
            append("Instagram ").append(igVersion(c)).append('\n')
            append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        }
        val body = try {
            file(c).takeIf { it.exists() }?.readText().orEmpty()
        } catch (_: Exception) {
            ""
        }
        return header + body.ifEmpty { "(registro vuoto: attiva la diagnosi e usa Instagram)" }
    }

    fun clear(c: Context) {
        try {
            file(c).delete()
        } catch (_: Exception) {
        }
    }

    fun igVersion(c: Context): String = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(IgUi.PKG, 0).versionName ?: "?"
    } catch (_: Exception) {
        "non installato"
    }
}

object BuildConfigLite {
    fun versionName(c: Context): String = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
