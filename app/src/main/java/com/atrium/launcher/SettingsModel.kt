package com.atrium.launcher

import android.app.Activity

sealed class SettingNode(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
) {
    /** Optional live second line, for rows whose subtitle depends on current state. */
    var liveDescription: (() -> String)? = null

    class Category(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val children: List<SettingNode>,
    ) : SettingNode(id, title, description, tags)

    /** A drill-down list. Children are resolved lazily so they always reflect live state. */
    class Page(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val children: () -> List<SettingNode>,
    ) : SettingNode(id, title, description, tags)

    class Toggle(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : SettingNode(id, title, description, tags)

    class Choice(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val options: List<String>,
        val getCurrent: () -> String,
        val onSelect: (String) -> Unit,
    ) : SettingNode(id, title, description, tags)

    class Action(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val action: () -> Unit,
        /** Right-hand monospace verb, e.g. OPEN, RESET, GRANT. */
        val verb: String = "OPEN",
    ) : SettingNode(id, title, description, tags)

    /** Read-only fact, rendered as title + value. */
    class Info(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val value: () -> String,
    ) : SettingNode(id, title, description, tags)
}

class SettingsRegistry(
    private val activity: Activity,
    private val settings: LauncherSettings,
    private val index: AppIndex,
) {
    /** Fired whenever a setting changed and the launcher should re-read its configuration. */
    var onApply: (() -> Unit)? = null

    /** Fired when a change alters how the settings list itself should be laid out. */
    var onStructure: (() -> Unit)? = null

    private fun applied(structural: Boolean = false) {
        onApply?.invoke()
        if (structural) onStructure?.invoke()
    }

    val rootTree: List<SettingNode> = listOf(
        SettingNode.Category(
            id = "clock",
            title = "Clock & Date",
            description = "The type on your home screen",
            tags = listOf("clock", "time", "date", "format", "hours", "seconds"),
            children = listOf(
                SettingNode.Choice(
                    id = "clock_format",
                    title = "Time Format",
                    description = "Follow the system, or pin a 24 or 12 hour clock",
                    tags = listOf("24 hour", "12 hour", "am", "pm", "military", "format"),
                    options = listOf("Automatic", "24-hour", "12-hour"),
                    getCurrent = {
                        when (settings.clockFormat) {
                            1 -> "24-hour"
                            2 -> "12-hour"
                            else -> "Automatic"
                        }
                    },
                    onSelect = {
                        settings.clockFormat = when (it) {
                            "24-hour" -> 1
                            "12-hour" -> 2
                            else -> 0
                        }
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "show_date",
                    title = "Show Date",
                    description = "Weekday and date, set in small caps above the time",
                    tags = listOf("date", "day", "weekday", "calendar"),
                    get = { settings.showDate },
                    set = {
                        settings.showDate = it
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "show_seconds",
                    title = "Show Seconds",
                    description = "A live seconds counter beside the time",
                    tags = listOf("seconds", "ticker", "live"),
                    get = { settings.showSeconds },
                    set = {
                        settings.showSeconds = it
                        applied()
                    }
                ),
            )
        ),
        SettingNode.Category(
            id = "home",
            title = "Home Screen",
            description = "What waits for you on the first screen",
            tags = listOf("home", "pinned", "dock", "apps", "favorites"),
            children = listOf(
                SettingNode.Page(
                    id = "pinned_apps",
                    title = "Pinned Apps",
                    description = "Choose the apps that live on your home screen",
                    tags = listOf("pinned", "dock", "favorites", "home", "apps", "shortcuts"),
                    children = {
                        index.uniqueApps().map { entry ->
                            SettingNode.Toggle(
                                id = "pin_${entry.component.flattenToString()}",
                                title = entry.label,
                                tags = listOf("pin", entry.key),
                                get = { index.isPinned(entry) },
                                set = { on ->
                                    index.setPinned(entry, on)
                                    applied()
                                }
                            )
                        }
                    }
                ).apply {
                    liveDescription = {
                        val total = index.uniqueApps().size
                        val pinned = index.pinned().size
                        if (total == 0) "Loading installed apps"
                        else "$pinned of $total apps on the home screen"
                    }
                },
                SettingNode.Action(
                    id = "reset_pins",
                    title = "Restore Default Pins",
                    description = "Return to the apps Atrium suggests on first run",
                    tags = listOf("reset", "default", "restore", "pins"),
                    action = {
                        index.resetPins()
                        applied(structural = true)
                    },
                    verb = "RESTORE",
                ),
            )
        ),
        SettingNode.Category(
            id = "display",
            title = "Display",
            description = "System chrome and row rhythm",
            tags = listOf("display", "screen", "status bar", "navigation", "density"),
            children = listOf(
                SettingNode.Toggle(
                    id = "hide_status_bar",
                    title = "Hide Status Bar",
                    description = "Remove the clock, battery and notification icons above",
                    tags = listOf("status bar", "battery", "notifications", "fullscreen", "immersive"),
                    get = { settings.hideStatusBar },
                    set = {
                        settings.hideStatusBar = it
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "hide_nav_bar",
                    title = "Hide Navigation Bar",
                    description = "Swipe up from the bottom edge to reveal it again",
                    tags = listOf("navigation bar", "nav", "gestures", "fullscreen", "immersive"),
                    get = { settings.hideNavBar },
                    set = {
                        settings.hideNavBar = it
                        applied()
                    }
                ),
                SettingNode.Choice(
                    id = "density",
                    title = "Row Density",
                    description = "How much air sits between lines of text",
                    tags = listOf("density", "compact", "spacing", "padding", "rows"),
                    options = listOf("Comfortable", "Compact"),
                    getCurrent = { if (settings.compact) "Compact" else "Comfortable" },
                    onSelect = {
                        settings.density = if (it == "Compact") 1 else 0
                        applied(structural = true)
                    }
                ),
            )
        ),
        SettingNode.Category(
            id = "search",
            title = "Search",
            description = "What the search bar reaches for",
            tags = listOf("search", "web", "contacts", "results"),
            children = listOf(
                SettingNode.Toggle(
                    id = "contacts_search",
                    title = "Suggest Contacts",
                    description = "Match names and numbers while typing, tap to dial",
                    tags = listOf("contacts", "people", "phone", "dial", "call"),
                    get = { settings.contactsInSearch },
                    set = {
                        settings.contactsInSearch = it
                        applied()
                    }
                ),
                SettingNode.Choice(
                    id = "web_engine",
                    title = "Web Engine",
                    description = "Where the web column sends your query",
                    tags = listOf("google", "duckduckgo", "brave", "browser", "engine", "web"),
                    options = listOf("Google", "DuckDuckGo", "Brave Search"),
                    getCurrent = { engineName(settings.webEngine) },
                    onSelect = {
                        settings.webEngine = when (it) {
                            "DuckDuckGo" -> 1
                            "Brave Search" -> 2
                            else -> 0
                        }
                        applied()
                    }
                ),
            )
        ),
        SettingNode.Category(
            id = "motion",
            title = "Motion & Touch",
            description = "Weight, speed and feedback",
            tags = listOf("motion", "animation", "speed", "blur", "haptics", "spring"),
            children = listOf(
                SettingNode.Choice(
                    id = "anim_speed",
                    title = "Motion Speed",
                    description = "Pace of every spring, fade and slide in the launcher",
                    tags = listOf("speed", "fast", "slow", "snappy", "relaxed", "duration"),
                    options = listOf("Snappy", "Quick", "Standard", "Calm", "Slow"),
                    getCurrent = {
                        when {
                            settings.animSpeed <= 0.72f -> "Snappy"
                            settings.animSpeed <= 0.9f -> "Quick"
                            settings.animSpeed <= 1.15f -> "Standard"
                            settings.animSpeed <= 1.45f -> "Calm"
                            else -> "Slow"
                        }
                    },
                    onSelect = {
                        settings.animSpeed = when (it) {
                            "Snappy" -> 0.65f
                            "Quick" -> 0.82f
                            "Calm" -> 1.3f
                            "Slow" -> 1.7f
                            else -> 1.0f
                        }
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "stagger",
                    title = "Staggered Reveal",
                    description = "Rows arrive one after another instead of all at once",
                    tags = listOf("stagger", "cascade", "reveal", "list", "motion"),
                    get = { settings.staggerReveal },
                    set = {
                        settings.staggerReveal = it
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "blur",
                    title = "Blur to Focus",
                    description = "Defocus the screen while an app opens, then resolve it",
                    tags = listOf("blur", "focus", "depth", "render effect", "launch"),
                    get = { settings.blurEnabled },
                    set = {
                        settings.blurEnabled = it
                        applied()
                    }
                ),
                SettingNode.Choice(
                    id = "blur_strength",
                    title = "Blur Depth",
                    description = "How far out of focus the screen travels",
                    tags = listOf("blur", "depth", "strength", "radius"),
                    options = listOf("Subtle", "Medium", "Deep"),
                    getCurrent = {
                        when (settings.blurStrength) {
                            0 -> "Subtle"
                            2 -> "Deep"
                            else -> "Medium"
                        }
                    },
                    onSelect = {
                        settings.blurStrength = when (it) {
                            "Subtle" -> 0
                            "Deep" -> 2
                            else -> 1
                        }
                        applied()
                    }
                ),
                SettingNode.Toggle(
                    id = "haptics",
                    title = "Haptic Feedback",
                    description = "A small tick when you press, toggle or snap columns",
                    tags = listOf("haptics", "vibration", "feedback", "touch"),
                    get = { settings.hapticsEnabled },
                    set = {
                        settings.hapticsEnabled = it
                        applied()
                    }
                ),
            )
        ),
        SettingNode.Category(
            id = "system",
            title = "System",
            description = "Permissions and Android integration",
            tags = listOf("system", "permissions", "default", "launcher", "android"),
            children = listOf(
                SettingNode.Action(
                    id = "default_home",
                    title = "Default Launcher",
                    description = "Hand the home button to Atrium, system-wide",
                    tags = listOf("default", "home", "launcher", "selector", "system"),
                    action = { settings.openDefaultHomeSettings() }
                ),
                SettingNode.Action(
                    id = "contacts_permission",
                    title = "Contacts Access",
                    description = "Atrium only reads names and numbers, nothing else",
                    tags = listOf("permission", "contacts", "access", "privacy"),
                    action = {
                        if (settings.hasContactsAccess()) settings.openAppSettings()
                        else settings.requestContactsAccess(REQUEST_CONTACTS)
                    },
                    verb = "REVIEW",
                ).apply {
                    liveDescription = {
                        if (settings.hasContactsAccess()) "Granted. Review it in Android settings"
                        else "Not granted. Tap to ask Android for access"
                    }
                },
                SettingNode.Action(
                    id = "app_info",
                    title = "Atrium App Info",
                    description = "Battery, storage and every system permission",
                    tags = listOf("app info", "details", "permissions", "battery", "storage"),
                    action = { settings.openAppSettings() }
                ),
                SettingNode.Action(
                    id = "reset_all",
                    title = "Reset All Settings",
                    description = "Every preference back to its factory value",
                    tags = listOf("reset", "clear", "defaults", "factory"),
                    action = {
                        settings.reset()
                        applied(structural = true)
                    },
                    verb = "RESET",
                ),
            )
        ),
        SettingNode.Category(
            id = "about",
            title = "About",
            description = "What this is made of",
            tags = listOf("about", "version", "fonts", "license", "credits"),
            children = listOf(
                SettingNode.Info(
                    id = "version",
                    title = "Version",
                    tags = listOf("version", "build", "release"),
                    value = { settings.versionName() }
                ),
                SettingNode.Info(
                    id = "type",
                    title = "Typefaces",
                    description = "Inter Tight and Geist Mono, SIL Open Font License 1.1",
                    tags = listOf("font", "typeface", "inter", "geist", "license"),
                    value = { "OFL 1.1" }
                ),
                SettingNode.Info(
                    id = "search_engine",
                    title = "Search Algorithm",
                    description = "A zero-allocation port of fzf v2's Smith-Waterman scoring",
                    tags = listOf("fzf", "search", "algorithm", "fuzzy"),
                    value = { "fzf v2" }
                ),
                SettingNode.Info(
                    id = "motion_engine",
                    title = "Motion",
                    description = "Damped springs stepped on every display frame",
                    tags = listOf("spring", "choreographer", "motion", "physics"),
                    value = { "Choreographer" }
                ),
            )
        ),
    )

    /**
     * Search across settings using FZF matching against title, description, and tags.
     * Categories are flattened; pages match as entry points.
     */
    fun search(q: String): List<SettingNode> {
        val trimmed = q.trim()
        val all = ArrayList<SettingNode>()
        fun collect(nodes: List<SettingNode>) {
            for (node in nodes) {
                when (node) {
                    is SettingNode.Category -> collect(node.children)
                    else -> all += node
                }
            }
        }
        collect(rootTree)

        if (trimmed.isEmpty()) return all

        val scored = ArrayList<Pair<Int, SettingNode>>()
        for (node in all) {
            var best: Int? = Fzf.score(trimmed, node.title)
                ?.let { it + 20 } // title matches outrank everything else
            val descScore = Fzf.score(trimmed, node.description)
            if (descScore != null) best = maxOf(best ?: 0, descScore - 5)
            for (tag in node.tags) {
                val tagScore = Fzf.score(trimmed, tag)
                if (tagScore != null) best = maxOf(best ?: 0, tagScore)
            }
            if (best != null) scored += best to node
        }
        scored.sortWith(compareByDescending<Pair<Int, SettingNode>> { it.first }.thenBy { it.second.title })
        return scored.map { it.second }
    }

    companion object {
        const val REQUEST_CONTACTS = 101
    }
}
