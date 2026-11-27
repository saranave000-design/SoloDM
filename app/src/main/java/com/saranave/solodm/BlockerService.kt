package com.saranave.solodm

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
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

        private const val EVAL_FAST_MS = 60L
        private const val EVAL_SLOW_MS = 1200L
        private const val STABLE_AFTER_MS = 1200L
        private const val RESUME_VALID_MS = 20 * 60_000L
        private const val KICK_COOLDOWN_MS = 700L
        private const val VIEWER_GRACE_MS = 700L
        private const val UNKNOWN_STABLE_MS = 800L
        private const val LAUNCH_HOLD_MS = 2500L
        private const val UNKNOWN_HOLD_MS = 500L
        private const val CLICK_TRIGGER_VALID_MS = 3000L

        private const val MODE_HOME = "home"
        private const val MODE_NOTIF = "notif"
        private const val MODE_EXPLORE = "explore"
        private const val SYSTEMUI = "com.android.systemui"

        private val BLOCKED_ORIGINS = setOf(Screen.FEED, Screen.SEARCH, Screen.REELS_TAB)

        /** Home ed Esplora: un'unica maschera (e un unico gioco) che cambia solo forma. */
        const val KEY = "contenuto"
        private const val QUICK_THROTTLE_MS = 120L
        private const val TAP_HOLD_MS = 1000L
        private const val STICKY_MAX_MS = 1500L
        private const val EXIT_COVER_MS = 500L
        private const val ENTRY_COVER_MAX_MS = 1500L
        private val LAUNCHERS = setOf(
            "com.miui.home", "com.mi.android.globallauncher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher"
        )
    }

    private class Viewer(val screen: Screen, val origin: Screen, val start: Long) {
        var fingerprint: String? = null
        var netScroll = 0
        var firstFromIndex = -1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var masks: MaskManager

    private var evalScheduled = false
    private var evalDueAt = 0L
    private var lastChangeAt = 0L
    private var lastScreenKey = ""
    // anti-lampo
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var captureInFlight = false
    private var capturePending = false
    private var lastQuickAt = 0L
    private var lastStructuralAt = 0L
    /** Pagina (home o Esplora) su cui si torna con "indietro": serve per coprirla subito al ritorno. */
    private var returnBase: Screen? = null
    private var feedStableSince = 0L

    private var sheetOpen = false
    private var sheetClosedAt = 0L
    private var lastRecognizedMasks: Map<String, MaskManager.Mask> = emptyMap()
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

    private var lastSignature = ""
    private var lastDiagLine = ""

    private val evalRunnable = Runnable {
        evalScheduled = false
        try {
            evaluate()
        } catch (t: Throwable) {
            CrashGuard.record(this, "valutazione", t)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "paused_until") handler.post { scheduleEval(0) }
        if (key == "picker_request") handler.post { maybeStartPicker() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        CrashGuard.install(this)
        CrashGuard.clearAlert(this)
        masks = MaskManager(this)
        masks.setColor(initialColor())
        masks.onMute = { handler.post { muteVideo() } }
        masks.onGuard = { key, action -> onGuardTouch(key, action) }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        // Prepara in anticipo le finestre della home, invisibili
        val (w, h) = screenSize()
        Prefs.loadMemory(this, MODE_HOME, w, h)?.let { masks.prepare(it) }
        Prefs.loadMemory(this, "resume", w, h)?.let { masks.prepare(it) }
        Prefs.loadMemory(this, MODE_EXPLORE, w, h)?.let { masks.prepare(it) }
        diag("servizio avviato (maschere ${if (masks.appLayerActive()) "sotto le notifiche" else "livello accessibilità"})")
    }

    override fun onDestroy() {
        running = false
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        picker?.close()
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
        try {
            handleEvent(event ?: return)
        } catch (t: Throwable) {
            CrashGuard.record(this, "evento", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        if (!::masks.isInitialized) return
        val pkg = event.packageName?.toString()

        if (pkg == IgUi.PKG) {
            if (!igForeground && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onInstagramAppearing(event)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                diag("finestra Instagram: ${event.className}")
            }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
            if (picker == null && Prefs.pickerRequested(this)) maybeStartPicker()
            val structural = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            if (Prefs.antiFlash(this) && !Prefs.isPaused(this)) {
                val now = SystemClock.uptimeMillis()
                if (structural) lastStructuralAt = now
                // tocco sulla casetta: copri subito, prima che Instagram cambi schermata
                if ((event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) &&
                    event.source?.viewIdResourceName?.endsWith(":id/feed_tab") == true
                ) coverFeedFromMemory("tocco casetta")
                if ((event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) &&
                    event.source?.viewIdResourceName?.endsWith(":id/search_tab") == true
                ) coverExploreFromMemory("tocco lente")
                // freccia indietro mentre sei "sopra" la home o Esplora: copri prima che ricompaiano
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    event.source?.viewIdResourceName?.endsWith(":id/action_bar_button_back") == true
                ) when (returnBase) {
                    Screen.FEED -> coverFeedFromMemory("freccia indietro")
                    Screen.SEARCH -> coverExploreFromMemory("freccia indietro")
                    else -> {}
                }
                // controllo rapido: dopo un cambio di schermata (e per 1,5 s) a ogni evento, al massimo ogni 30 ms;
                // sempre, se home o Esplora possono ricomparire con un "indietro"
                if ((structural || now - lastStructuralAt < 800 || now - launchAt < 2500) && now - lastQuickAt >= QUICK_THROTTLE_MS) {
                    lastQuickAt = now
                    quickCheck()
                }
            }
            val stable = SystemClock.uptimeMillis() - lastChangeAt > STABLE_AFTER_MS
            scheduleEval(if (structural || !stable) EVAL_FAST_MS else EVAL_SLOW_MS)
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
            // Uscita verso home del telefono o app recenti mentre home/Esplora sono coperte:
            // copri tutto lo schermo durante l'animazione (la scheda che si rimpicciolisce mostrerebbe i post)
            val cls = event.className?.toString() ?: ""
            val toLauncher = pkg in LAUNCHERS || (pkg == SYSTEMUI && cls.contains("recent", ignoreCase = true))
            if (toLauncher && igForeground && Prefs.antiFlash(this) && !Prefs.isPaused(this) &&
                KEY in masks.visibleKeys()
            ) fullCover(EXIT_COVER_MS, "uscita da Instagram")
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
        // Instagram si riapre dove l'avevi lasciato; se è rimasto chiuso a lungo, dalla home.
        val leftAgo = System.currentTimeMillis() - Prefs.sp(this).getLong("left_at", 0L)
        val useResume = leftAgo in 0 until RESUME_VALID_MS
        val home = Prefs.loadMemory(this, MODE_HOME, w, h)
        var mem = (if (useResume) Prefs.loadMemory(this, "resume", w, h) else null) ?: home
        if (mode == MODE_NOTIF) {
            // da una notifica Instagram va dritto alla chat: niente maschera sul contenuto
            mem = mem?.filterKeys { it != KEY && !it.startsWith("guard_") }
        } else {
            // anti-lampo: home/Esplora si coprono SEMPRE all'apertura; si scoprono solo a pagina riconosciuta
            val feed = home?.get(KEY)
            if (Prefs.antiFlash(this) && feed != null && mem?.containsKey(KEY) != true) mem = (mem ?: emptyMap()) + (KEY to feed)
        }
        if (mem != null) masks.apply(mem)
        // Rientro (dalle recenti o dall'icona) verso home/Esplora: tutto coperto finché la pagina non è riconosciuta
        if (Prefs.antiFlash(this) && mode != MODE_NOTIF && mem != null && KEY in mem) fullCover(ENTRY_COVER_MAX_MS, "rientro")
        diag("apertura $mode ($why): memoria ${if (useResume) "dove l'avevi lasciato" else "home"} " +
            "${mem?.keys?.sorted() ?: "assente"} in ${SystemClock.uptimeMillis() - now} ms")
    }

    // ------------------------------------------------------------------ valutazione

    private fun scheduleEval(delay: Long) {
        if (delay == 0L) {
            handler.removeCallbacks(evalRunnable)
            evalScheduled = false
            evalRunnable.run()
            return
        }
        val due = SystemClock.uptimeMillis() + delay
        if (evalScheduled && evalDueAt <= due) return
        handler.removeCallbacks(evalRunnable)
        evalScheduled = true
        evalDueAt = due
        handler.postDelayed(evalRunnable, delay)
    }

    private fun fetchRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Exception) {
        null
    }

    private fun evaluate() {
        if (!Prefs.antiFlash(this)) {
            val root = fetchRoot()
            process(root, null, sync = true)
            return
        }
        // Lettura completa della pagina su un binario separato: il principale resta libero
        // per disegnare maschere e giochini.
        if (captureInFlight) { capturePending = true; return }
        captureInFlight = true
        val (w, h) = screenSize()
        captureExecutor.execute {
            val root = fetchRoot()
            val snap = if (root != null && root.packageName?.toString() == IgUi.PKG && !Prefs.isPaused(this)) {
                try { Snapshot.capture(root, w, h) } catch (_: Exception) { null }
            } else null
            handler.post {
                captureInFlight = false
                try {
                    process(root, snap, sync = false)
                } catch (t: Throwable) {
                    CrashGuard.record(this, "valutazione", t)
                }
                if (capturePending) { capturePending = false; scheduleEval(EVAL_FAST_MS) }
            }
        }
    }

    private fun process(root: AccessibilityNodeInfo?, snapIn: Snapshot?, sync: Boolean) {
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
        val snap = snapIn ?: if (sync) {
            try { Snapshot.capture(root, w, h) } catch (_: Exception) { return }
        } else return
        val screen = Classifier.classify(snap)
        val sheetNow = snap.hasAny(IgUi.SHEET_IDS)
        if (sheetOpen && !sheetNow) sheetClosedAt = now
        sheetOpen = sheetNow

        trackBase(screen, now)
        current = screen
        when (snap.selectedTab()) {
            IgUi.TAB_FEED -> returnBase = Screen.FEED
            IgUi.TAB_SEARCH -> returnBase = Screen.SEARCH
            null -> {}
            else -> returnBase = null
        }

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

        val desired = HashMap(buildMasks(screen, snap))
        val shown = masks.current(KEY)
        if (KEY !in desired && shown != null) {
            // copertura di base: si scopre solo quando la pagina è riconosciuta come libera
            // (non oltre 1,5 s su una pagina sconosciuta: es. la fotocamera per pubblicare)
            val stickyUnknown = screen == Screen.UNKNOWN && unknownSince != 0L && now - unknownSince < STICKY_MAX_MS
            if (stickyUnknown || now < coverHoldUntil) {
                desired[KEY] = shown
                handler.postDelayed({ scheduleEval(1) }, 400)
            }
        }
        if (screen == Screen.FEED || screen == Screen.SEARCH) coverHoldUntil = 0L
        if (now < uncoverHoldUntil) {
            if (screen == Screen.FEED || screen == Screen.SEARCH || screen == Screen.UNKNOWN) desired.remove(KEY)
            else uncoverHoldUntil = 0L
        }
        val holding = screen == Screen.UNKNOWN &&
            ((!launchRecognized && now - launchAt < LAUNCH_HOLD_MS) || now - lastKnownAt < UNKNOWN_HOLD_MS)
        if (!holding) masks.apply(desired)
        if (screen != Screen.UNKNOWN) {
            lastKnownAt = now
            if (!launchRecognized) {
                launchRecognized = true
                diag("apertura $launchMode: riconosciuta $screen dopo ${now - launchAt} ms")
            }
            lastRecognizedMasks = desired
            if (screen == Screen.FEED && snap.hasAny(IgUi.FEED_BAR_IDS)) Prefs.saveMemory(this, MODE_HOME, w, h, desired)
            if (screen == Screen.SEARCH && KEY in desired) Prefs.saveMemory(this, MODE_EXPLORE, w, h, desired)
            val key = screen.name + desired.keys.sorted()
            if (key != lastScreenKey) { lastScreenKey = key; lastChangeAt = now }
            if (screen == Screen.FEED) {
                if (feedStableSince == 0L) feedStableSince = now
            } else feedStableSince = 0L
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

        // Commenti aperti (o appena chiusi): quei movimenti non sono "passare al post dopo"
        if (current == Screen.POST_LIST || current == Screen.CLIPS_VIEWER || current == Screen.STORY_VIEWER) {
            if (sheetOpen || now - sheetClosedAt < 700 || insideSheet(src)) return
        }

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
                        IgUi.STORY_VIEWER_PREFIXES.any { id.startsWith(it) } ||
                        (current == Screen.STORY_VIEWER && id in IgUi.STORY_PAGER_IDS)
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

    private fun insideSheet(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 10) {
            val id = IgUi.entryName(n)
            // (attenzione: "bottom_sheet_camera_container" avvolge TUTTO, non va contato)
            if (id != null && (id in IgUi.SHEET_IDS || id in IgUi.SHEET_LIST_IDS ||
                    id.startsWith("comment") || id.startsWith("layout_comment"))
            ) return true
            n = n.parent
            depth++
        }
        return false
    }

    // ------------------------------------------------------------------ maschere

    /** Rettangoli dei pulsanti trasparenti: uno per icona, tagliati dove toccano l'icona vicina. */
    private fun guardRects(s: Snapshot): List<Pair<String, Rect>> {
        val all = s.tabs.values.filter { it.bounds.width() > 0 }.sortedBy { it.bounds.left }
        val out = ArrayList<Pair<String, Rect>>()
        for ((i, t) in all.withIndex()) {
            if (t.key !in GUARDED) continue
            val r = Rect(t.bounds)
            if (i > 0) r.left = maxOf(r.left, minOf(all[i - 1].bounds.right, t.bounds.centerX()))
            if (i + 1 < all.size) r.right = minOf(r.right, maxOf(all[i + 1].bounds.left, t.bounds.centerX()))
            if (r.width() > 10) out += t.key to r
        }
        return out
    }

    private val GUARDED = setOf(IgUi.TAB_FEED, IgUi.TAB_SEARCH, IgUi.TAB_DIRECT, IgUi.TAB_PROFILE)

    private fun buildMasks(screen: Screen, s: Snapshot): Map<String, MaskManager.Mask> {
        val out = HashMap<String, MaskManager.Mask>()
        val tabTop = s.tabBarTop()

        s.tabs[IgUi.TAB_REELS]?.let { t ->
            out["tab_reels"] = MaskManager.Mask(Rect(t.bounds))
        }
        // Anti-lampo: finestre trasparenti sopra casetta e lente. Prendono il tocco,
        // accendono la maschera e solo dopo premono l'icona vera di Instagram.
        // Messaggi e profilo: il tocco toglie subito la maschera.
        if (Prefs.antiFlash(this) && screen != Screen.CLIPS_VIEWER && screen != Screen.STORY_VIEWER) {
            for ((tab, r) in guardRects(s)) out["guard_$tab"] = MaskManager.Mask(r, MaskManager.Kind.GUARD)
        }

        when (screen) {
            Screen.FEED -> {
                val remembered = if (!s.hasAny(IgUi.FEED_BAR_IDS)) Prefs.loadMemory(this, MODE_HOME, s.screenW, s.screenH)?.get(KEY) else null
                if (remembered != null) out[KEY] = remembered
                else {
                    val top = contentTop(s)
                    if (tabTop - top > 100) out[KEY] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), MaskManager.Kind.GAME)
                }
            }
            Screen.SEARCH -> {
                val edit = s.firstOf(IgUi.SEARCH_EDIT_IDS)
                val typing = edit?.node?.isFocused == true
                val showingUsers = s.hasPrefix(IgUi.SEARCH_USER_PREFIXES)
                if (!typing && !showingUsers) {
                    val top = edit?.bounds?.bottom?.plus(dp(8)) ?: (s.screenH * 0.14f).toInt()
                    if (tabTop - top > 100) out[KEY] = MaskManager.Mask(Rect(0, top, s.screenW, tabTop), MaskManager.Kind.GAME)
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

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun fallbackColor(): Int = if (isDark()) Color.BLACK else Color.WHITE

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Il colore va bene solo se è coerente col tema: scuro nel tema scuro, chiaro in quello chiaro. */
    /** Colore delle maschere: quello campionato a mano col mirino, altrimenti nero / bianco. */
    private fun initialColor(): Int = Prefs.pickedColor(this, isDark()) ?: fallbackColor()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::masks.isInitialized) masks.setColor(initialColor())
    }

    // ------------------------------------------------------------------ mirino del colore

    private var picker: ColorPicker? = null

    /** Avviato da "Campiona colore" in Altro, appena Instagram è davanti. */
    private fun maybeStartPicker() {
        if (picker != null || !Prefs.pickerRequested(this)) return
        val root = fetchRoot() ?: return
        if (root.packageName?.toString() != IgUi.PKG) return
        Prefs.clearPickerRequest(this)
        val dark = isDark()
        val p = ColorPicker(this, onSave = { c ->
            Prefs.setPickedColor(this, dark, c)
            masks.setColor(c)
            diag("colore campionato col mirino: #%06X".format(c and 0xFFFFFF))
        }, onClose = { picker = null })
        picker = p
        p.start()
        diag("mirino del colore aperto")
    }

    // ------------------------------------------------------------------ audio

    private var audioFocus: android.media.AudioFocusRequest? = null

    /**
     * 🔇: Solo DM chiede ad Android l'audio (come una chiamata o un'altra app di musica),
     * così Instagram smette di suonare. Il volume del telefono non cambia.
     */
    private fun muteVideo() {
        val ok = try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocus?.let { am.abandonAudioFocusRequest(it) }
            val r = am.requestAudioFocus(req)
            audioFocus = req
            r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (t: Throwable) { CrashGuard.record(this, "audio", t); false }
        diag("🔇 audio preso: $ok")
        try { android.widget.Toast.makeText(this, if (ok) "🔇" else "Audio non disponibile", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun releaseAudio() {
        val f = audioFocus ?: return
        audioFocus = null
        try { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(f) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ anti-lampo

    private fun findVisible(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? = try {
        root.findAccessibilityNodeInfosByViewId("${IgUi.PKG}:id/$id").firstOrNull { it.isVisibleToUser }
    } catch (_: Exception) {
        null
    }

    /** Copre subito la home usando la memoria (o i bordi della barra, se la memoria manca). */
    private fun coverFeedFromMemory(why: String, root: AccessibilityNodeInfo? = null, bar: AccessibilityNodeInfo? = null) {
        val (w, h) = screenSize()
        val home = Prefs.loadMemory(this, MODE_HOME, w, h)
        val feed = home?.get(KEY) ?: run {
            if (root == null || bar == null) return
            val r = Rect(); bar.getBoundsInScreen(r)
            val tab = findVisible(root, "tab_bar")
            val tr = Rect(); tab?.getBoundsInScreen(tr)
            val bottom = if (tab != null && tr.top > h / 2) tr.top else h
            if (bottom - r.bottom < 100) return
            MaskManager.Mask(Rect(0, r.bottom, w, bottom), MaskManager.Kind.GAME)
        }
        masks.showOne(KEY, feed)
        home?.get("tab_reels")?.let { masks.showOne("tab_reels", it) }
        diag("rapido: home coperta ($why)")
    }

    /** Copre subito Esplora usando la memoria (o i bordi della barra di ricerca). */
    private fun coverExploreFromMemory(why: String, root: AccessibilityNodeInfo? = null, bar: AccessibilityNodeInfo? = null) {
        val (w, h) = screenSize()
        val mem = Prefs.loadMemory(this, MODE_EXPLORE, w, h)
        val m = mem?.get(KEY) ?: run {
            if (root == null || bar == null) return
            val r = Rect(); bar.getBoundsInScreen(r)
            val tab = findVisible(root, "tab_bar")
            val tr = Rect(); tab?.getBoundsInScreen(tr)
            val bottom = if (tab != null && tr.top > h / 2) tr.top else h
            if (bottom - r.bottom < 100) return
            MaskManager.Mask(Rect(0, r.bottom, w, bottom), MaskManager.Kind.GAME)
        }
        masks.showOne(KEY, m)
        mem?.get("tab_reels")?.let { masks.showOne("tab_reels", it) }
        diag("rapido: Esplora coperta ($why)")
    }

    /** Su Esplora si può cercare: niente maschera se stai scrivendo o se ci sono risultati. */
    private fun exploreBrowsing(root: AccessibilityNodeInfo): Boolean {
        val e = findVisible(root, "action_bar_search_edit_text")
        if (e != null) {
            if (e.isFocused) return false
            if (!e.isShowingHintText && !e.text.isNullOrBlank()) return false
        }
        return findVisible(root, "row_search_user_container") == null
    }

    /**
     * Controllo rapido con ricerche mirate (pochissime richieste invece di leggere tutta la pagina):
     * barra della home visibile → copri subito; chat/profilo visibili → scopri subito.
     */
    private fun quickCheck() {
        val now = SystemClock.uptimeMillis()
        val root = fetchRoot() ?: return
        if (root.packageName?.toString() != IgUi.PKG) return
        if (KEY in masks.visibleKeys()) {
            // coperto: si guarda solo se sei arrivata su chat o profilo (salvo subito dopo casetta/lente)
            if (now < coverHoldUntil) return
            val free = findVisible(root, "direct_inbox_action_bar") ?: findVisible(root, "row_thread_composer_edittext")
                ?: findVisible(root, "profile_header_container")
            if (free != null) {
                masks.hideKeys(listOf(KEY))
                diag("rapido: maschera tolta (${IgUi.entryName(free)})")
            }
            return
        }
        if (now < uncoverHoldUntil) return
        val feedBar = findVisible(root, "main_feed_action_bar")
        if (feedBar != null) {
            if (findVisible(root, "action_bar_button_back") == null) coverFeedFromMemory("barra home", root, feedBar)
            return
        }
        val exploreBar = findVisible(root, "explore_action_bar")
        if (exploreBar != null && findVisible(root, "action_bar_button_back") == null && exploreBrowsing(root)) {
            coverExploreFromMemory("barra Esplora", root, exploreBar)
        }
    }

    // ------------------------------------------------------------------ copertura totale (uscita / rientro)

    private var fullCoverUntil = 0L
    private val fullCoverOff = Runnable { fullCoverUntil = 0L; masks.hideKeys(listOf("fullcover")) }

    /** Copre tutto lo schermo col colore di sfondo per al massimo [ms] millisecondi. */
    private fun fullCover(ms: Long, why: String) {
        val (w, h) = screenSize()
        masks.showOne("fullcover", MaskManager.Mask(Rect(0, 0, w, h)))
        fullCoverUntil = SystemClock.uptimeMillis() + ms
        handler.removeCallbacks(fullCoverOff)
        handler.postDelayed(fullCoverOff, ms)
        diag("copertura totale ($why, max $ms ms)")
    }

    // ------------------------------------------------------------------ tocco su casetta / lente

    private var coverHoldUntil = 0L
    private var uncoverHoldUntil = 0L

    private fun onGuardTouch(key: String, action: Int) {
        val tab = key.removePrefix("guard_")
        val now = SystemClock.uptimeMillis()
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!Prefs.antiFlash(this) || Prefs.isPaused(this)) return
                when (tab) {
                    IgUi.TAB_FEED -> { uncoverHoldUntil = 0L; coverHoldUntil = now + TAP_HOLD_MS; coverFeedFromMemory("tocco casetta"); scheduleEvalLater(TAP_HOLD_MS + 50) }
                    IgUi.TAB_SEARCH -> { uncoverHoldUntil = 0L; coverHoldUntil = now + TAP_HOLD_MS; coverExploreFromMemory("tocco lente"); scheduleEvalLater(TAP_HOLD_MS + 50) }
                    else -> {
                        coverHoldUntil = 0L; uncoverHoldUntil = now + TAP_HOLD_MS
                        scheduleEvalLater(TAP_HOLD_MS + 50)
                        if (KEY in masks.visibleKeys()) { masks.hideKeys(listOf(KEY)); diag("tocco ${if (tab == IgUi.TAB_DIRECT) "messaggi" else "profilo"}: maschera tolta") }
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val root = fetchRoot()
                val id = when (tab) { IgUi.TAB_FEED -> "feed_tab"; IgUi.TAB_SEARCH -> "search_tab"; IgUi.TAB_DIRECT -> "direct_tab"; else -> "profile_tab" }
                val node = if (root != null && root.packageName?.toString() == IgUi.PKG) findVisible(root, id) else null
                if (node == null || !clickNode(node)) {
                    // l'icona non c'è più (es. si è aperta una chat): via i pulsanti trasparenti
                    masks.hideKeys(masks.visibleKeys().filter { it.startsWith("guard_") })
                    coverHoldUntil = 0L; uncoverHoldUntil = 0L
                    diag("tocco icona: $id non trovata")
                }
                scheduleEval(EVAL_FAST_MS)
            }
            android.view.MotionEvent.ACTION_CANCEL -> { coverHoldUntil = 0L; uncoverHoldUntil = 0L; scheduleEval(EVAL_FAST_MS) }
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
        picker?.close()
        releaseAudio()
        if (SystemClock.uptimeMillis() < fullCoverUntil) masks.hideAllExcept(setOf("fullcover")) else masks.hideAll()
        returnBase = null
        viewer = null
        current = Screen.UNKNOWN
        feedStableSince = 0L
        val (w, h) = screenSize()
        Prefs.saveMemory(this, "resume", w, h, lastRecognizedMasks)
        Prefs.sp(this).edit().putLong("left_at", System.currentTimeMillis()).apply()
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
