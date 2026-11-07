package com.atrium.launcher

import android.app.Activity
import android.content.pm.PackageManager

/** One top-level section of the token map. */
class Section(val id: String, val title: String, val note: String)

/**
 * ATRIUM · SETTINGS REGISTRY
 * ────────────────────────────────────────────────────────────────────────────
 * The structural half of settings: sections, groups and search. Values live in
 * [Tokens]; the shape of each value lives in the schema. Keeping them apart is
 * what lets the deck scale to hundreds of options without hundreds of rows
 * written by hand.
 */
class SettingsRegistry(
    private val activity: Activity,
    private val tokens: Tokens,
    private val settings: LauncherSettings
) {

    private val sectionList = listOf(
        Section("Clock", "Clock", "Format, face, placement, density"),
        Section("Apps", "Apps", "Layout, icons, labels, spacing"),
        Section("Search", "Search", "The field itself"),
        Section("Results", "Results", "How answers stack"),
        Section("Background", "Background", "The material behind everything"),
        Section("Palette", "Palette", "One hue for the whole surface"),
        Section("Typography", "Typography", "Global ink, scale and weight"),
        Section("Motion", "Motion", "Tempo, spring and depth"),
        Section("System", "System", "Bars, permissions, launcher duties")
    )

    fun sections(): List<Section> = sectionList

    fun countIn(sectionId: String): Int = Schema.all.count { it.section == sectionId }

    fun groupsIn(sectionId: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (t in Schema.all) if (t.section == sectionId) seen.add(t.group)
        return seen.toList()
    }

    fun leavesIn(sectionId: String, group: String): List<Tk> =
        Schema.all.filter { it.section == sectionId && it.group == group }

    /** Actions that live in the deck but aren't tokens. */
    fun systemActions(): List<Pair<String, () -> Unit>> = listOf(
        "Make Atrium your home app" to { settings.openDefaultHomeSettings() },
        "Contacts permission" to { requestContacts() },
        "Pick a wallpaper" to { settings.pickWallpaper() },
        "Atrium app info" to { settings.openAppSettings() }
    )

    fun contactsGranted(): Boolean =
        activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun requestContacts() {
        if (!contactsGranted()) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 101)
        } else settings.openAppSettings()
    }

    /**
     * FZF over title, help, group, section, unit and keywords. Title matches are
     * boosted so typing "size" finds sizes before prose that contains the word.
     */
    fun search(q: String): List<Tk> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Tk>>()
        for (t in Schema.all) {
            var best: Int? = Fzf.score(query, t.qual) ?: Fzf.score(query, t.label)
            if (best != null) best = best!! + 60
            Fzf.score(query, t.group)?.let { best = maxOf(best ?: 0, it + 18) }
            Fzf.score(query, t.section)?.let { best = maxOf(best ?: 0, it + 4) }
            Fzf.score(query, t.help)?.let { best = maxOf(best ?: 0, it - 10) }
            Fzf.score(query, t.key)?.let { best = maxOf(best ?: 0, it + 12) }
            if (t.unit.isNotEmpty()) Fzf.score(query, t.unit)?.let { best = maxOf(best ?: 0, it - 6) }
            for (k in t.keywords) Fzf.score(query, k)?.let { best = maxOf(best ?: 0, it + 8) }
            for (o in t.options) Fzf.score(query, o.label)?.let { best = maxOf(best ?: 0, it - 2) }
            if (best != null) scored.add(best!! to t)
        }
        scored.sortWith(compareByDescending<Pair<Int, Tk>> { it.first }
            .thenBy { pair -> sectionList.indexOfFirst { it.id == pair.second.section } })
        return scored.map { it.second }
    }
}
