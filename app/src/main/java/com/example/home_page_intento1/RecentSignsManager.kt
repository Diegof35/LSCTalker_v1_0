package com.example.home_page_intento1

import android.content.Context

/**
 * In-memory + SharedPreferences singleton that tracks the last MAX_RECENT
 * videos the user played, across all screens (Home, Search, CategoryDetail).
 *
 * Call [load] once in MainActivity.onCreate.
 * Call [addAndSave] every time a video popup opens.
 */
object RecentSignsManager {

    const val MAX_RECENT = 20
    private const val PREFS_NAME  = "lsc_talker_prefs"
    private const val PREF_SIGNS  = "recent_signs_v1"

    // ── Data model ──────────────────────────────────────────────────────────
    data class RecentSignEntry(
        val displayName: String,    // e.g. "Pera"
        val categoryCode: String,   // e.g. "03"
        val categoryIndex: Int,     // 0-based position within category (Drive API)
        val categoryName: String,   // e.g. "Frutas"
        val imageResName: String    // drawable name string, e.g. "item_pear" or "theme_fruits"
    )

    // ── In-memory list (most recent first) ─────────────────────────────────
    private val _list = mutableListOf<RecentSignEntry>()
    val list: List<RecentSignEntry> get() = _list.toList()

    // ── Persistence: load from SharedPreferences ────────────────────────────
    fun load(context: Context) {
        val raw = prefs(context).getString(PREF_SIGNS, "") ?: return
        _list.clear()
        raw.split("\n").filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("|")
            if (p.size == 5) {
                _list.add(
                    RecentSignEntry(
                        displayName   = p[0],
                        categoryCode  = p[1],
                        categoryIndex = p[2].toIntOrNull() ?: 0,
                        categoryName  = p[3],
                        imageResName  = p[4]
                    )
                )
            }
        }
    }

    /**
     * Prepends [entry] to the list (removing duplicates), then saves.
     * Call this every time a video popup is opened.
     */
    fun addAndSave(context: Context, entry: RecentSignEntry) {
        _list.removeAll { it.categoryCode == entry.categoryCode && it.categoryIndex == entry.categoryIndex }
        _list.add(0, entry)
        while (_list.size > MAX_RECENT) _list.removeAt(_list.size - 1)
        save(context)
    }

    /** Returns one entry per unique category (first occurrence = most recent). */
    fun uniqueCategories(): List<RecentSignEntry> = _list.distinctBy { it.categoryCode }

    /**
     * Resolves [imageResName] to a drawable resource ID using the package resources.
     * Returns 0 if not found.
     */
    fun resolveDrawableRes(context: Context, entry: RecentSignEntry): Int =
        context.resources.getIdentifier(entry.imageResName, "drawable", context.packageName)

    // ── Private helpers ─────────────────────────────────────────────────────
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun save(context: Context) {
        val raw = _list.joinToString("\n") {
            "${it.displayName}|${it.categoryCode}|${it.categoryIndex}|${it.categoryName}|${it.imageResName}"
        }
        prefs(context).edit().putString(PREF_SIGNS, raw).apply()
    }
}