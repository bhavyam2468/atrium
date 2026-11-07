package com.atrium.launcher

import android.app.Activity
import android.content.pm.PackageManager

/* ============================================================================
   Settings tree.

   A three-level spine — hub → area → page of leaves — that stays navigable
   at any size. Every leaf knows the token it edits (when it edits one), so
   the tree doubles as the index used by search, "recently adjusted" and the
   design studio's long-press sheets.
   ============================================================================ */

sealed class SettingNode(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val tokenKey: String? = null
) {
    class Category(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val children: List<SettingNode>
    ) : SettingNode(id, title, description, tags)

    class Info(
        id: String,
        title: String,
        description: String = ""
    ) : SettingNode(id, title, description)

    class Toggle(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        tokenKey: String? = null,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    ) : SettingNode(id, title, description, tags, tokenKey)

    class Action(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val emphasis: Boolean = false,
        val action: () -> Unit
    ) : SettingNode(id, title, description, tags)

    class Choice(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        tokenKey: String? = null,
        val options: () -> List<Opt>,
        val current: () -> Int,
        val select: (Int) -> Unit
    ) : SettingNode(id, title, description, tags, tokenKey)

    class Adjust(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        tokenKey: String? = null,
        val min: Float,
        val max: Float,
        val step: Float,
        val get: () -> Float,
        val set: (Float) -> Unit,
        val format: (Float) -> String
    ) : SettingNode(id, title, description, tags, tokenKey)
}

/** One selectable option inside a Choice sheet. */
class Opt(
    val label: String,
    val example: String? = null,
    val color: Int? = null,
    val typefaceFamily: Int? = null
)

class SettingsRegistry(
    private val activity: Activity,
    private val settings: LauncherSettings,
    private val theme: AtriumTheme
) {
    /** Wired by the activity once the home root exists. */
    var onOpenStudio: (() -> Unit)? = null

    val rootTree: List<SettingNode> by lazy { buildTree() }

    /* ------------------------- node factories -------------------------- */

    private fun toggle(
        key: String, title: String, desc: String,
        tags: List<String> = emptyList()
    ): SettingNode.Toggle {
        val def = TOKEN_DEFS[key]!!
        return SettingNode.Toggle(
            id = key, title = title, description = desc, tags = tags, tokenKey = key,
            get = { theme.raw(key) as? Boolean ?: (def.def as Boolean) },
            set = { theme.set(key, it) }
        )
    }

    private fun choiceInt(
        key: String, title: String, desc: String,
        options: () -> List<Opt>,
        tags: List<String> = emptyList()
    ): SettingNode.Choice {
        val def = TOKEN_DEFS[key]!!
        return SettingNode.Choice(
            id = key, title = title, description = desc, tags = tags, tokenKey = key,
            options = options,
            current = { theme.raw(key) as? Int ?: (def.def as Int) },
            select = { theme.set(key, it) }
        )
    }

    private fun adjust(
        key: String, title: String, desc: String,
        step: Float, format: (Float) -> String,
        tags: List<String> = emptyList()
    ): SettingNode.Adjust {
        val d = TOKEN_DEFS[key]!!
        return SettingNode.Adjust(
            id = key, title = title, description = desc, tags = tags, tokenKey = key,
            min = d.min.toFloat(), max = d.max.toFloat(), step = step,
            get = { (theme.raw(key) as Number).toFloat() },
            set = { v -> theme.set(key, if (d.type == TokType.INT) v.toInt() else v) },
            format = format
        )
    }

    /* ----------------------------- the tree ---------------------------- */

    private fun buildTree(): List<SettingNode> = listOf(
        SettingNode.Action(
            id = "studio",
            title = "Design studio",
            description = "Shape your home by touch — drag, tap, hold",
            tags = listOf("customize", "design", "editor", "studio", "theme", "personalize", "layout", "look"),
            emphasis = true,
            action = { onOpenStudio?.invoke() }
        ),
        SettingNode.Category(
            id = "appearance", title = "Appearance",
            description = "Type, clock, layout, colour and background",
            tags = listOf("look", "style", "theme", "design", "visual"),
            children = listOf(
                SettingNode.Category(
                    id = "typography", title = "Typography",
                    description = "Typeface, weight and sizing for labels",
                    tags = listOf("font", "type", "weight", "size", "letter spacing"),
                    children = listOf(
                        choiceInt(Tk.FAMILY, "Typeface", "Used everywhere unless the clock overrides it",
                            { Fonts.specs.mapIndexed { i, f -> Opt(f.name, typefaceFamily = i) } },
                            listOf("font", "family", "inter", "grotesk", "fraunces", "mono", "serif")),
                        adjust(Tk.UI_WEIGHT, "Label weight", "How heavy names and rows read",
                            10f, ::fmtW, listOf("bold", "light", "thin", "heavy")),
                        adjust(Tk.UI_SIZE, "Label size", "Base text size — the clock and pins scale from it",
                            1f, ::fmtSp, listOf("text", "size", "scale")),
                        adjust(Tk.TRACKING, "Letter spacing", "Tight tracking feels technical, wide feels editorial",
                            0.01f, ::fmtEm, listOf("tracking", "kerning", "spacing"))
                    )
                ),
                SettingNode.Category(
                    id = "clock", title = "Clock",
                    description = "Representation, arrangement and weight of the home clock",
                    tags = listOf("time", "date", "clock", "hours", "format"),
                    children = listOf(
                        choiceInt(Tk.TIME_FORMAT, "Time format", "Tap the clock in the studio to cycle this",
                            {
                                TIME_FORMATS.map { p ->
                                    Opt(patternExample(p), example = p)
                                }
                            }, listOf("24", "12", "seconds", "am", "pm", "military")),
                        choiceInt(Tk.DATE_FORMAT, "Date format", "Representation of the date line",
                            {
                                DATE_FORMATS.mapIndexed { i, p ->
                                    if (i == DATE_HIDDEN) Opt("Hidden", example = "no date line")
                                    else Opt(patternExample(p), example = p)
                                }
                            }, listOf("day", "month", "year", "weekday", "hidden")),
                        choiceInt(Tk.ARRANGEMENT, "Arrangement", "How time and date compose",
                            {
                                listOf(
                                    Opt("Time above date"),
                                    Opt("Date above time"),
                                    Opt("One line", example = "joined by the separator")
                                )
                            }, listOf("stack", "inline", "line", "order")),
                        choiceInt(Tk.SEPARATOR, "Separator", "What joins time and date on one line",
                            {
                                SEPARATORS.map { s ->
                                    Opt(if (s.isBlank()) "(space)" else s.trim(),
                                        example = patternExample(DATE_FORMATS[2]) + s + patternExample(TIME_FORMATS[1]))
                                }
                            }, listOf("dot", "pipe", "dash", "comma")),
                        SettingNode.Choice(
                            id = Tk.CLOCK_FAMILY, title = "Clock typeface",
                            description = "The clock can speak its own type",
                            tags = listOf("font", "clock", "family"),
                            tokenKey = Tk.CLOCK_FAMILY,
                            options = {
                                listOf(Opt("Same as labels")) + Fonts.specs.mapIndexed { i, f ->
                                    Opt(f.name, typefaceFamily = i)
                                }
                            },
                            current = { theme.clock_family + 1 },
                            select = { theme.set(Tk.CLOCK_FAMILY, it - 1) }
                        ),
                        adjust(Tk.CLOCK_SCALE, "Clock size", "Relative to label size",
                            0.2f, ::fmtX, listOf("big", "large", "small", "giant")),
                        adjust(Tk.CLOCK_WEIGHT, "Clock weight", "Light clocks feel calm; heavy ones feel bold",
                            20f, ::fmtW, listOf("thin", "bold")),
                        adjust(Tk.CLOCK_TRACKING, "Clock spacing", "Letter spacing of the time",
                            0.01f, ::fmtEm, listOf("tracking")),
                        adjust(Tk.DATE_SCALE, "Date size", "Relative to label size",
                            0.1f, ::fmtX, listOf("date", "size")),
                        adjust(Tk.DATE_WEIGHT, "Date weight", "Letter weight of the date line",
                            20f, ::fmtW, listOf("date", "bold")),
                        adjust(Tk.DATE_TRACKING, "Date spacing", "Wide, tracked-out dates read like a masthead",
                            0.02f, ::fmtEm, listOf("date", "tracking")),
                        toggle(Tk.DATE_UPPER, "Uppercase date", "Sets the date line in capitals")
                    )
                ),
                SettingNode.Category(
                    id = "home_layout", title = "Home layout",
                    description = "Where the clock and pinned apps sit on screen",
                    tags = listOf("position", "alignment", "padding", "layout", "anchor"),
                    children = listOf(
                        choiceInt(Tk.ALIGN_X, "Horizontal alignment", "Left, centre or right edge",
                            { listOf(Opt("Start"), Opt("Center"), Opt("End")) },
                            listOf("left", "right", "center", "align")),
                        adjust(Tk.ANCHOR_Y, "Vertical position", "Where the block floats between the edges",
                            0.05f, ::fmtPct, listOf("top", "bottom", "middle", "anchor")),
                        adjust(Tk.SIDE_INSET, "Side inset", "Breathing room at the screen edges",
                            2f, ::fmtDp, listOf("padding", "margin", "inset")),
                        adjust(Tk.CLOCK_GAP, "Clock to pins gap", "Space between the clock block and pinned apps",
                            4f, ::fmtDp, listOf("gap", "spacing", "margin")),
                        toggle(Tk.PINS_VISIBLE, "Show pinned apps", "The row of favourites under the clock"),
                        adjust(Tk.PINS_SIZE, "Pin size", "Relative to label size",
                            0.1f, ::fmtX, listOf("pins", "size")),
                        SettingNode.Choice(
                            id = Tk.PINS_ALIGN, title = "Pin alignment",
                            description = "How the pin row aligns inside the block",
                            tags = listOf("pins", "align"),
                            tokenKey = Tk.PINS_ALIGN,
                            options = {
                                listOf(Opt("Follow layout"), Opt("Start"), Opt("Center"), Opt("End"))
                            },
                            current = { theme.pins_align + 1 },
                            select = { theme.set(Tk.PINS_ALIGN, it - 1) }
                        )
                    )
                ),
                SettingNode.Category(
                    id = "apps_results", title = "Apps & results",
                    description = "Rows in the app list and search columns",
                    tags = listOf("rows", "list", "icons", "names", "density"),
                    children = listOf(
                        choiceInt(Tk.ROW_STYLE, "Row style", "Names, icons, or both",
                            { listOf(Opt("Name only"), Opt("Icon + name"), Opt("Icon only")) },
                            listOf("icons", "names", "text", "hide")),
                        adjust(Tk.ICON_SIZE, "Icon size", "Diameter of row icons",
                            2f, ::fmtDp, listOf("icon", "size")),
                        choiceInt(Tk.ICON_SHAPE, "Icon shape", "Mask cut into row icons",
                            { listOf(Opt("Circle"), Opt("Squircle"), Opt("Rounded"), Opt("Square")) },
                            listOf("shape", "mask", "round")),
                        toggle(Tk.ICON_MONO, "Monochrome icons", "Tint icons to match the palette"),
                        adjust(Tk.ROW_DENSITY, "Row spacing", "How tall each row breathes",
                            0.1f, ::fmtX, listOf("density", "spacing", "height")),
                        choiceInt(Tk.RESULT_ALIGN, "Row alignment", "Where rows sit inside their column",
                            { listOf(Opt("Start"), Opt("Center"), Opt("End")) },
                            listOf("align", "left", "right")),
                        toggle(Tk.SHOW_SUBTITLES, "Result details", "Secondary text next to results, like phone numbers"),
                        toggle(Tk.SHOW_HEADERS, "Column titles", "The small label above each column"),
                        adjust(Tk.COLUMN_WIDTH, "Column width", "How much screen a column claims",
                            0.05f, ::fmtPct, listOf("width", "columns"))
                    )
                ),
                SettingNode.Category(
                    id = "search_bar", title = "Search bar",
                    description = "The input everything rises from",
                    tags = listOf("search", "input", "bar"),
                    children = listOf(
                        choiceInt(Tk.SEARCH_STYLE, "Bar style", "Bare text, a hairline, or a quiet pill",
                            { listOf(Opt("Bare"), Opt("Underline"), Opt("Pill")) },
                            listOf("style", "underline", "pill", "bare")),
                        adjust(Tk.SEARCH_SIZE, "Bar text size", "Size of the query text",
                            2f, ::fmtSp, listOf("size", "text"))
                    )
                ),
                SettingNode.Category(
                    id = "color", title = "Colour & background",
                    description = "Accent, brightness and what lies behind",
                    tags = listOf("color", "background", "wallpaper", "black", "accent", "theme"),
                    children = listOf(
                        choiceInt(Tk.ACCENT, "Accent", "Highlights, cursors and the studio's actions",
                            { ACCENTS.map { (n, c) -> Opt(n, color = c) } },
                            listOf("accent", "color", "hue")),
                        toggle(Tk.TINT_TEXT, "Tint text with accent", "Let the accent colour the interface text too"),
                        adjust(Tk.BRIGHTNESS, "Text brightness", "Dim the whole interface for night eyes",
                            0.05f, ::fmtPct, listOf("brightness", "dim", "night")),
                        choiceInt(Tk.BACKGROUND, "Background", "True black, or your wallpaper behind a scrim",
                            { listOf(Opt("Pure black"), Opt("Wallpaper")) },
                            listOf("wallpaper", "black", "background", "amoled")),
                        adjust(Tk.WALLPAPER_DIM, "Wallpaper dim", "Darkness laid over the wallpaper",
                            0.05f, ::fmtPct, listOf("dim", "scrim", "darken"))
                    )
                )
            )
        ),
        SettingNode.Category(
            id = "behavior", title = "Behavior",
            description = "Motion and physical feel",
            tags = listOf("animation", "motion", "feel", "speed"),
            children = listOf(
                SettingNode.Category(
                    id = "motion", title = "Motion",
                    description = "Blur, speed and spring curves",
                    tags = listOf("blur", "speed", "animation", "spring", "physics"),
                    children = listOf(
                        SettingNode.Toggle(
                            id = "blur", title = "Blur-to-focus effect",
                            description = "Optical blur when launching apps and returning home",
                            tags = listOf("blur", "focus", "sharp", "depth"),
                            get = { settings.blurEnabled },
                            set = { settings.blurEnabled = it }
                        ),
                        SettingNode.Choice(
                            id = "anim_speed", title = "Animation speed",
                            description = "How quickly transitions settle",
                            tags = listOf("speed", "fast", "slow", "snappy", "relaxed"),
                            options = { listOf(Opt("Snappy", example = "0.75×"), Opt("Standard", example = "1.0×"), Opt("Relaxed", example = "1.5×")) },
                            current = {
                                when {
                                    settings.animSpeed <= 0.8f -> 0
                                    settings.animSpeed >= 1.3f -> 2
                                    else -> 1
                                }
                            },
                            select = {
                                settings.animSpeed = when (it) {
                                    0 -> 0.75f
                                    2 -> 1.5f
                                    else -> 1.0f
                                }
                            }
                        )
                    )
                )
            )
        ),
        SettingNode.Category(
            id = "system", title = "System",
            description = "Window, permissions and about",
            tags = listOf("system", "permissions", "default", "about"),
            children = listOf(
                SettingNode.Category(
                    id = "window", title = "Status bar & window",
                    description = "System bars and launcher defaults",
                    tags = listOf("status bar", "fullscreen", "immersive"),
                    children = listOf(
                        SettingNode.Toggle(
                            id = "hide_status_bar", title = "Hide status bar",
                            description = "Completely hide the system status bar for pure immersion",
                            tags = listOf("status bar", "battery", "clock", "notch", "fullscreen"),
                            get = { settings.hideStatusBar },
                            set = { settings.hideStatusBar = it }
                        ),
                        SettingNode.Action(
                            id = "default_home", title = "Default launcher",
                            description = "Change the system default home application",
                            tags = listOf("default", "home", "launcher", "system"),
                            action = { settings.openDefaultHomeSettings() }
                        )
                    )
                ),
                SettingNode.Category(
                    id = "permissions", title = "Permissions",
                    description = "System access for launcher integrations",
                    tags = listOf("permission", "contacts", "access"),
                    children = listOf(
                        SettingNode.Action(
                            id = "perm_contacts", title = "Contacts search access",
                            description = "Let Atrium search contacts in the universal search bar",
                            tags = listOf("contacts", "phone", "people", "address book"),
                            action = {
                                if (activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                                    activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 101)
                                } else {
                                    settings.openAppSettings()
                                }
                            }
                        ),
                        SettingNode.Action(
                            id = "app_details", title = "Atrium app info",
                            description = "Android system details for full permission control",
                            tags = listOf("app info", "details", "storage", "permissions"),
                            action = { settings.openAppSettings() }
                        )
                    )
                ),
                SettingNode.Category(
                    id = "about", title = "About",
                    description = "Version and resets",
                    tags = listOf("about", "version", "reset"),
                    children = listOf(
                        SettingNode.Info(
                            id = "version", title = "Atrium ${versionName()}",
                            description = "A quiet place for your apps"
                        ),
                        SettingNode.Action(
                            id = "reset_theme", title = "Reset customization",
                            description = "Restore every appearance token to its default",
                            tags = listOf("reset", "default", "restore"),
                            action = { theme.resetAll() }
                        )
                    )
                )
            )
        )
    )

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.1"
    } catch (_: Exception) { "0.1" }

    /* ----------------------------- lookup ------------------------------- */

    fun nodeForToken(key: String): SettingNode? {
        var found: SettingNode? = null
        fun walk(nodes: List<SettingNode>) {
            for (n in nodes) {
                if (found != null) return
                if (n is SettingNode.Category) walk(n.children)
                else if (n.tokenKey == key) found = n
            }
        }
        walk(rootTree)
        return found
    }

    fun recentNodes(): List<SettingNode> =
        theme.recentKeys().mapNotNull { nodeForToken(it) }

    /** Search across settings using FZF matching against title, description, and tags. */
    fun search(q: String): List<SettingNode> {
        val trimmed = q.trim()
        val allLeaves = ArrayList<SettingNode>()
        fun collectLeaves(nodes: List<SettingNode>) {
            for (node in nodes) {
                if (node is SettingNode.Category) collectLeaves(node.children)
                else allLeaves += node
            }
        }
        collectLeaves(rootTree)

        if (trimmed.isEmpty()) return allLeaves

        val scored = ArrayList<Pair<Int, SettingNode>>()
        for (node in allLeaves) {
            var bestScore: Int? = Fzf.score(trimmed, node.title)
            val descScore = Fzf.score(trimmed, node.description)
            if (descScore != null) {
                bestScore = maxOf(bestScore ?: 0, descScore - 5) // slight penalty for description match
            }
            for (tag in node.tags) {
                val tagScore = Fzf.score(trimmed, tag)
                if (tagScore != null) {
                    bestScore = maxOf(bestScore ?: 0, tagScore)
                }
            }
            if (bestScore != null) {
                scored += bestScore to node
            }
        }
        scored.sortByDescending { it.first }
        return scored.map { it.second }
    }
}
