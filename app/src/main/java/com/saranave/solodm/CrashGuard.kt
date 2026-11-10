package com.saranave.solodm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Se qualcosa va storto:
 *  - l'errore viene SEMPRE scritto nel registro di diagnosi (anche a diagnosi spenta);
 *  - se l'app sta per chiudersi, parte una notifica che invita a riattivare il servizio
 *    (Android, dopo un errore, lo lascia "acceso" nelle impostazioni ma fermo).
 */
object CrashGuard {
    private const val CHANNEL = "avvisi"
    private const val NOTIF_ID = 41
    @Volatile private var installed = false

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                DiagLog.addSync(app, "ERRORE FATALE (${thread.name}): ${Log.getStackTraceString(t).take(4000)}")
                alert(app)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /** Errore intercettato: l'app continua a funzionare, ma lo annotiamo. */
    fun record(c: Context, where: String, t: Throwable) {
        DiagLog.add(c, "ERRORE in $where: ${Log.getStackTraceString(t).take(3000)}")
    }

    private fun alert(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Avvisi Solo DM", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            c, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Solo DM si è fermata")
            .setContentText("Tocca qui, poi spegni e riaccendi Solo DM per riattivare il blocco.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun clearAlert(c: Context) {
        try {
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}
