package com.saranave.solodm

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Il cuore dell'app. Regole:
 *  - Scheda Reels: coperta; se ci arrivi comunque → torni ai Messaggi.
 *  - Esplora: coperta sotto la barra di ricerca; la ricerca di profili resta libera.
 *  - Feed: barra delle storie coperta; appena scorri, copre tutto il feed.
 *  - Reel / post / storia aperti da Feed, Esplora o Reels → chiusi subito.
 *  - Reel / post / storia aperti da Chat o Profilo → li vedi, ma uno solo:
 *    se provi a passare al successivo, si chiude.
 */
class BlockerService : AccessibilityService() {

    companion object {
        @Volatile var running = false
            private set

        private const val EVAL_THROTTLE_MS = 120L
        private const val KICK_COOLDOWN_MS = 700L
        private const val VIEWER_GRACE_MS = 700L
        private const val FEED_GRACE_MS = 900L
        private const val UNKNOWN_STABLE_MS = 800L

        /** Da qui un contenuto non può essere aperto. */
        private val BLOCKED_ORIGINS = setOf(Screen.FEED, Screen.SEARCH, Screen.REELS_TAB)
    }

    private class Viewer(val screen: Screen, val origin: Screen, val start: Long) {
        var fingerprint: String? = null
        var netScroll = 0
        var firstFromIndex = -1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var masks: MaskManager

    private var evalScheduled = false
    private var lastKick = 0L

    private var current = Screen.UNKNOWN
    private var lastBase = Screen.FEED
    private var unknownSince = 0L
    private var viewer: Viewer? = null

    private var feedEnteredAt = 0L
    private var feedScrolled = false

    private var igForeground = false
    private var lastSignature = ""
    private var lastDiagLine = ""

    private val evalRunnable = Runnable {
        evalScheduled = false
        evaluate()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { scheduleEval(0) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        masks = MaskManager(this)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        diag("servizio avviato")
    }

    override fun onDestroy() {
        running = false
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        if (::masks.isInitialized) masks.clear()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        if (::masks.isInitialized) masks.clear()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::masks.isInitialized) return
        val pkg = event.packageName?.toString()

        if (pkg == IgUi.PKG) {
            igForeground = true
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
            scheduleEval(EVAL_THROTTLE_MS)
            return
        }

        // Eventi di altre app o del sistema: controlla se Instagram è ancora davanti
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (igForeground) scheduleEval(60)
        }
    }

    private fun scheduleEval(delay: Long) {
        if (delay == 0L) {
            handler.removeCallbacks(evalRunnable)
            evalScheduled = false
            evaluate()
            return
        }
        if (evalScheduled) return
        evalScheduled = true
        handler.postDelayed(evalRunnable, delay)
    }

    // ------------------------------------------------------------------ valutazione

    private fun evaluate() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root == null || root.packageName?.toString() != IgUi.PKG) {
            if (root != null || !isIgVisibleInWindows()) leftInstagram()
            return
        }
        igForeground = true

        if (Prefs.isPaused(this)) {
            masks.clear()
            viewer = null
            // Ricontrolla quando la pausa finisce
            val left = Prefs.pausedUntil(this) - System.currentTimeMillis()
            handler.removeCallbacks(evalRunnable)
            evalScheduled = true
            handler.postDelayed(evalRunnable, left.coerceIn(500L, 60_000L))
            return
        }

        val (w, h) = screenSize()
        val snap = try {
            Snapshot.capture(root, w, h)
        } catch (_: Exception) {
            return
        }
        val screen = Classifier.classify(snap)
        val now = SystemClock.uptimeMillis()

        trackBase(screen, now)
        if (screen == Screen.FEED && current != Screen.FEED) {
            feedEnteredAt = now
            // Se la barra delle storie non è visibile, il feed è già scorso
            feedScrolled = !snap.hasPrefix(IgUi.STORY_TRAY_PREFIXES)
        }
        current = screen

        var action = ""
        when (screen) {
            Screen.REELS_TAB -> {
                viewer = null
                action = "scheda Reels → Messaggi"
                goToInbox(snap)
            }
            Screen.CLIPS_VIEWER, Screen.STORY_VIEWER, Screen.POST_LIST -> {
                action = handleViewer(screen, snap, now)
            }
            else -> viewer = null
        }

        val desired = buildMasks(screen, snap)
        masks.apply(desired)

        if (Prefs.diagEnabled(this)) {
            val sig = screen.name + "|" + snap.signature()
            if (sig != lastSignature || action.isNotEmpty()) {
                lastSignature = sig
                val line = "schermata=$screen origine=${viewer?.origin ?: lastBase} scheda=${snap.selectedTab() ?: "-"}" +
                    " maschere=${desired.keys.sorted()}" + (if (action.isNotEmpty()) " AZIONE=$action" else "") +
                    "\n   id: ${snap.signature()}"
                if (line != lastDiagLine) {
                    lastDiagLine = line
                    DiagLog.add(this, line)
                }
            }
        }
    }

    /** Ricorda da quale schermata "base" si arriva, per sapere da dove è stato aperto un contenuto. */
    private fun trackBase(screen: Screen, now: Long) {
        when (screen) {
            Screen.FEED, Screen.SEARCH, Screen.DM, Screen.PROFILE, Screen.REELS_TAB -> {
                lastBase = screen
                unknownSince = 0L
            }
            Screen.UNKNOWN -> {
                // Una schermata non riconosciuta conta solo se resta stabile
                // (evita che un fotogramma di passaggio "ripulisca" l'origine).
                if (unknownSince == 0L) {
                    unknownSince = now
                    handler.postDelayed({ scheduleEval(1) }, UNKNOWN_STABLE_MS + 50)
                } else if (now - unknownSince >= UNKNOWN_STABLE_MS) {
                    lastBase = Screen.UNKNOWN
                }
            }
            else -> unknownSince = 0L
        }
    }

    private fun handleViewer(screen: Screen, snap: Snapshot, now: Long): String {
        var v = viewer
        if (v == null || v.screen != screen) {
            v = Viewer(screen, lastBase, now)
            viewer = v
            if (v.origin in BLOCKED_ORIGINS) {
                back()
                return "$screen aperto da ${v.origin} → chiuso"
            }
        }
        if (v.origin in BLOCKED_ORIGINS) {
            back()
            return "$screen da ${v.origin} → chiuso"
        }
        // Contenuto permesso: controlla che non si passi al successivo
        val fp = when (screen) {
            Screen.CLIPS_VIEWER -> fingerprint(snap, IgUi.CLIPS_FINGERPRINT_PREFIXES, 1)
            Screen.STORY_VIEWER -> fingerprint(snap, IgUi.STORY_OWNER_IDS, 1, exact = true)
            else -> null
        }
        if (!fp.isNullOrBlank() && now - v.start > VIEWER_GRACE_MS) {
            val old = v.fingerprint
            if (old == null) {
                v.fingerprint = fp
            } else if (old != fp) {
                back()
                return "$screen: passato al successivo → chiuso"
            }
        }
        return ""
    }

    private fun fingerprint(snap: Snapshot, names: List<String>, max: Int, exact: Boolean = false): String? {
        val found = if (exact) names.flatMap { snap.ids[it].orEmpty() } else snap.allWithPrefix(names)
        val parts = found
            .sortedBy { it.bounds.top }
            .mapNotNull { (it.node.text ?: it.node.contentDescription)?.toString()?.trim()?.take(60) }
            .filter { it.isNotEmpty() }
            .take(max)
        return if (parts.isEmpty()) null else parts.joinToString("|")
    }

    // ------------------------------------------------------------------ scorrimento

    private fun onScrolled(e: AccessibilityEvent) {
        if (Prefs.isPaused(this)) return
        val src = e.source ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        val (w, h) = screenSize()
        if (r.height() < h * 0.4f || r.width() < w * 0.6f) return
        val dy = if (Build.VERSION.SDK_INT >= 28) e.scrollDeltaY else 0
        val now = SystemClock.uptimeMillis()

        when (current) {
            Screen.FEED -> {
                if (now - feedEnteredAt > FEED_GRACE_MS && dy >= 0 && !feedScrolled) {
                    feedScrolled = true
                    diag("feed scorso → coperto")
                }
            }
            Screen.CLIPS_VIEWER, Screen.STORY_VIEWER -> {
                val v = viewer ?: return
                if (v.origin in BLOCKED_ORIGINS) return
                val idx = e.fromIndex
                if (now - v.start < VIEWER_GRACE_MS) {
                    if (idx >= 0) v.firstFromIndex = idx
                    return
                }
                val isPager = IgUi.entryName(src)?.let { id ->
                    IgUi.CLIPS_VIEWER_PREFIXES.any { id.startsWith(it) } ||
                        IgUi.STORY_VIEWER_PREFIXES.any { id.startsWith(it) }
                } ?: (r.height() > h * 0.75f)
                if (!isPager) return
                val moved = kotlin.math.abs(dy) > 40 ||
                    (idx >= 0 && v.firstFromIndex >= 0 && idx != v.firstFromIndex)
                if (idx >= 0 && v.firstFromIndex < 0) v.firstFromIndex = idx
                if (moved) {
                    back()
                    diag("${v.screen}: swipe al successivo → chiuso")
                }
            }
            Screen.POST_LIST -> {
                val v = viewer ?: return
                if (v.origin in BLOCKED_ORIGINS) return
                if (now - v.start < VIEWER_GRACE_MS) {
                    // Instagram sta ancora posizionando la lista sul post toccato
                    if (e.fromIndex >= 0) v.firstFromIndex = e.fromIndex
                    return
                }
                if (dy != 0) {
                    v.netScroll += dy
                    // Puoi leggere tutto il post, ma non arrivare a quello dopo/prima
                    if (v.netScroll > h * 0.85f || v.netScroll < -h * 0.4f) {
                        back()
                        diag("lista post: scorso oltre il post → chiuso")
                    }
                } else {
                    val idx = e.fromIndex
                    if (idx < 0) return
                    if (v.firstFromIndex < 0) {
                        v.firstFromIndex = idx
                    } else if (idx - v.firstFromIndex >= 4 || v.firstFromIndex - idx >= 2) {
                        back()
                        diag("lista post: cambiato post → chiuso")
                    }
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ maschere

    private fun buildMasks(screen: Screen, s: Snapshot): Map<String, MaskManager.Mask> {
        val out = HashMap<String, MaskManager.Mask>()
        val tabTop = s.tabBarTop()

        // Pulsante Reels nella barra in basso: sempre coperto
        s.tabs[IgUi.TAB_REELS]?.let { t ->
            out["tab_reels"] = MaskManager.Mask(Rect(t.bounds), null)
        }

        when (screen) {
            Screen.FEED -> {
                // Barra delle storie
                val tray = s.allWithPrefix(IgUi.STORY_TRAY_PREFIXES)
                    .filter { it.bounds.height() > 0 && it.bounds.width() > s.screenW / 2 }
                    .maxByOrNull { it.bounds.width() * it.bounds.height() }
                if (tray != null) out["storie"] = MaskManager.Mask(Rect(tray.bounds), "Storie nascoste")

                if (feedScrolled) {
                    val top = contentTop(s, tray?.bounds?.top)
                    if (tabTop - top > 100) {
                        out["feed"] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), "Feed nascosto\n\nUsa i Messaggi o pubblica 🙂")
                    }
                }
            }
            Screen.SEARCH -> {
                val edit = s.firstOf(IgUi.SEARCH_EDIT_IDS)
                val typing = edit?.node?.isFocused == true
                val showingUsers = s.hasPrefix(IgUi.SEARCH_USER_PREFIXES)
                if (!typing && !showingUsers) {
                    val top = edit?.bounds?.bottom?.plus(dp(8)) ?: (s.screenH * 0.14f).toInt()
                    if (tabTop - top > 100) {
                        out["esplora"] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), "Esplora nascosto\n\nTocca la barra in alto per cercare un profilo")
                    }
                }
            }
            else -> {}
        }
        return out
    }

    /** Dove inizia il contenuto del feed (sotto la barra in alto). */
    private fun contentTop(s: Snapshot, trayTop: Int?): Int {
        val bar = s.firstOf(IgUi.ACTION_BAR_IDS)?.bounds
        if (bar != null && bar.bottom in 1 until s.screenH / 3) return bar.bottom
        if (trayTop != null && trayTop in 1 until s.screenH / 3) return trayTop
        val firstRow = s.allWithPrefix(IgUi.FEED_ROW_PREFIXES).minOfOrNull { it.bounds.top }
        val fallback = (s.screenH * 0.11f).toInt()
        return if (firstRow != null && firstRow in 1 until s.screenH / 3) maxOf(firstRow, fallback / 2) else fallback
    }

    // ------------------------------------------------------------------ azioni

    private fun back() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKick < KICK_COOLDOWN_MS) return
        lastKick = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        scheduleEvalLater(450)
    }

    private fun goToInbox(s: Snapshot) {
        val now = SystemClock.uptimeMillis()
        if (now - lastKick < KICK_COOLDOWN_MS) return
        lastKick = now
        val direct = s.tabs[IgUi.TAB_DIRECT]
        if (direct != null && clickNode(direct.node)) {
            scheduleEvalLater(450); return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
                .setPackage(IgUi.PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        scheduleEvalLater(450)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun scheduleEvalLater(ms: Long) {
        handler.postDelayed({ scheduleEval(1) }, ms)
    }

    private fun leftInstagram() {
        if (!igForeground && masks.isEmpty()) return
        igForeground = false
        masks.clear()
        viewer = null
        feedScrolled = false
        current = Screen.UNKNOWN
    }

    private fun isIgVisibleInWindows(): Boolean = try {
        windows.any { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.isActive && w.root?.packageName?.toString() == IgUi.PKG
        }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ utilità

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun diag(msg: String) {
        if (Prefs.diagEnabled(this)) DiagLog.add(this, msg)
    }
}
