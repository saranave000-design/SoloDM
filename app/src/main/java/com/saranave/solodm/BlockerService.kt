package com.saranave.solodm

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Il cuore dell'app. Regole:
 *  - Feed ed Esplora: coperti del tutto (con i giochini sopra); ricerca profili libera.
 *  - Pulsante Reels: coperto; se ci arrivi comunque → torni ai Messaggi.
 *  - Reel / post / storia aperti da Feed, Esplora o Reels → chiusi subito.
 *  - Reel / post / storia aperti da Chat o Profilo → li vedi, ma uno solo.
 *
 * Velocità all'apertura: le maschere usate l'ultima volta vengono rimesse
 * SUBITO (già al tocco dell'icona), senza aspettare di leggere la pagina;
 * poi la lettura vera le aggiusta. Due memorie: apertura normale e da notifica.
 */
class BlockerService : AccessibilityService() {

    companion object {
        @Volatile var running = false
            private set

        private const val EVAL_THROTTLE_MS = 80L
        private const val KICK_COOLDOWN_MS = 700L
        private const val VIEWER_GRACE_MS = 700L
        private const val UNKNOWN_STABLE_MS = 800L
        private const val LAUNCH_HOLD_MS = 2500L
        private const val UNKNOWN_HOLD_MS = 500L
        private const val CLICK_TRIGGER_VALID_MS = 3000L

        private const val MODE_HOME = "home"
        private const val MODE_NOTIF = "notif"
        private const val SYSTEMUI = "com.android.systemui"

        private val BLOCKED_ORIGINS = setOf(Screen.FEED, Screen.SEARCH, Screen.REELS_TAB)
    }

    private class Viewer(val screen: Screen, val origin: Screen, val start: Long) {
        var fingerprint: String? = null
        var netScroll = 0
        var firstFromIndex = -1
    }

    private val handler = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var masks: MaskManager

    private var evalScheduled = false
    private var lastKick = 0L

    private var current = Screen.UNKNOWN
    private var lastBase = Screen.FEED
    private var unknownSince = 0L
    private var lastKnownAt = 0L
    private var viewer: Viewer? = null

    // apertura
    private var igForeground = false
    private var launchMode = MODE_HOME
    private var launchAt = 0L
    private var launchRecognized = true
    private var clickTriggerAt = 0L
    private var clickTriggerMode = MODE_HOME
    private var lastOtherPkg = ""
    private var lastOtherAt = 0L

    // colore
    private var sampledThisSession = false
    private var lastShotAt = 0L

    private var lastSignature = ""
    private var lastDiagLine = ""

    private val evalRunnable = Runnable {
        evalScheduled = false
        evaluate()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "paused_until") handler.post { scheduleEval(0) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        masks = MaskManager(this)
        masks.setColor(Prefs.bgColor(this) ?: fallbackColor())
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        // Prepara in anticipo le finestre della home, invisibili
        val (w, h) = screenSize()
        Prefs.loadMemory(this, MODE_HOME, w, h)?.let { masks.prepare(it) }
        diag("servizio avviato")
    }

    override fun onDestroy() {
        running = false
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        if (::masks.isInitialized) masks.destroy()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        if (::masks.isInitialized) masks.destroy()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::masks.isInitialized) return
        val pkg = event.packageName?.toString()

        if (pkg == IgUi.PKG) {
            if (!igForeground && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onInstagramAppearing(event)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                diag("finestra Instagram: ${event.className}")
            }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
            scheduleEval(EVAL_THROTTLE_MS)
            return
        }

        // Tocco su un'icona / notifica di Instagram: accendi le maschere PRIMA che si apra
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg != packageName) {
            val fromShade = pkg == SYSTEMUI
            if (mentionsInstagram(event, exact = !fromShade)) onLaunchClick(if (fromShade) MODE_NOTIF else MODE_HOME)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && pkg != packageName) {
            lastOtherPkg = pkg
            lastOtherAt = SystemClock.uptimeMillis()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (igForeground || !masks.visibleKeys().isEmpty()) scheduleEval(40)
        }
    }

    /**
     * Icona: l'etichetta è esattamente "Instagram" (così un link "instagram.com"
     * in un'altra app non conta). Notifica: basta che la nomini.
     */
    private fun mentionsInstagram(e: AccessibilityEvent, exact: Boolean): Boolean {
        val parts = e.text.mapNotNull { it?.toString()?.trim() } + listOfNotNull(e.contentDescription?.toString()?.trim())
        return if (exact) parts.any { it.equals("instagram", true) }
        else parts.any { it.contains("instagram", true) }
    }

    // ------------------------------------------------------------------ apertura

    private fun onLaunchClick(mode: String) {
        if (Prefs.isPaused(this)) return
        val now = SystemClock.uptimeMillis()
        clickTriggerAt = now
        clickTriggerMode = mode
        startLaunch(mode, now, "tocco")
    }

    private fun onInstagramAppearing(event: AccessibilityEvent) {
        igForeground = true
        sampledThisSession = false
        val now = SystemClock.uptimeMillis()
        if (now - clickTriggerAt < CLICK_TRIGGER_VALID_MS) {
            // già gestito dal tocco
            launchMode = clickTriggerMode
            return
        }
        if (Prefs.isPaused(this)) return
        val mode = if (lastOtherPkg == SYSTEMUI && now - lastOtherAt < 4000) MODE_NOTIF else MODE_HOME
        startLaunch(mode, now, "primo evento ${event.className ?: ""}")
    }

    private fun startLaunch(mode: String, now: Long, why: String) {
        launchMode = mode
        launchAt = now
        launchRecognized = false
        viewer = null
        val (w, h) = screenSize()
        val mem = Prefs.loadMemory(this, mode, w, h)
        if (mem != null) masks.apply(mem)
        diag("apertura $mode ($why): memoria ${if (mem == null) "assente" else mem.keys.sorted().toString()} in ${SystemClock.uptimeMillis() - now} ms")
    }

    // ------------------------------------------------------------------ valutazione

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

    private fun evaluate() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val now = SystemClock.uptimeMillis()
        if (root == null || root.packageName?.toString() != IgUi.PKG) {
            // Appena toccata l'icona Instagram non è ancora in primo piano: aspetta
            if (now - clickTriggerAt < 1500 && !igForeground) {
                handler.postDelayed({ scheduleEval(1) }, 150)
                return
            }
            if (root != null || !isIgVisibleInWindows()) leftInstagram()
            return
        }
        if (!igForeground) {
            igForeground = true
            sampledThisSession = false
        }

        if (Prefs.isPaused(this)) {
            masks.hideAll()
            viewer = null
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

        trackBase(screen, now)
        current = screen

        var action = ""
        when (screen) {
            Screen.REELS_TAB -> {
                viewer = null
                action = "scheda Reels → Messaggi"
                goToInbox(snap)
            }
            Screen.CLIPS_VIEWER, Screen.STORY_VIEWER, Screen.POST_LIST -> action = handleViewer(screen, snap, now)
            else -> viewer = null
        }

        val desired = buildMasks(screen, snap)
        val holding = screen == Screen.UNKNOWN &&
            ((!launchRecognized && now - launchAt < LAUNCH_HOLD_MS) || now - lastKnownAt < UNKNOWN_HOLD_MS)
        if (!holding) masks.apply(desired)
        if (screen != Screen.UNKNOWN) {
            lastKnownAt = now
            if (!launchRecognized) {
                launchRecognized = true
                Prefs.saveMemory(this, launchMode, w, h, desired)
                diag("apertura $launchMode: riconosciuta $screen dopo ${now - launchAt} ms")
            }
            if (screen == Screen.FEED) Prefs.saveMemory(this, MODE_HOME, w, h, desired)
            maybeSampleColor(snap)
        } else if (holding) {
            handler.postDelayed({ scheduleEval(1) }, 200)
        }

        if (Prefs.diagEnabled(this)) {
            val sig = screen.name + "|" + snap.signature()
            if (sig != lastSignature || action.isNotEmpty()) {
                lastSignature = sig
                val line = "schermata=$screen origine=${viewer?.origin ?: lastBase} scheda=${snap.selectedTab() ?: "-"}" +
                    " maschere=${desired.keys.sorted()}" + (if (holding) " (tengo le precedenti)" else "") +
                    (if (action.isNotEmpty()) " AZIONE=$action" else "") +
                    "\n   id: ${snap.signature()}"
                if (line != lastDiagLine) {
                    lastDiagLine = line
                    DiagLog.add(this, line)
                }
            }
        }
    }

    private fun trackBase(screen: Screen, now: Long) {
        when (screen) {
            Screen.FEED, Screen.SEARCH, Screen.DM, Screen.PROFILE, Screen.REELS_TAB -> {
                lastBase = screen
                unknownSince = 0L
            }
            Screen.UNKNOWN -> {
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
        }
        if (v.origin in BLOCKED_ORIGINS) {
            back()
            return "$screen aperto da ${v.origin} → chiuso"
        }
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
                    if (e.fromIndex >= 0) v.firstFromIndex = e.fromIndex
                    return
                }
                if (dy != 0) {
                    v.netScroll += dy
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

        s.tabs[IgUi.TAB_REELS]?.let { t ->
            out["tab_reels"] = MaskManager.Mask(Rect(t.bounds))
        }

        when (screen) {
            Screen.FEED -> {
                val top = contentTop(s)
                if (tabTop - top > 100) out["feed"] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), MaskManager.Kind.GAME)
            }
            Screen.SEARCH -> {
                val edit = s.firstOf(IgUi.SEARCH_EDIT_IDS)
                val typing = edit?.node?.isFocused == true
                val showingUsers = s.hasPrefix(IgUi.SEARCH_USER_PREFIXES)
                if (!typing && !showingUsers) {
                    val top = edit?.bounds?.bottom?.plus(dp(8)) ?: (s.screenH * 0.14f).toInt()
                    if (tabTop - top > 100) out["esplora"] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), MaskManager.Kind.GAME)
                }
            }
            else -> {}
        }
        return out
    }

    /** Dove finisce la barra in alto del feed (logo, +, cuore, messaggi). */
    private fun contentTop(s: Snapshot): Int {
        val bar = s.firstOf(IgUi.ACTION_BAR_IDS)?.bounds
        if (bar != null && bar.bottom in 1 until s.screenH / 3) return bar.bottom
        val tray = s.allWithPrefix(IgUi.STORY_TRAY_PREFIXES).minOfOrNull { it.bounds.top }
        if (tray != null && tray in 1 until s.screenH / 3) return tray
        val firstRow = s.allWithPrefix(IgUi.FEED_ROW_PREFIXES).minOfOrNull { it.bounds.top }
        val fallback = (s.screenH * 0.11f).toInt()
        return if (firstRow != null && firstRow in 1 until s.screenH / 3) minOf(firstRow, fallback) else fallback
    }

    // ------------------------------------------------------------------ colore

    private fun fallbackColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.BLACK else Color.WHITE
    }

    /** Campiona il colore di sfondo di Instagram dal bordo sinistro della barra in alto. */
    private fun maybeSampleColor(s: Snapshot) {
        if (sampledThisSession || Build.VERSION.SDK_INT < 30) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < 10_000) return
        lastShotAt = now
        val bar = s.firstOf(IgUi.ACTION_BAR_IDS)?.bounds
        val yTop = bar?.top?.plus(dp(4)) ?: dp(30)
        val yBottom = bar?.bottom?.minus(dp(4))?.takeIf { it > yTop } ?: (yTop + dp(20))
        val points = (0..4).map { i -> dp(3) to (yTop + (yBottom - yTop) * i / 4) } +
            listOf((s.screenW - dp(3)) to yTop)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return
                        val colors = points.mapNotNull { (x, y) ->
                            if (x in 0 until bmp.width && y in 0 until bmp.height) bmp.getPixel(x, y) else null
                        }
                        bmp.recycle(); hw.recycle()
                        val best = colors.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return
                        if (best.value < 3) return
                        val c = best.key or 0xFF000000.toInt()
                        handler.post {
                            sampledThisSession = true
                            if (c != masks.color) {
                                masks.setColor(c)
                                Prefs.setBgColor(this@BlockerService, c)
                                diag("colore sfondo campionato: #%06X".format(c and 0xFFFFFF))
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {}
            })
        } catch (_: Exception) {
        }
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
        if (!igForeground && masks.visibleKeys().isEmpty()) return
        igForeground = false
        masks.hideAll()
        viewer = null
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
