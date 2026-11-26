package com.saranave.solodm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schermata "nascosta": pausa (tenendo premuto a lungo) e diagnosi.
 */
class AdvancedActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var pauseState: TextView
    private lateinit var holdBtn: Button
    private lateinit var endBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private var holdStart = 0L
    private val holdTick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - holdStart
            val left = Prefs.HOLD_SECONDS - (elapsed / 1000).toInt()
            if (left <= 0) {
                Prefs.startPause(this@AdvancedActivity)
                refresh()
                Toast.makeText(this@AdvancedActivity, "Pausa di ${Prefs.PAUSE_MINUTES} minuti", Toast.LENGTH_SHORT).show()
                holdStart = 0L
                return
            }
            holdBtn.text = "Continua a tenere premuto… $left"
            handler.postDelayed(this, 200)
        }
    }

    @SuppressLint("ClickableViewAccessibility", "UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (root, col) = ui.page()

        ui.title(col, "Altro")
        ui.spacer(col, 12)

        // ---- Pausa
        val c1 = ui.cardBox(col)
        ui.text(c1, "Pausa", 17f, bold = true)
        ui.text(
            c1,
            "Sblocca tutto per ${Prefs.PAUSE_MINUTES} minuti, poi si riattiva da sola. " +
                "Devi tenere premuto il pulsante per ${Prefs.HOLD_SECONDS} secondi.",
            14f, ui.muted
        )
        pauseState = ui.text(c1, "", 14f)
        holdBtn = ui.button(c1, "", primary = false) {}
        holdBtn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!Prefs.isPaused(this)) {
                        holdStart = SystemClock.uptimeMillis()
                        handler.post(holdTick)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdStart != 0L) {
                        handler.removeCallbacks(holdTick)
                        holdStart = 0L
                        refresh()
                    }
                    true
                }
                else -> true
            }
        }
        endBtn = ui.button(c1, "Termina la pausa adesso") {
            Prefs.endPause(this)
            refresh()
        }

        // ---- Anti-lampo e colore
        val c0 = ui.cardBox(col)
        ui.text(c0, "Anti-lampo", 17f, bold = true)
        ui.text(
            c0,
            "Copre home ed Esplora già all'apertura e al tocco della casetta o della lente, le scopre appena sei in chat, " +
                "e alleggerisce il lavoro del telefono. Il tocco su casetta e lente passa prima da Solo DM: " +
                "così la maschera è già al suo posto quando la pagina compare. " +
                "Se noti qualcosa di strano, spegnilo: torna tutto come nella versione 1.4.\n\n" +
                "App recenti: su Xiaomi usa Impostazioni → Schermata Home → Sfoca anteprime app e attivala per Instagram.",
            14f, ui.muted
        )
        val swAf = Switch(this)
        swAf.text = "Anti-lampo attivo"
        swAf.setTextColor(ui.fg)
        swAf.isChecked = Prefs.antiFlash(this)
        swAf.setOnCheckedChangeListener { _, on -> Prefs.setAntiFlash(this, on) }
        c0.addView(swAf)
        ui.spacer(c0, 10)
        ui.text(
            c0,
            "Colore delle maschere: premi \"Campiona colore\", si apre Instagram con un mirino. " +
                "Trascinalo su un punto di sfondo vuoto e premi ✓: vedi il colore e, se va bene, Salva. " +
                "Resta salvato per sempre (uno per il tema scuro e uno per il chiaro).",
            14f, ui.muted
        )
        ui.button(c0, "🎯  Campiona colore") { startPicker() }
        ui.button(c0, "Torna al colore di base", primary = false) {
            Prefs.resetColors(this)
            Toast.makeText(this, "Ok: maschere nere (o bianche col tema chiaro). Vale dalla prossima apertura.", Toast.LENGTH_LONG).show()
        }

        // ---- Diagnosi
        val c2 = ui.cardBox(col)
        ui.text(c2, "Diagnosi", 17f, bold = true)
        ui.text(
            c2,
            "Se qualcosa non viene bloccato (o viene bloccato per sbaglio): attiva la diagnosi, " +
                "rifai la cosa su Instagram, poi tocca \"Condividi\" e manda il testo a Claude. " +
                "Contiene solo i nomi tecnici delle schermate, niente messaggi né nomi.",
            14f, ui.muted
        )
        val sw = Switch(this)
        sw.text = "Registra diagnosi"
        sw.setTextColor(ui.fg)
        sw.isChecked = Prefs.diagEnabled(this)
        sw.setOnCheckedChangeListener { _, on -> Prefs.setDiag(this, on) }
        c2.addView(sw)
        ui.spacer(c2, 10)
        ui.button(c2, "Condividi diagnosi", primary = false) { shareDiag() }
        ui.button(c2, "Cancella diagnosi", primary = false) {
            DiagLog.clear(this)
            Toast.makeText(this, "Cancellata", Toast.LENGTH_SHORT).show()
        }

        ui.text(col, "Instagram installato: ${DiagLog.igVersion(this)}", 12f, ui.muted)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(holdTick)
        holdStart = 0L
    }

    private fun refresh() {
        val paused = Prefs.isPaused(this)
        if (paused) {
            val t = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(Prefs.pausedUntil(this)))
            pauseState.text = "⏸  In pausa fino alle $t"
            holdBtn.text = "Già in pausa"
            holdBtn.isEnabled = false
            endBtn.visibility = View.VISIBLE
        } else {
            pauseState.text = "▶  Blocco attivo"
            holdBtn.text = "Tieni premuto ${Prefs.HOLD_SECONDS} s per la pausa"
            holdBtn.isEnabled = true
            endBtn.visibility = View.GONE
        }
    }

    private fun startPicker() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "Serve Android 11 o più recente", Toast.LENGTH_LONG).show(); return
        }
        Prefs.requestPicker(this)
        val i = try {
            Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox")).setPackage("com.instagram.android")
        } catch (_: Exception) { null }
        try {
            startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            val l = packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (l != null) startActivity(l) else Toast.makeText(this, "Instagram non trovato", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDiag() {
        val text = DiagLog.read(this)
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnosi Solo DM")
            .putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000))
        startActivity(Intent.createChooser(i, "Condividi diagnosi"))
    }
}
