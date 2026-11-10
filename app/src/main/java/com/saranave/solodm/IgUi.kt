package com.saranave.solodm

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tutto ciò che dipende dall'interfaccia interna di Instagram sta in questo file.
 * Quando Instagram si aggiorna e qualcosa smette di funzionare, di solito basta
 * aggiungere qui i nuovi identificativi (si trovano nel registro di diagnosi).
 *
 * Nota: dentro Instagram i Reels si chiamano "clips" (clips_*),
 * mentre le Storie si chiamano "reel" (reel_viewer_*, reels_tray_*).
 */
object IgUi {
    const val PKG = "com.instagram.android"
    private const val ID_PREFIX = "$PKG:id/"

    // Visualizzatore Reels a schermo intero
    val CLIPS_VIEWER_PREFIXES = listOf("clips_viewer")

    // Visualizzatore Storie
    val STORY_VIEWER_PREFIXES = listOf("reel_viewer")
    // Pannello dei commenti (o altro pannello che sale dal basso)
    val SHEET_IDS = listOf(
        "comment_overswipe_dismiss_container", "bottom_sheet_container", "layout_container_bottom_sheet",
        "bottom_sheet_container_view", "layout_comment_thread_edittext_multiline"
    )
    val SHEET_LIST_IDS = listOf("main_list_view", "sticky_header_list", "recycler_view_container_id")

    val STORY_PAGER_IDS = listOf("view_pager", "list_view_pager", "reel_viewer_root")
    val STORY_OWNER_IDS = listOf("reel_viewer_title", "reel_viewer_header_title", "reel_viewer_username")

    // Barra delle storie in cima al feed
    val STORY_TRAY_PREFIXES = listOf("reels_tray", "stories_tray", "reel_tray", "overlay_stories_tray")

    // Barre in alto che esistono SOLO in una schermata: riconoscimento immediato
    // anche quando, all'apertura, nessuna scheda in basso risulta ancora attiva.
    val FEED_BAR_IDS = listOf("main_feed_action_bar")
    val SEARCH_BAR_IDS = listOf("explore_action_bar", "explore_action_bar_container")

    // Autore del reel aperto: serve a capire se si è passati al successivo
    // (la didascalia no: cambia quando la espandi con "altro")
    val CLIPS_FINGERPRINT_PREFIXES = listOf("clips_author_username", "clips_author")

    // Post nel feed / lista post di un profilo
    val FEED_ROW_PREFIXES = listOf("row_feed_")

    // Chat / Messaggi
    val DM_PREFIXES = listOf("direct_", "row_thread", "thread_", "inbox_", "direct_inbox")
    val DM_CONTAINS = listOf("thread_composer", "message_composer", "direct_thread", "inbox_refreshable")

    // Profilo
    val PROFILE_PREFIXES = listOf("profile_header", "row_profile_header", "profile_tab_layout", "profile_tabs_container")

    // Ricerca
    val SEARCH_EDIT_IDS = listOf("action_bar_search_edit_text", "row_search_edit_text", "search_edit_text")
    val SEARCH_USER_PREFIXES = listOf("row_search_user", "search_user_", "row_user_")

    val BACK_IDS = listOf("action_bar_button_back", "action_bar_back_button", "back_button")
    // Barra in alto (per sapere dove inizia il contenuto e per campionare il colore).
    // NIENTE "action_bar_root": è la radice di tutta la schermata.
    val ACTION_BAR_IDS = listOf(
        "main_feed_action_bar", "explore_action_bar", "direct_inbox_action_bar",
        "profile_action_bar", "action_bar_container", "action_bar"
    )

    /** Riconoscimento dei pulsanti della barra in basso. */
    fun tabKeyForId(entry: String): String? = when {
        !entry.endsWith("_tab") -> null
        entry == "feed_tab" || entry == "home_tab" -> TAB_FEED
        entry.startsWith("clips") || entry.startsWith("reels") -> TAB_REELS
        entry.startsWith("search") || entry.startsWith("explore") -> TAB_SEARCH
        entry.startsWith("direct") || entry.startsWith("inbox") || entry.startsWith("messag") -> TAB_DIRECT
        entry.startsWith("profile") -> TAB_PROFILE
        else -> null
    }

    /** Ripiego se gli ID cambiano: descrizione del pulsante (IT/EN). */
    fun tabKeyForDesc(desc: String): String? {
        val d = desc.lowercase()
        return when {
            d.contains("reel") -> TAB_REELS
            d.contains("esplora") || d.contains("explore") || d.startsWith("cerca") || d.startsWith("search") -> TAB_SEARCH
            d.contains("messag") || d.contains("direct") || d.contains("chat") -> TAB_DIRECT
            d == "home" || d.startsWith("home") -> TAB_FEED
            d.contains("profil") -> TAB_PROFILE
            else -> null
        }
    }

    const val TAB_FEED = "feed"
    const val TAB_REELS = "reels"
    const val TAB_SEARCH = "search"
    const val TAB_DIRECT = "direct"
    const val TAB_PROFILE = "profile"

    fun entryName(node: AccessibilityNodeInfo): String? {
        val full = node.viewIdResourceName ?: return null
        return if (full.startsWith(ID_PREFIX)) full.substring(ID_PREFIX.length) else null
    }
}

enum class Screen { FEED, REELS_TAB, SEARCH, DM, PROFILE, CLIPS_VIEWER, STORY_VIEWER, POST_LIST, UNKNOWN }

class Found(val node: AccessibilityNodeInfo, val bounds: Rect)

class Tab(val key: String, val node: AccessibilityNodeInfo, val bounds: Rect, val selected: Boolean)

/** Fotografia della schermata attuale di Instagram. */
class Snapshot(val screenW: Int, val screenH: Int) {
    val ids = HashMap<String, MutableList<Found>>()
    val tabs = HashMap<String, Tab>()

    fun has(name: String) = ids.containsKey(name)
    fun hasAny(names: List<String>) = names.any { ids.containsKey(it) }
    fun hasPrefix(prefixes: List<String>) = ids.keys.any { k -> prefixes.any { k.startsWith(it) } }
    fun hasContains(parts: List<String>) = ids.keys.any { k -> parts.any { k.contains(it) } }

    fun firstOf(names: List<String>): Found? {
        for (n in names) ids[n]?.firstOrNull()?.let { return it }
        return null
    }

    fun allWithPrefix(prefixes: List<String>): List<Found> =
        ids.filterKeys { k -> prefixes.any { k.startsWith(it) } }.values.flatten()

    /** Esiste un elemento con quel prefisso che occupa gran parte dello schermo? */
    fun bigWithPrefix(prefixes: List<String>, fraction: Float): Boolean =
        allWithPrefix(prefixes).any { it.bounds.height() >= screenH * fraction && it.bounds.width() >= screenW * 0.6f }

    fun selectedTab(): String? = tabs.values.firstOrNull { it.selected }?.key

    /** Bordo superiore della barra in basso (o il fondo dello schermo). */
    fun tabBarTop(): Int {
        val tops = tabs.values.map { it.bounds.top }.filter { it > screenH / 2 }
        return tops.minOrNull() ?: screenH
    }

    fun signature(): String = ids.keys.sorted().joinToString(",")

    companion object {
        private const val MAX_NODES = 3500

        fun capture(root: AccessibilityNodeInfo, w: Int, h: Int): Snapshot {
            val s = Snapshot(w, h)
            val descCandidates = ArrayList<Tab>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                val entry = IgUi.entryName(n)
                if (entry != null) {
                    s.ids.getOrPut(entry) { ArrayList(2) }.add(Found(n, r))
                    IgUi.tabKeyForId(entry)?.let { key ->
                        if (r.top > h * 0.6f) s.tabs[key] = Tab(key, n, r, n.isSelected)
                    }
                }
                // Possibile pulsante della barra in basso riconosciuto dalla descrizione
                // (si decide alla fine: vale solo se sta DENTRO la barra "tab_bar")
                if (r.top > h * 0.75f && r.height() < h * 0.15f && r.width() < w * 0.4f) {
                    val desc = n.contentDescription?.toString()
                    if (!desc.isNullOrBlank()) {
                        IgUi.tabKeyForDesc(desc)?.let { key -> descCandidates += Tab(key, n, r, n.isSelected) }
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
            val bar = s.ids["tab_bar"]?.firstOrNull()?.bounds
            if (bar != null) {
                for (t in descCandidates) {
                    if (!bar.contains(t.bounds)) continue
                    val old = s.tabs[t.key]
                    if (old == null) s.tabs[t.key] = t
                    else if (t.selected && !old.selected) s.tabs[t.key] = Tab(t.key, old.node, old.bounds, true)
                }
            }
            return s
        }
    }
}

object Classifier {
    fun classify(s: Snapshot): Screen {
        val selected = s.selectedTab()

        // 1) Storie a schermo intero
        if (s.bigWithPrefix(IgUi.STORY_VIEWER_PREFIXES, 0.5f) ||
            (s.hasPrefix(IgUi.STORY_VIEWER_PREFIXES) && s.hasAny(IgUi.STORY_OWNER_IDS))
        ) return Screen.STORY_VIEWER

        // 2) Reels a schermo intero (scheda Reels o reel aperto da altrove)
        if (s.bigWithPrefix(IgUi.CLIPS_VIEWER_PREFIXES, 0.5f)) {
            return if (selected == IgUi.TAB_REELS) Screen.REELS_TAB else Screen.CLIPS_VIEWER
        }
        if (selected == IgUi.TAB_REELS) return Screen.REELS_TAB

        val hasBack = s.hasAny(IgUi.BACK_IDS)
        val hasFeedRows = s.hasPrefix(IgUi.FEED_ROW_PREFIXES)

        // 3) Lista di post aperta da un profilo/chat (ha la freccia indietro)
        if (hasFeedRows && hasBack && !s.hasPrefix(IgUi.PROFILE_PREFIXES)) return Screen.POST_LIST

        // 4) Chat
        if (selected == IgUi.TAB_DIRECT || isDm(s)) return Screen.DM

        // 5) Profilo
        if (s.hasPrefix(IgUi.PROFILE_PREFIXES)) return Screen.PROFILE

        // 6) Ricerca / Esplora
        if (selected == IgUi.TAB_SEARCH || (selected == null && s.hasAny(IgUi.SEARCH_BAR_IDS))) return Screen.SEARCH

        // 7) Feed: barra del feed, oppure scheda Home attiva con contenuti tipici del feed
        if (!hasBack && (selected == null || selected == IgUi.TAB_FEED) && s.hasAny(IgUi.FEED_BAR_IDS)) return Screen.FEED
        if (selected == IgUi.TAB_FEED && !hasBack && (hasFeedRows || s.hasPrefix(IgUi.STORY_TRAY_PREFIXES))) {
            return Screen.FEED
        }
        return Screen.UNKNOWN
    }

    private fun isDm(s: Snapshot): Boolean = s.ids.keys.any { k ->
        !k.endsWith("_tab") && !k.contains("button") &&
            (IgUi.DM_PREFIXES.any { k.startsWith(it) } || IgUi.DM_CONTAINS.any { k.contains(it) })
    }
}
