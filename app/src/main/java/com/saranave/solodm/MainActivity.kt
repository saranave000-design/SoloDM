package com.saranave.solodm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var restrictedHint: TextView
    private lateinit var batteryBtn: Button
    private lateinit var pauseInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (root, col) = ui.page()

        ui.title(col, "Solo DM")
        ui.text(col, "Instagram solo per messaggi e per pubblicare.", 15f, ui.muted, bottom = 22)

        val c1 = ui.cardBox(col)
        status = ui.text(c1, "", 17f, bold = true)
        enableBtn = ui.button(c1, "Attiva nelle impostazioni") { openAccessibility() }
        restrictedHint = ui.text(
            c1,
            "Se Android dice \"Impostazione con limitazioni\": vai su Impostazioni → App → Solo DM → menu ⋮ in alto → \"Consenti impostazioni con limitazioni\", poi riprova.",
            13f, ui.muted
        )
        ui.button(c1, "Apri le info dell'app", primary = false) { openAppInfo() }

        val c2 = ui.cardBox(col)
        ui.text(c2, "Cosa fa", 17f, bold = true)
        ui.text(
            c2,
            "🚫  Reels: coperto\n" +
                "🧩  Feed ed Esplora: coperti, con un giochino al posto\n" +
                "🚫  Storie dalla barra in alto: coperte\n" +
                "👀  Storie dal profilo di una persona: sì, solo le sue\n" +
                "👀  Reel ricevuti in chat: sì, ma uno solo\n" +
                "👀  Post di un profilo: uno alla volta\n" +
                "🔍  Ricerca: solo per trovare profili\n" +
                "✅  Messaggi, le tue storie e i post: liberi",
            14f
        )

        val c3 = ui.cardBox(col)
        ui.text(c3, "Perché non si spenga", 17f, bold = true)
        ui.text(c3, "Escludila dal risparmio batteria, così Android non la ferma.", 14f, ui.muted)
        batteryBtn = ui.button(c3, "Escludi dal risparmio batteria", primary = false) { askBattery() }

        pauseInfo = ui.text(col, "", 14f, ui.muted)

        ui.button(col, "🧩  Giochini", primary = false) {
            startActivity(Intent(this, GamesActivity::class.java))
        }

        ui.link(col, "Altro") {
            startActivity(Intent(this, AdvancedActivity::class.java))
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅  Attiva" else "⚠️  Non attiva"
        enableBtn.text = if (on) "Impostazioni accessibilità" else "Attiva nelle impostazioni"
        restrictedHint.visibility = if (on) View.GONE else View.VISIBLE

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (ignoring) "✓ Già esclusa dal risparmio batteria" else "Escludi dal risparmio batteria"
        batteryBtn.isEnabled = !ignoring

        if (Prefs.isPaused(this)) {
            val min = ((Prefs.pausedUntil(this) - System.currentTimeMillis()) / 60_000L) + 1
            pauseInfo.text = "⏸  In pausa ancora per circa $min min"
            pauseInfo.visibility = View.VISIBLE
        } else {
            pauseInfo.visibility = View.GONE
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, BlockerService::class.java)
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibility() {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (Build.VERSION.SDK_INT >= 33) {
            // Su alcuni telefoni apre direttamente la voce dell'app
            val me = ComponentName(this, BlockerService::class.java).flattenToString()
            i.putExtra(":settings:fragment_args_key", me)
            i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", me) })
        }
        startActivity(i)
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
