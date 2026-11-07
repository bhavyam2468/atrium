package com.atrium.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ATRIUM · TOKENS
 * ────────────────────────────────────────────────────────────────────────────
 * Every visual decision in the launcher is a *token*. Nothing is hardcoded in the
 * views — they all read from this store. That is what makes the home screen
 * directly manipulable: drag a handle, one token changes, the whole surface
 * re-derives and settles.
 *
 * This file is deliberately free of Android imports so it can be unit-tested on
 * a plain JVM and reasoned about in isolation. Persistence is injected through
 * [Store], so the same engine runs against SharedPreferences on device and an
 * in-memory map in tests.
 */

enum class TkKind { BOOL, NUM, CHOICE, TEXT }

/** One selectable value for a [TkKind.CHOICE] token. */
class TkOpt(val id: String, val label: String)

/**
 * A token descriptor: the single source of truth for its default, its domain,
 * how it should be rendered in the settings deck, and which live editing zone
 * (if any) can drive it directly on the home screen.
 */
class Tk(
    val key: String,
    val section: String,
    val group: String,
    val label: String,
    val help: String,
    val kind: TkKind,
    val def: String,
    val options: List<TkOpt> = emptyList(),
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f,
    val unit: String = "",
    val fmt: ((Float) -> String)? = null,
    val keywords: List<String> = emptyList(),
    val zone: String? = null,
    val advanced: Boolean = false
) {
    val defFloat: Float get() = def.toFloatOrNull() ?: 0f
    val defBool: Boolean get() = def == "1"

    /**
     * A label that stays short on a home-screen HUD but is unambiguous in the
     * settings deck — "Scale" means nothing when seven sections have one.
     */
    val qual: String by lazy {
        val g = group.trim()
        val base = label.trim()
        when {
            base.startsWith(g, ignoreCase = true) -> base
            g.isEmpty() -> base
            base.length <= 15 -> "$base · $g"
            else -> base
        }
    }

    /** Human readable value for a raw stored string. */
    fun display(raw: String): String = when (kind) {
        TkKind.BOOL -> if (raw == "1") "On" else "Off"
        TkKind.CHOICE -> options.firstOrNull { it.id == raw }?.label ?: raw
        TkKind.TEXT -> raw.ifEmpty { "—" }
        TkKind.NUM -> {
            val v = raw.toFloatOrNull() ?: defFloat
            fmt?.invoke(v) ?: (trimNum(v) + unit)
        }
    }

    /** Best-effort coercion of arbitrary input into a valid raw value. */
    fun coerce(raw: String): String = when (kind) {
        TkKind.BOOL -> if (raw == "1") "1" else "0"
        TkKind.CHOICE -> if (options.any { it.id == raw }) raw else def
        TkKind.TEXT -> raw.replace('\n', ' ')
        TkKind.NUM -> {
            val v = (raw.toFloatOrNull() ?: defFloat).coerceIn(min, max)
            val snapped = if (step > 0f) Math.round(v / step) * step else v
            trimNum(snapped)
        }
    }

    companion object {
        fun trimNum(v: Float): String {
            val s = String.format(Locale.US, "%.3f", v)
            return s.trimEnd('0').trimEnd('.')
        }
    }
}

/** Minimal persistence contract — SharedPreferences on device, a map in tests. */
interface Store {
    fun read(): String?
    fun write(value: String)
}

/**
 * The token store. Holds raw string values, exposes typed getters/setters with
 * instant in-memory reads (views are re-derived on every frame during a drag, so
 * reads must not touch disk), and keeps a bounded undo history.
 */
class Tokens(private val store: Store) {

    private val map = HashMap<String, String>(256)
    private val listeners = ArrayList<(Set<String>) -> Unit>(4)

    /** Keys changed since the last [flush] — useful for cheap incremental redraws. */
    private val dirty = HashSet<String>(32)

    /** Keys touched this session, most recent first — the deck's "Recent" shelf. */
    private val recent = LinkedHashSet<String>(16)

    /** Monotonic counter bumped on every structural/value change. */
    var revision: Int = 0
        private set

    private var suspendDepth = 0
    private var writeScheduled = false

    // ---- undo ------------------------------------------------------------
    private val history = ArrayList<HashMap<String, String>>(64)
    private var hIndex = -1

    var onHistoryChanged: (() -> Unit)? = null

    init {
        load()
        history.clear()
        history.add(HashMap(map))
        hIndex = 0
    }

    // ---- lifecycle -------------------------------------------------------

    fun recentKeys(limit: Int): List<Tk> =
        recent.toList().asReversed().mapNotNull { Schema.byKey[it] }.take(limit)

    private fun load() {
        val raw = store.read() ?: return
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val k = line.substring(0, tab)
            val v = line.substring(tab + 1)
            if (k == META_RECENT) {
                for (r in v.split(',')) if (Schema.byKey.containsKey(r)) recent.add(r)
                continue
            }
            if (Schema.byKey.containsKey(k)) map[k] = Schema.byKey[k]!!.coerce(v)
        }
    }

    private fun serialize(): String {
        val sb = StringBuilder(map.size * 32)
        if (recent.isNotEmpty()) {
            sb.append(META_RECENT).append('\t').append(recent.joinToString(",")).append('\n')
        }
        for (t in Schema.all) {
            val v = map[t.key] ?: continue
            if (v == t.def) continue
            sb.append(t.key).append('\t').append(v).append('\n')
        }
        return sb.toString()
    }

    private fun scheduleWrite() {
        if (writeScheduled) return
        writeScheduled = true
        Main.post {
            writeScheduled = false
            store.write(serialize())
        }
    }

    // ---- listeners -------------------------------------------------------

    fun addListener(l: (Set<String>) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Set<String>) -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        val snapshot = HashSet(dirty)
        dirty.clear()
        if (snapshot.isNotEmpty()) revision++
        for (l in listeners.toList()) l(snapshot)
    }

    /** Notify listeners for a *derived* change that has no token key (rare). */
    fun touch() { dirty.add("") ; notifyChanged() }

    // ---- raw access ------------------------------------------------------

    fun raw(key: String): String = map[key] ?: Schema.byKey[key]?.def ?: ""

    fun isDefault(key: String): Boolean = raw(key) == (Schema.byKey[key]?.def ?: "")

    fun changedCount(): Int = map.count { (k, v) -> Schema.byKey[k]?.def != v }

    fun f(key: String): Float = raw(key).toFloatOrNull() ?: 0f
    fun i(key: String): Int = raw(key).toFloatOrNull()?.toInt() ?: 0
    fun b(key: String): Boolean = raw(key) == "1"
    fun s(key: String): String = raw(key)

    fun set(key: String, value: Float) = setRaw(key, Tk.trimNum(value))
    fun set(key: String, value: Boolean) = setRaw(key, if (value) "1" else "0")
    fun set(key: String, value: String) = setRaw(key, value)

    fun setRaw(key: String, value: String) {
        val spec = Schema.byKey[key] ?: return
        val coerced = spec.coerce(value)
        if (map[key] == coerced) return
        map[key] = coerced
        recent.remove(key)
        recent.add(key)
        if (recent.size > 12) recent.remove(recent.first())
        dirty.add(key)
        scheduleWrite()
        notifyChanged()
    }

    fun reset(key: String) {
        val spec = Schema.byKey[key] ?: return
        if (map.remove(key) != null) {
            recent.remove(key)
            dirty.add(key)
            scheduleWrite()
            notifyChanged()
        }
        // no-op if already default
        if (spec.def.isEmpty()) return
    }

    fun resetAll() {
        map.clear()
        recent.clear()
        dirty.add("")
        scheduleWrite()
        notifyChanged()
    }

    // ---- batching (drag sessions) ---------------------------------------

    /**
     * Suppress listener fan-out while a continuous gesture emits a burst of
     * token writes; a single notification is delivered at [end].
     */
    fun begin() { suspendDepth++ }

    fun end() {
        if (suspendDepth > 0) suspendDepth--
        if (suspendDepth == 0) notifyChanged()
    }

    // ---- undo / redo -----------------------------------------------------

    private var lastCommit = 0L

    /** Record a coalesced history entry. Rapid edits fold into one step. */
    fun commit() {
        val now = System.currentTimeMillis()
        val snap = HashMap(map)
        if (hIndex >= 0 && history[hIndex] == snap) return
        if (history.isEmpty()) { history.add(snap); hIndex = 0; return }
        // coalesce edits that happen within 700ms of the previous commit
        if (now - lastCommit < 700 && hIndex == history.size - 1) {
            history[hIndex] = snap
        } else {
            while (history.size > hIndex + 1) history.removeAt(history.size - 1)
            history.add(snap)
            if (history.size > 200) history.removeAt(0)
            hIndex = history.size - 1
        }
        lastCommit = now
        onHistoryChanged?.invoke()
    }

    val canUndo: Boolean get() = hIndex > 0
    val canRedo: Boolean get() = hIndex < history.size - 1

    fun undo(): Boolean {
        if (!canUndo) return false
        hIndex--
        applySnapshot(history[hIndex])
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        hIndex++
        applySnapshot(history[hIndex])
        return true
    }

    private fun applySnapshot(snap: HashMap<String, String>) {
        map.clear()
        map.putAll(snap)
        dirty.add("")
        scheduleWrite()
        lastCommit = 0L
        notifyChanged()
        onHistoryChanged?.invoke()
    }

    // ---- bulk import / export -------------------------------------------

    fun snapshot(): HashMap<String, String> = HashMap(map)

    fun applyAll(values: Map<String, String>, keepHistory: Boolean = false) {
        map.clear()
        for ((k, v) in values) {
            val spec = Schema.byKey[k] ?: continue
            map[k] = spec.coerce(v)
        }
        dirty.add("")
        scheduleWrite()
        notifyChanged()
        if (keepHistory) commit() else {
            history.clear(); history.add(HashMap(map)); hIndex = 0
            onHistoryChanged?.invoke()
        }
    }

    companion object {
        private const val META_RECENT = "!recent"
    }

    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("# atrium tokens · ").append(Schema.all.size).append(" keys\n")
        var section = ""
        for (t in Schema.all) {
            if (t.section != section) {
                section = t.section
                sb.append('\n').append('#').append(' ').append(section).append('\n')
            }
            sb.append(t.key).append('\t').append(raw(t.key)).append('\n')
        }
        return sb.toString()
    }

    fun importText(text: String): Int {
        var n = 0
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val tab = l.indexOf('\t')
            if (tab <= 0) continue
            val k = l.substring(0, tab).trim()
            val v = l.substring(tab + 1)
            val spec = Schema.byKey[k] ?: continue
            map[k] = spec.coerce(v)
            n++
        }
        dirty.add("")
        scheduleWrite()
        notifyChanged()
        commit()
        return n
    }

    /** Randomise into a coherent, always-legible look. */
    fun shuffle() {
        val r = java.util.Random()
        fun pick(vararg ids: String) = ids[r.nextInt(ids.size)]
        set(K.clockStyle, pick("plain", "condensed", "serif", "mono", "hairline", "wide"))
        set(K.clockFormat, pick("h:mm", "HH:mm", "h:mm a", "h:mm:ss a", "h", "h:mm aaaa", "HH:mm:ss"))
        set(K.clockDateFormat, pick("EEE, d MMMM", "EEEE", "d MMMM", "d MMM", "MMM d", "EEE d MMM", "d.MM.yyyy"))
        set(K.clockArrangement, pick("dateAbove", "inline", "time", "dateBelow"))
        set(K.clockAlign, pick("start", "center", "end"))
        set(K.clockAnchor, pick("topStart", "topCenter", "centerStart", "centerEnd"))
        set(K.clockWeight, pick("200", "300", "400", "600"))
        set(K.clockScale, 0.8f + r.nextFloat() * 0.9f)
        set(K.clockTracking, -0.04f + r.nextFloat() * 0.2f)
        set(K.pinsLayout, pick("list", "row", "grid2", "inline"))
        set(K.pinsAlign, pick("start", "center", "end"))
        set(K.pinsAnchor, pick("center", "topStart", "bottomStart", "centerStart"))
        set(K.pinsIcons, r.nextBoolean())
        set(K.pinsSize, 0.8f + r.nextFloat() * 0.5f)
        set(K.pinsGap, 0.2f + r.nextFloat() * 1.0f)
        set(K.bgMode, pick("void", "gradient", "aurora", "solid"))
        set(K.bgHue, r.nextFloat() * 360f)
        set(K.bgGlow, 0.15f + r.nextFloat() * 0.6f)
        set(K.bgGrain, r.nextFloat() * 0.15f)
        set(K.searchStyle, pick("bare", "underline", "pill", "box"))
        commit()
    }
}

/** Tiny main-thread hook so Tokens.kt stays Android-free. */
object Main {
    var post: (Runnable) -> Unit = { it.run() }
}

// ════════════════════════════════════════════════════════════════════════════
//  TOKEN KEYS
// ════════════════════════════════════════════════════════════════════════════

object K {
    // ── clock ──────────────────────────────────────────────────────────────
    const val clockOn = "clock.on"
    const val clockStyle = "clock.style"
    const val clockFormat = "clock.format"
    const val clockFormatCustom = "clock.format.custom"
    const val clockDateFormat = "clock.dateFormat"
    const val clockCustomDate = "clock.date.custom"
    const val clockArrangement = "clock.arrangement"
    const val clockAlign = "clock.align"
    const val clockAnchor = "clock.anchor"
    const val clockScale = "clock.scale"
    const val clockWeight = "clock.weight"
    const val clockTracking = "clock.tracking"
    const val clockCase = "clock.case"
    const val clockOpacity = "clock.opacity"
    const val clockColonBlink = "clock.colonBlink"
    const val clockTabular = "clock.tabular"
    const val clockDateOpacity = "clock.dateOpacity"
    const val clockDivider = "clock.divider"
    const val clockDx = "clock.dx"
    const val clockDy = "clock.dy"
    const val clockDateScale = "clock.dateScale"
    const val clockSecondsTick = "clock.tickFade"
    const val clockFace = "clock.face"
    const val clockCustomFace = "clock.face.custom"
    const val clockShadow = "clock.shadow"
    const val clockGlow = "clock.glow"

    // ── pins / apps ────────────────────────────────────────────────────────
    const val pinsOn = "pins.on"
    const val pinsCount = "pins.count"
    const val pinsAlign = "pins.align"
    const val pinsAnchor = "pins.anchor"
    const val pinsLayout = "pins.layout"
    const val pinsIcons = "pins.icons"
    const val pinsIconSize = "pins.iconSize"
    const val pinsIconStyle = "pins.iconStyle"
    const val pinsLabels = "pins.labels"
    const val pinsSize = "pins.size"
    const val pinsWeight = "pins.weight"
    const val pinsGap = "pins.gap"
    const val pinsOpacity = "pins.opacity"
    const val pinsCase = "pins.case"
    const val pinsDx = "pins.dx"
    const val pinsDy = "pins.dy"
    const val pinsStyle = "pins.style"
    const val pinsTracking = "pins.tracking"
    const val pinsFountain = "pins.fountain"
    const val pinsFadeIn = "pins.fadeIn"
    const val pinsIconBreathe = "pins.iconBreathe"

    // ── search field ───────────────────────────────────────────────────────
    const val searchStyle = "search.style"
    const val searchSize = "search.size"
    const val searchAlign = "search.align"
    const val searchHint = "search.hint"
    const val searchHintCase = "search.hintCase"
    const val searchGlyph = "search.glyph"
    const val searchPad = "search.pad"
    const val searchOpacity = "search.opacity"
    const val searchRule = "search.rule"

    // ── results list ───────────────────────────────────────────────────────
    const val listSize = "list.size"
    const val listAlign = "list.align"
    const val listIcons = "list.icons"
    const val listIconSize = "list.iconSize"
    const val listLabelStyle = "list.labelStyle"
    const val listRowGap = "list.rowGap"
    const val listDim = "list.dim"
    const val listHeader = "list.header"
    const val listHeaderSize = "list.headerSize"
    const val listStagger = "list.stagger"
    const val listMaxRows = "list.maxRows"
    const val listTracking = "list.tracking"

    // ── background ─────────────────────────────────────────────────────────
    const val bgMode = "bg.mode"
    const val bgTone = "bg.tone"
    const val bgHue = "bg.hue"
    const val bgSaturation = "bg.saturation"
    const val bgGlow = "bg.glow"
    const val bgVignette = "bg.vignette"
    const val bgGrain = "bg.grain"
    const val bgMotion = "bg.motion"
    const val bgWallpaper = "bg.wallpaper"
    const val bgWallpaperDim = "bg.wallpaperDim"
    const val bgWallpaperBlur = "bg.wallpaperBlur"
    const val bgGradientAngle = "bg.gradientAngle"

    // ── palette ────────────────────────────────────────────────────────────
    const val palHue = "pal.hue"
    const val palSat = "pal.saturation"
    const val palWarm = "pal.warmth"

    // ── typography ─────────────────────────────────────────────────────────
    const val typeFace = "type.face"
    const val typeGutter = "type.gutter"
    const val typeScale = "type.scale"
    const val typeHue = "type.hue"
    const val typeTone = "type.tone"
    const val typeWeightShift = "type.weightShift"

    // ── motion ─────────────────────────────────────────────────────────────
    const val motionSpeed = "motion.speed"
    const val motionSpring = "motion.spring"
    const val motionBlur = "motion.blur"
    const val motionBlurAmount = "motion.blurAmount"
    const val motionDepth = "motion.depth"
    const val motionHaptics = "motion.haptics"
    const val motionEntrance = "motion.entrance"
    const val motionStagger = "motion.stagger"

    // ── system ─────────────────────────────────────────────────────────────
    const val sysStatusBar = "sys.statusBar"
    const val sysNavBar = "sys.navBar"
    const val sysColumnContacts = "sys.columnContacts"
    const val sysColumnWeb = "sys.columnWeb"
}

// ════════════════════════════════════════════════════════════════════════════
//  SCHEMA
// ════════════════════════════════════════════════════════════════════════════

object Schema {

    private val list = ArrayList<Tk>(128)
    val all: List<Tk> get() = list
    val byKey: Map<String, Tk> = HashMap<String, Tk>()

    private val formats = listOf(
        "h" to "3",
        "h:mm" to "3:04",
        "h:mm a" to "3:04 PM",
        "h:mm aaaa" to "3:04 p.m.",
        "h:mm:ss" to "3:04:05",
        "h:mm:ss a" to "3:04:05 PM",
        "hh:mm" to "03:04",
        "h.mm a" to "3.04 PM",
        "h a" to "3 PM",
        "h 'o’clock'" to "3 o’clock",
        "HH" to "15",
        "H" to "15",
        "H:mm" to "15:04",
        "HH:mm" to "15:04",
        "HH:mm:ss" to "15:04:05",
        "HH:mm 'h'" to "15:04 h",
        "HHmm" to "1504"
    )

    private val dateFormats = listOf(
        "d" to "3",
        "MMM" to "Sep",
        "d MMM" to "3 Sep",
        "EEE · d MMM" to "Wed · 3 Sep",
        "EEEE d" to "Wednesday 3",
        "EEE, d MMMM" to "Wed, 3 September",
        "EEE d MMM" to "Wed 3 Sep",
        "EEEE" to "Wednesday",
        "EEEE, d MMMM" to "Wednesday, 3 September",
        "d MMMM" to "3 September",
        "MMMM d" to "September 3",
        "MMM d" to "Sep 3",
        "d MMM yyyy" to "3 Sep 2026",
        "d.MM.yyyy" to "3.09.2026",
        "dd/MM/yyyy" to "03/09/2026",
        "yyyy.MM.dd" to "2026.09.03"
    )

    private fun add(t: Tk) {
        list.add(t)
        (byKey as HashMap<String, Tk>)[t.key] = t
    }

    private fun b(key: String, section: String, group: String, label: String, help: String,
                  def: Boolean, zone: String? = null, kw: List<String> = emptyList()) =
        add(Tk(key, section, group, label, help, TkKind.BOOL, if (def) "1" else "0", zone = zone, keywords = kw))

    private fun n(key: String, section: String, group: String, label: String, help: String,
                  def: Float, min: Float, max: Float, step: Float = 0.01f, unit: String = "",
                  fmt: ((Float) -> String)? = null, zone: String? = null, kw: List<String> = emptyList(),
                  advanced: Boolean = false) =
        add(Tk(key, section, group, label, help, TkKind.NUM, Tk.trimNum(def), min = min, max = max,
            step = step, unit = unit, fmt = fmt, zone = zone, keywords = kw, advanced = advanced))

    private fun c(key: String, section: String, group: String, label: String, help: String,
                  def: String, opts: List<Pair<String, String>>, zone: String? = null,
                  kw: List<String> = emptyList()) =
        add(Tk(key, section, group, label, help, TkKind.CHOICE, def,
            options = opts.map { TkOpt(it.first, it.second) }, zone = zone, keywords = kw))

    private fun txt(key: String, section: String, group: String, label: String, help: String,
                    def: String, kw: List<String> = emptyList()) =
        add(Tk(key, section, group, label, help, TkKind.TEXT, def, keywords = kw))

    init {
        val S_CLOCK = "Clock"
        val S_APPS = "Apps"
        val S_SEARCH = "Search"
        val S_RESULTS = "Results"
        val S_BG = "Background"
        val S_PALETTE = "Palette"
        val S_TYPE = "Typography"
        val S_MOTION = "Motion"
        val S_SYS = "System"

        // ── clock ──────────────────────────────────────────────────────────
        b(K.clockOn, S_CLOCK, "Visibility", "Show Clock", "The clock and date block on the home screen.", true,
            kw = listOf("time", "date", "hide", "visible"))
        c(K.clockStyle, S_CLOCK, "Type", "Typeface", "Named typographic treatments for the clock.",
            "plain", listOf(
                "plain" to "Plain",
                "hairline" to "Hairline",
                "condensed" to "Condensed",
                "serif" to "Serif",
                "mono" to "Mono",
                "wide" to "Wide"
            ), zone = "clock", kw = listOf("font", "type", "serif", "mono", "condensed"))
        c(K.clockFace, S_CLOCK, "Type", "Face", "The clock can be set in any of the four bundled faces, a system face, or a named family.",
            "auto", listOf(
                "auto" to "Follow typeface",
                "inter" to "Inter",
                "grotesk" to "Space Grotesk",
                "serif" to "Instrument Serif",
                "serifItalic" to "Instrument Serif Italic",
                "mono" to "JetBrains Mono",
                "system" to "System",
                "systemSerif" to "System serif",
                "systemMono" to "System mono",
                "systemCondensed" to "System condensed"
            ), zone = "clock", kw = listOf("font", "face", "typeface", "custom"))
        txt(K.clockCustomFace, S_CLOCK, "Type", "Font Family Name",
            "A family the system knows, e.g. 'serif-monospace' or 'sans-serif-condensed'. Unknown names fall back.",
            "", listOf("font", "custom", "family"))
        n(K.clockShadow, S_CLOCK, "Light", "Shadow", "A soft drop shadow that lifts the type off the surface.",
            0f, 0f, 1f, 0.01f, fmt = { (it * 100).toInt().toString() + "%" }, zone = "clock",
            kw = listOf("shadow", "drop", "depth", "light"))
        n(K.clockGlow, S_CLOCK, "Light", "Glow", "Coloured light bleeding out of the numerals.",
            0f, 0f, 1f, 0.01f, fmt = { (it * 100).toInt().toString() + "%" }, zone = "clock",
            kw = listOf("glow", "halo", "neon", "light"))
        c(K.clockFormat, S_CLOCK, "Time Format", "Pattern", "Sixteen ways to write the hour — drag the clock to scrub them all.",
            "h:mm", formats.map { it.first to it.second }, zone = "clock",
            kw = listOf("time", "24 hour", "12 hour", "seconds", "am", "pm"))
        txt(K.clockFormatCustom, S_CLOCK, "Time Format", "Custom Pattern",
            "Any java.text pattern. e.g. H:mm:ss.SS or h 'past' H.", "", listOf("pattern", "custom", "advanced"))
        c(K.clockDateFormat, S_CLOCK, "Date Format", "Pattern", "Sixteen ways to write the day.",
            "EEE, d MMMM", dateFormats.map { it.first to it.second }, zone = "clock",
            kw = listOf("date", "day", "month", "weekday"))
        txt(K.clockCustomDate, S_CLOCK, "Date Format", "Custom Pattern",
            "Any java.text pattern. e.g. d MMMM yyyy.", "", listOf("pattern", "custom", "date"))
        c(K.clockArrangement, S_CLOCK, "Layout", "Arrangement", "How date and time stack together.",
            "dateAbove", listOf(
                "time" to "Time only",
                "dateOnly" to "Date only",
                "dateAbove" to "Date above",
                "dateBelow" to "Date below",
                "inline" to "Inline · date first",
                "inlineReverse" to "Inline · time first"
            ), zone = "clock", kw = listOf("stack", "arrangement", "order"))
        c(K.clockAlign, S_CLOCK, "Layout", "Alignment", "Horizontal anchor of the clock block.",
            "start", listOf("start" to "Left", "center" to "Centre", "end" to "Right"),
            zone = "clock", kw = listOf("align", "left", "center", "right"))
        c(K.clockAnchor, S_CLOCK, "Layout", "Anchor", "Which corner of the screen the clock lives in.",
            "topStart", listOf(
                "topStart" to "Top left", "topCenter" to "Top centre", "topEnd" to "Top right",
                "centerStart" to "Middle left", "center" to "Middle centre", "centerEnd" to "Middle right",
                "bottomStart" to "Bottom left", "bottomCenter" to "Bottom centre", "bottomEnd" to "Bottom right"
            ), zone = "clock", kw = listOf("position", "place", "corner"))
        n(K.clockScale, S_CLOCK, "Size", "Scale", "Multiplies the clock's type size. Drag the clock's corner handle.",
            1f, 0.45f, 2.2f, 0.01f, "×", zone = "clock", kw = listOf("size", "big", "small", "scale"))
        n(K.clockWeight, S_CLOCK, "Type", "Weight", "Variable weight of the clock face.", 300f, 100f, 800f, 10f,
            fmt = { it.toInt().toString() }, zone = "clock", kw = listOf("weight", "bold", "thin", "light"))
        n(K.clockTracking, S_CLOCK, "Type", "Letter Spacing", "Negative tightens, positive opens the type.",
            -0.02f, -0.08f, 0.24f, 0.005f, "em", fmt = { String.format(Locale.US, "%+.2f em", it) },
            zone = "clock", kw = listOf("tracking", "spacing", "kern"))
        c(K.clockCase, S_CLOCK, "Type", "Case", "Text transform for date and meridiem.", "none",
            listOf("none" to "As written", "upper" to "UPPERCASE", "lower" to "lowercase"), zone = "clock",
            kw = listOf("case", "caps", "uppercase"))
        n(K.clockOpacity, S_CLOCK, "Type", "Opacity", "Full-strength or ghosted into the background.", 1f, 0.2f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "clock", kw = listOf("opacity", "fade", "dim"))
        n(K.clockDateOpacity, S_CLOCK, "Type", "Date Opacity", "Dates usually sit quieter than the time.",
            0.55f, 0.2f, 1f, 0.01f, fmt = { (it * 100).toInt().toString() + "%" }, zone = "clock",
            kw = listOf("opacity", "date", "dim"))
        b(K.clockTabular, S_CLOCK, "Type", "Tabular Figures", "Fixed-width digits so the clock never jitters.", true,
            zone = "clock", kw = listOf("mono", "digits", "jitter", "tabular"))
        b(K.clockColonBlink, S_CLOCK, "Time Format", "Blinking Colon", "A quiet heartbeat on the separator.", false,
            zone = "clock", kw = listOf("blink", "colon", "pulse"))
        b(K.clockSecondsTick, S_CLOCK, "Time Format", "Animated Digits", "Cross-fade each digit as it changes.", true,
            zone = "clock", kw = listOf("animate", "tick", "digits"))
        c(K.clockDivider, S_CLOCK, "Layout", "Inline Divider", "What sits between date and time when inline.",
            "middot", listOf("space" to "Space", "middot" to "·", "pipe" to "|", "slash" to "/",
                "endash" to "–", "emdash" to "—", "bullet" to "•"), zone = "clock", kw = listOf("divider", "separator"))
        n(K.clockDx, S_CLOCK, "Layout", "Offset X", "Fine horizontal nudge.", 0f, -0.4f, 0.4f, 0.002f,
            fmt = { String.format(Locale.US, "%+.0f%%", it * 100) }, zone = "clock", advanced = true)
        n(K.clockDy, S_CLOCK, "Layout", "Offset Y", "Fine vertical nudge.", 0f, -0.4f, 0.4f, 0.002f,
            fmt = { String.format(Locale.US, "%+.0f%%", it * 100) }, zone = "clock", advanced = true)
        n(K.clockDateScale, S_CLOCK, "Size", "Date Scale", "Relative size of the date line.", 0.34f, 0.16f, 1f, 0.005f,
            "×", fmt = { String.format(Locale.US, "%.2f×", it) }, zone = "clock", kw = listOf("date", "size"))

        // ── pins ───────────────────────────────────────────────────────────
        b(K.pinsOn, S_APPS, "Visibility", "Show Apps", "The pinned app block on the home screen.", true,
            zone = "pins", kw = listOf("apps", "hide", "show"))
        c(K.pinsFountain, S_APPS, "Style", "Hierarchy", "A quiet gradient of attention across the list.",
            "none", listOf(
                "none" to "Even",
                "fade" to "Fade down",
                "focus" to "Focus from anchor",
                "centre" to "Centre brightest"
            ), zone = "pins", kw = listOf("hierarchy", "fade", "focus", "dim", "emphasis"))
        b(K.pinsFadeIn, S_APPS, "Style", "Icon Bloom", "Icons materialise rather than appear.", true,
            zone = "pins", kw = listOf("icons", "fade", "reveal", "animation"))
        n(K.pinsCount, S_APPS, "Content", "App Count", "How many pinned apps to show.", 6f, 1f, 12f, 1f,
            fmt = { it.toInt().toString() }, zone = "pins", kw = listOf("count", "how many", "limit"))
        c(K.pinsLayout, S_APPS, "Layout", "Arrangement", "A stack, a wrapped row, a grid, or a single line.",
            "list", listOf(
                "list" to "Stack",
                "row" to "Row · wraps",
                "grid2" to "Grid · 2 columns",
                "grid3" to "Grid · 3 columns",
                "inline" to "Single line"
            ), zone = "pins", kw = listOf("layout", "grid", "row", "columns"))
        c(K.pinsAlign, S_APPS, "Layout", "Alignment", "Horizontal anchor of the app block.",
            "start", listOf("start" to "Left", "center" to "Centre", "end" to "Right"),
            zone = "pins", kw = listOf("align", "left", "center", "right"))
        c(K.pinsAnchor, S_APPS, "Layout", "Anchor", "Where the app block sits on the screen.",
            "center", listOf(
                "topStart" to "Top left", "topCenter" to "Top centre", "topEnd" to "Top right",
                "centerStart" to "Middle left", "center" to "Middle centre", "centerEnd" to "Middle right",
                "bottomStart" to "Bottom left", "bottomCenter" to "Bottom centre", "bottomEnd" to "Bottom right"
            ), zone = "pins", kw = listOf("position", "place"))
        b(K.pinsIcons, S_APPS, "Style", "Show Icons", "App icons beside each label.", false,
            zone = "pins", kw = listOf("icons", "glyph", "logo"))
        n(K.pinsIconSize, S_APPS, "Style", "Icon Size", "Diameter of the app icon.", 22f, 10f, 40f, 1f, "dp",
            fmt = { it.toInt().toString() + " dp" }, zone = "pins", kw = listOf("icon", "size"))
        c(K.pinsIconStyle, S_APPS, "Style", "Icon Treatment", "Monochrome icons blend into the type.",
            "mono", listOf("mono" to "Monochrome", "dim" to "Dimmed", "original" to "Original"),
            zone = "pins", kw = listOf("icon", "tint", "colour", "color"))
        b(K.pinsLabels, S_APPS, "Style", "Show Labels", "The app name text.", true,
            zone = "pins", kw = listOf("labels", "names", "text"))
        n(K.pinsSize, S_APPS, "Size", "Type Scale", "Size of the app labels relative to the base.", 1f, 0.6f, 1.8f,
            0.01f, "×", fmt = { String.format(Locale.US, "%.2f×", it) }, zone = "pins",
            kw = listOf("size", "text", "scale"))
        n(K.pinsWeight, S_APPS, "Style", "Weight", "Weight of the app labels.", 400f, 100f, 800f, 10f,
            fmt = { it.toInt().toString() }, zone = "pins", kw = listOf("weight", "bold"))
        n(K.pinsTracking, S_APPS, "Style", "Letter Spacing", "Tracking across app labels.", 0f, -0.06f, 0.2f, 0.005f,
            "em", fmt = { String.format(Locale.US, "%+.2f em", it) }, zone = "pins", kw = listOf("spacing", "kern"))
        n(K.pinsGap, S_APPS, "Layout", "Spacing", "Vertical rhythm between apps.", 0.55f, 0f, 2.4f, 0.01f,
            "×", fmt = { String.format(Locale.US, "%.2f×", it) }, zone = "pins", kw = listOf("gap", "density", "spacing"))
        n(K.pinsOpacity, S_APPS, "Style", "Opacity", "Overall presence of the app block.", 1f, 0.2f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "pins", kw = listOf("opacity", "dim"))
        c(K.pinsCase, S_APPS, "Style", "Case", "Text transform for app names.", "none",
            listOf("none" to "As written", "upper" to "UPPERCASE", "lower" to "lowercase", "small" to "Small caps"),
            zone = "pins", kw = listOf("case", "caps"))
        c(K.pinsStyle, S_APPS, "Style", "Label Style", "Plain names, monograms, or index numbers.",
            "plain", listOf("plain" to "Plain", "monogram" to "Monogram", "numbered" to "Numbered", "bracket" to "Bracketed"),
            zone = "pins", kw = listOf("style", "monogram", "number"))
        n(K.pinsDx, S_APPS, "Layout", "Offset X", "Fine horizontal nudge.", 0f, -0.4f, 0.4f, 0.002f,
            fmt = { String.format(Locale.US, "%+.0f%%", it * 100) }, zone = "pins", advanced = true)
        n(K.pinsDy, S_APPS, "Layout", "Offset Y", "Fine vertical nudge.", 0f, -0.4f, 0.4f, 0.002f,
            fmt = { String.format(Locale.US, "%+.0f%%", it * 100) }, zone = "pins", advanced = true)

        // ── search field ───────────────────────────────────────────────────
        c(K.searchStyle, S_SEARCH, "Field", "Field Style", "How the input announces itself.", "bare",
            listOf("bare" to "Bare", "underline" to "Underline", "pill" to "Pill", "box" to "Box", "rule" to "Hairline rule"),
            kw = listOf("input", "bar", "style", "underline", "pill"))
        n(K.searchSize, S_SEARCH, "Field", "Type Scale", "Size of the search text.", 1f, 0.6f, 1.8f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("size", "search", "text"))
        c(K.searchAlign, S_SEARCH, "Field", "Alignment", "Where the field sits horizontally.", "center",
            listOf("start" to "Left", "center" to "Centre", "end" to "Right"), kw = listOf("align", "left", "center"))
        txt(K.searchHint, S_SEARCH, "Field", "Placeholder", "The word shown when the field is empty.", "search",
            listOf("hint", "placeholder", "prompt"))
        c(K.searchHintCase, S_SEARCH, "Field", "Placeholder Case", "Transform for the placeholder only.", "none",
            listOf("none" to "As written", "upper" to "UPPERCASE", "lower" to "lowercase"), kw = listOf("case"))
        b(K.searchRule, S_SEARCH, "Field", "Hairline Rule", "A single rule above the field, drawn in ink.",
            false, kw = listOf("rule", "line", "divider"))
        c(K.searchGlyph, S_SEARCH, "Field", "Leading Glyph", "A mark before the field's text.", "none",
            listOf("none" to "None", "chevron" to "›", "slash" to "/", "search" to "⌕", "dot" to "●", "plus" to "+"),
            kw = listOf("glyph", "icon", "mark"))
        n(K.searchPad, S_SEARCH, "Field", "Bottom Inset", "Distance from the bottom edge.", 1f, 0.2f, 2.5f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("padding", "inset", "bottom"))
        n(K.searchOpacity, S_SEARCH, "Field", "Opacity", "Presence of the field.", 1f, 0.2f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, kw = listOf("opacity"))

        // ── results ────────────────────────────────────────────────────────
        n(K.listSize, S_RESULTS, "List", "Type Scale", "Size of result rows.", 1f, 0.6f, 1.7f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("results", "size", "text"))
        c(K.listAlign, S_RESULTS, "List", "Alignment", "Horizontal alignment of result rows.", "center",
            listOf("start" to "Left", "center" to "Centre", "end" to "Right"), kw = listOf("align"))
        b(K.listIcons, S_RESULTS, "Detail", "Show Icons", "Icons beside search results.", true,
            kw = listOf("icons", "results"))
        n(K.listIconSize, S_RESULTS, "Detail", "Icon Size", "Result icon diameter.", 20f, 10f, 36f, 1f, "dp",
            fmt = { it.toInt().toString() + " dp" }, kw = listOf("icon", "size"))
        c(K.listLabelStyle, S_RESULTS, "Detail", "Row Content", "What each row shows.", "label",
            listOf("label" to "Label", "labelSub" to "Label · detail", "subOnly" to "Detail only"),
            kw = listOf("subtitle", "row", "content"))
        n(K.listRowGap, S_RESULTS, "List", "Row Spacing", "Rhythm between results.", 0.5f, 0f, 2f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("gap", "density"))
        n(K.listDim, S_RESULTS, "List", "Secondary Dim", "How far non-focused rows recede.", 0.45f, 0.1f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, kw = listOf("dim", "focus", "hierarchy"))
        b(K.listHeader, S_RESULTS, "List", "Column Headers", "The small caps title above each column.", true,
            kw = listOf("header", "title", "caption"))
        n(K.listHeaderSize, S_RESULTS, "List", "Header Size", "Size of column headers.", 0.55f, 0.3f, 1f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("header", "size"))
        b(K.listStagger, S_RESULTS, "List", "Staggered Reveal", "Rows rise into place one after another.", true,
            kw = listOf("animation", "stagger", "reveal"))
        n(K.listMaxRows, S_RESULTS, "List", "Max Rows", "Upper bound on rendered results per column.", 12f, 4f, 24f, 1f,
            fmt = { it.toInt().toString() }, kw = listOf("limit", "rows", "count"))
        n(K.listTracking, S_RESULTS, "List", "Letter Spacing", "Tracking across results.", -0.01f, -0.06f, 0.16f, 0.005f,
            "em", fmt = { String.format(Locale.US, "%+.2f em", it) }, kw = listOf("spacing"))

        // ── background ─────────────────────────────────────────────────────
        c(K.bgMode, S_BG, "Surface", "Atmosphere", "The material the home screen is made of.", "void",
            listOf(
                "void" to "Void · pure black",
                "solid" to "Solid tone",
                "gradient" to "Gradient",
                "aurora" to "Aurora",
                "wallpaper" to "Wallpaper"
            ), zone = "bg", kw = listOf("wallpaper", "background", "gradient", "black", "colour"))
        n(K.bgTone, S_BG, "Surface", "Depth", "How much light is left in the surface.", 0.06f, 0f, 0.7f, 0.005f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "bg", kw = listOf("dark", "light", "tone", "depth"))
        n(K.bgHue, S_BG, "Surface", "Hue", "Temperature of the surface tint.", 258f, 0f, 360f, 1f,
            fmt = { it.toInt().toString() + "°" }, zone = "bg", kw = listOf("hue", "colour", "color", "tint"))
        n(K.bgSaturation, S_BG, "Surface", "Saturation", "How coloured the surface is.", 0.35f, 0f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "bg", kw = listOf("saturation", "colour"))
        n(K.bgGradientAngle, S_BG, "Surface", "Gradient Angle", "Direction of the surface gradient.", 90f, 0f, 360f, 1f,
            fmt = { it.toInt().toString() + "°" }, zone = "bg", kw = listOf("gradient", "angle"))
        n(K.bgGlow, S_BG, "Light", "Glow", "Ambient light pooling behind the type.", 0.3f, 0f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "bg", kw = listOf("glow", "aurora", "light"))
        n(K.bgVignette, S_BG, "Light", "Vignette", "Darkening toward the edges.", 0f, 0f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, zone = "bg", kw = listOf("vignette", "edge", "dark"))
        n(K.bgGrain, S_BG, "Texture", "Grain", "A whisper of film grain — kills the flatness of pure black.",
            0.06f, 0f, 0.6f, 0.005f, fmt = { (it * 100).toInt().toString() + "%" }, zone = "bg",
            kw = listOf("grain", "noise", "texture", "film"))
        b(K.bgMotion, S_BG, "Light", "Living Light", "The aurora drifts, very slowly.", false, zone = "bg",
            kw = listOf("animate", "drift", "motion", "aurora"))
        txt(K.bgWallpaper, S_BG, "Wallpaper", "Image", "Chosen from the device picker.", "", listOf("wallpaper", "image", "photo"))
        n(K.bgWallpaperDim, S_BG, "Wallpaper", "Dim", "Darkening applied over the wallpaper.", 0.68f, 0f, 0.95f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, kw = listOf("wallpaper", "dim", "dark"))
        n(K.bgWallpaperBlur, S_BG, "Wallpaper", "Blur", "Optical blur applied over the wallpaper.", 22f, 0f, 60f, 1f, "dp",
            fmt = { it.toInt().toString() + " dp" }, kw = listOf("wallpaper", "blur"))

        // ── palette ────────────────────────────────────────────────────────
        n(K.palHue, S_PALETTE, "Light", "Hue", "The colour the whole surface leans toward.", 0f, 0f, 360f, 1f,
            fmt = { it.toInt().toString() + "°" }, kw = listOf("colour", "color", "hue", "tint", "theme"))
        n(K.palSat, S_PALETTE, "Light", "Tint", "How much colour survives. Zero gives you pure monochrome.",
            0f, 0f, 1f, 0.01f, fmt = { (it * 100).toInt().toString() + "%" },
            kw = listOf("saturation", "mono", "grey", "gray", "colour", "tint"))
        n(K.palWarm, S_PALETTE, "Light", "Warmth", "Cool blue-white through neutral to warm paper.",
            0f, -1f, 1f, 0.01f, fmt = { String.format(Locale.US, "%+.2f", it) },
            kw = listOf("warm", "cool", "temperature", "white", "paper", "blue"))

        // ── typography ─────────────────────────────────────────────────────
        c(K.typeFace, S_TYPE, "Voice", "Interface Face", "One face for everything that isn't the clock.",
            "inter", listOf(
                "inter" to "Inter",
                "grotesk" to "Space Grotesk",
                "serif" to "Instrument Serif",
                "serifItalic" to "Instrument Serif Italic",
                "mono" to "JetBrains Mono",
                "system" to "System",
                "systemSerif" to "System serif",
                "systemMono" to "System mono"
            ), kw = listOf("font", "face", "typeface", "voice", "all"))
        n(K.typeGutter, S_TYPE, "Layout", "Gutter", "The air between the type and the edge of the screen.",
            1f, 0.5f, 2f, 0.01f, "×", fmt = { String.format(Locale.US, "%.2f×", it) },
            kw = listOf("margin", "padding", "edge", "gutter", "air"))
        n(K.typeScale, S_TYPE, "Scale", "Global Scale", "Multiplies every type size at once.", 1f, 0.8f, 1.3f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("size", "global", "scale", "all"))
        n(K.typeTone, S_TYPE, "Tone", "Ink Brightness", "Pure white or a softer ink.", 1f, 0.55f, 1f, 0.01f,
            fmt = { (it * 100).toInt().toString() + "%" }, kw = listOf("white", "ink", "brightness", "text"))
        n(K.typeHue, S_TYPE, "Tone", "Ink Hue", "Warm, cool, or neutral ink.", 0f, 0f, 360f, 1f,
            fmt = { it.toInt().toString() + "°" }, kw = listOf("ink", "colour", "warm", "cool"))
        n(K.typeWeightShift, S_TYPE, "Tone", "Weight Shift", "Offset added to every weight.", 0f, -200f, 200f, 10f,
            fmt = { String.format(Locale.US, "%+.0f", it) }, kw = listOf("weight", "bold"))

        // ── motion ─────────────────────────────────────────────────────────
        n(K.motionSpeed, S_MOTION, "Timing", "Tempo", "Durations across the launcher.", 1f, 0.5f, 1.8f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("speed", "animation", "fast", "slow"))
        n(K.motionSpring, S_MOTION, "Feel", "Springiness", "How much the interface overshoots before settling.", 0.62f,
            0.2f, 1f, 0.01f, fmt = { (it * 100).toInt().toString() + "%" }, kw = listOf("spring", "bounce", "feel"))
        b(K.motionBlur, S_MOTION, "Focus", "Optical Blur", "Blur the surface when launching an app.", true,
            kw = listOf("blur", "depth", "focus"))
        n(K.motionBlurAmount, S_MOTION, "Focus", "Blur Amount", "How soft the defocus gets.", 22f, 4f, 48f, 1f, "px",
            fmt = { it.toInt().toString() + " px" }, kw = listOf("blur", "amount"))
        n(K.motionDepth, S_MOTION, "Feel", "Depth", "Parallax and scale between columns.", 1f, 0f, 2f, 0.01f, "×",
            fmt = { String.format(Locale.US, "%.2f×", it) }, kw = listOf("depth", "parallax", "scale"))
        b(K.motionHaptics, S_MOTION, "Feel", "Haptics", "Ticks when things snap into place.", true,
            kw = listOf("haptics", "vibrate", "tick"))
        c(K.motionEntrance, S_MOTION, "Timing", "Entrance", "How the home screen arrives.", "rise",
            listOf("rise" to "Rise", "fade" to "Fade", "mask" to "Mask", "none" to "None"),
            kw = listOf("entrance", "intro", "arrival"))
        b(K.motionStagger, S_MOTION, "Timing", "Stagger", "Choreograph elements instead of moving them together.", true,
            kw = listOf("stagger", "choreography"))

        // ── system ─────────────────────────────────────────────────────────
        b(K.sysStatusBar, S_SYS, "Bars", "Show Status Bar", "The system bar at the top of the screen.", false,
            kw = listOf("status", "bar", "system"))
        b(K.sysNavBar, S_SYS, "Bars", "Show Navigation Bar", "The gesture/navigation bar at the bottom.", false,
            kw = listOf("navigation", "bar", "gesture"))
        b(K.sysColumnContacts, S_SYS, "Search", "Contacts Column", "Show a contacts column in search.", true,
            kw = listOf("contacts", "column"))
        b(K.sysColumnWeb, S_SYS, "Search", "Web Column", "Offer a web search fallback.", true,
            kw = listOf("web", "search", "google"))
    }
}

/**
 * Named starting points. Each preset is a sparse patch of tokens; applying one
 * cross-fades the entire surface from its current state.
 */
class Preset(val id: String, val name: String, val note: String, val patch: Map<String, String>)

object Presets {
    val all: List<Preset> = listOf(
        Preset(
            "vitrine", "Vitrine", "The house default — date and time top-left, apps at rest in the middle.",
            mapOf(
                K.clockArrangement to "dateAbove", K.clockStyle to "plain", K.clockWeight to "300",
                K.clockScale to "1", K.clockAnchor to "topStart", K.clockAlign to "start",
                K.pinsLayout to "list", K.pinsAnchor to "center", K.pinsAlign to "start",
                K.pinsIcons to "0", K.bgMode to "void", K.searchStyle to "bare"
            )
        ),
        Preset(
            "broadsheet", "Broadsheet", "A serif masthead, oversized, with a rule under the date.",
            mapOf(
                K.clockStyle to "serif", K.clockWeight to "400", K.clockScale to "1.45",
                K.clockAnchor to "topCenter", K.clockAlign to "center", K.clockArrangement to "dateBelow",
                K.clockTracking to "-0.01", K.clockDateFormat to "EEEE, d MMMM",
                K.pinsLayout to "inline", K.pinsAnchor to "bottomCenter", K.pinsAlign to "center",
                K.pinsCase to "upper", K.pinsSize to "0.8", K.pinsTracking to "0.14",
                K.bgMode to "gradient", K.bgTone to "0.09", K.bgHue to "32", K.bgSaturation to "0.22",
                K.searchStyle to "rule", K.searchAlign to "center"
            )
        ),
        Preset(
            "nocturne", "Nocturne", "Only the hour, enormous and centred, floating in black.",
            mapOf(
                K.clockStyle to "hairline", K.clockFormat to "HH:mm", K.clockScale to "1.9",
                K.clockAnchor to "center", K.clockAlign to "center", K.clockArrangement to "time",
                K.clockTracking to "0.06", K.clockOpacity to "0.92",
                K.pinsOn to "1", K.pinsAnchor to "bottomCenter", K.pinsAlign to "center",
                K.pinsLayout to "inline", K.pinsSize to "0.72", K.pinsOpacity to "0.7",
                K.bgMode to "aurora", K.bgGlow to "0.42", K.bgHue to "258", K.bgGrain to "0.12",
                K.searchStyle to "underline", K.searchAlign to "center"
            )
        ),
        Preset(
            "terminal", "Terminal", "Monospaced, left-aligned, dense. For people who type first.",
            mapOf(
                K.clockStyle to "mono", K.clockFormat to "HH:mm:ss", K.clockScale to "0.8",
                K.clockAnchor to "topStart", K.clockAlign to "start", K.clockArrangement to "inlineReverse",
                K.clockTracking to "0", K.clockDivider to "pipe", K.clockCase to "lower",
                K.pinsLayout to "list", K.pinsAnchor to "centerStart", K.pinsAlign to "start",
                K.pinsStyle to "bracket", K.pinsSize to "0.82", K.pinsGap to "0.2",
                K.searchStyle to "bare", K.searchAlign to "start", K.searchGlyph to "chevron",
                K.bgMode to "solid", K.bgTone to "0.04", K.bgHue to "150", K.bgSaturation to "0.5",
                K.listAlign to "start", K.listHeader to "0"
            )
        ),
        Preset(
            "index", "Index", "A small clock, a grid of monograms. The screen as a contents page.",
            mapOf(
                K.clockStyle to "condensed", K.clockFormat to "h:mm", K.clockScale to "0.62",
                K.clockAnchor to "topCenter", K.clockAlign to "center", K.clockArrangement to "inline",
                K.pinsLayout to "grid3", K.pinsAnchor to "center", K.pinsAlign to "center",
                K.pinsStyle to "monogram", K.pinsLabels to "0", K.pinsIcons to "0",
                K.pinsGap to "1.4", K.pinsCount to "9",
                K.bgMode to "gradient", K.bgTone to "0.07", K.bgHue to "210", K.bgSaturation to "0.3",
                K.searchStyle to "pill", K.searchAlign to "center"
            )
        ),
        Preset(
            "quiet", "Quiet", "Almost nothing: time, three apps, a hint of light.",
            mapOf(
                K.clockStyle to "plain", K.clockScale to "0.9", K.clockWeight to "200",
                K.clockAnchor to "topStart", K.clockAlign to "start", K.clockArrangement to "dateAbove",
                K.clockOpacity to "0.85", K.clockDateOpacity to "0.4",
                K.pinsCount to "3", K.pinsLayout to "list", K.pinsAnchor to "bottomStart",
                K.pinsAlign to "start", K.pinsOpacity to "0.8", K.pinsSize to "0.9",
                K.bgMode to "gradient", K.bgTone to "0.03", K.bgHue to "240", K.bgSaturation to "0.6",
                K.bgGrain to "0.1", K.searchStyle to "bare", K.searchAlign to "start",
                K.searchGlyph to "none"
            )
        )
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }
}

/**
 * Workbenches start from nothing rather than from the house default: a blank
 * sheet, one material, one clock, a handful of apps. They exist so you can
 * begin from a clean surface instead of carving away a preset.
 */
object Workbenches {
    class Wb(val name: String, val note: String, val patch: Map<String, String>)
    val all: List<Wb> = listOf(
        Wb("Blank slate", "Everything off. Pure black, one clock, nothing else.", mapOf(
            K.pinsOn to "0", K.clockOn to "1",
            K.clockArrangement to "time", K.clockFormat to "HH:mm", K.clockScale to "1.1",
            K.clockAnchor to "center", K.clockAlign to "center", K.clockStyle to "hairline",
            K.clockWeight to "200", K.clockTracking to "0.02", K.clockOpacity to "0.9",
            K.clockDateOpacity to "0.4", K.clockDateFormat to "EEEE",
            K.bgMode to "void", K.bgGrain to "0.05", K.bgGlow to "0",
            K.searchStyle to "bare", K.searchAlign to "center", K.searchPad to "1"
        )),
        Wb("One word", "A single enormous hour. No apps, no date, no chrome.", mapOf(
            K.pinsOn to "0", K.clockOn to "1",
            K.clockArrangement to "time", K.clockFormat to "H",
            K.clockScale to "2.2", K.clockWeight to "100", K.clockTracking to "-0.02",
            K.clockAnchor to "center", K.clockAlign to "center", K.clockStyle to "hairline",
            K.clockOpacity to "0.85", K.bgMode to "void", K.bgGrain to "0.08",
            K.searchStyle to "bare", K.searchPad to "1.4"
        )),
        Wb("Ledger", "Names only, tightly ruled, nothing decorative.", mapOf(
            K.clockStyle to "mono", K.clockFormat to "HH:mm", K.clockScale to "0.55",
            K.clockAnchor to "bottomStart", K.clockAlign to "start",
            K.clockArrangement to "inline", K.clockDivider to "pipe", K.clockCase to "lower",
            K.clockTracking to "0", K.clockDateOpacity to "0.3",
            K.pinsLayout to "list", K.pinsAnchor to "topStart", K.pinsAlign to "start",
            K.pinsSize to "0.85", K.pinsGap to "0.25", K.pinsCase to "lower",
            K.pinsIcons to "0", K.pinsCount to "8",
            K.bgMode to "solid", K.bgTone to "0.03", K.bgSaturation to "0.2", K.palHue to "160",
            K.palSat to "0.35", K.searchStyle to "bare", K.searchAlign to "start",
            K.searchGlyph to "chevron", K.listAlign to "start", K.listHeader to "0",
            K.typeFace to "mono"
        )),
        Wb("Poster", "A serif headline over a wide gutter, apps as a single line.", mapOf(
            K.clockStyle to "serif", K.clockWeight to "400", K.clockScale to "1.5",
            K.clockAnchor to "centerStart", K.clockAlign to "start", K.clockArrangement to "dateAbove",
            K.clockDateFormat to "EEEE, d MMMM", K.clockTracking to "-0.005", K.clockDateScale to "0.3",
            K.pinsLayout to "inline", K.pinsAnchor to "bottomStart", K.pinsAlign to "start",
            K.pinsCase to "upper", K.pinsSize to "0.72", K.pinsTracking to "0.16", K.pinsGap to "0.9",
            K.pinsCount to "3", K.pinsOpacity to "0.8",
            K.bgMode to "gradient", K.bgTone to "0.1", K.bgHue to "24", K.bgSaturation to "0.28",
            K.palHue to "30", K.palSat to "0.25", K.palWarm to "0.6",
            K.typeGutter to "1.4", K.searchStyle to "rule", K.searchAlign to "start"
        ))
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  FORMATTING (shared by the clock view and the settings deck previews)
// ════════════════════════════════════════════════════════════════════════════

object Fmt {

    fun patternFor(tokens: Tokens): String {
        val custom = tokens.s(K.clockFormatCustom)
        return custom.ifBlank { tokens.s(K.clockFormat) }
    }

    fun datePatternFor(tokens: Tokens): String {
        val custom = tokens.s(K.clockCustomDate)
        return custom.ifBlank { tokens.s(K.clockDateFormat) }
    }

    private val cache = HashMap<String, SimpleDateFormat>(8)

    fun format(pattern: String, now: Date = Date(), locale: Locale = Locale.getDefault()): String =
        try {
            val sdf = cache[pattern] ?: SimpleDateFormat(pattern, locale).also {
                if (cache.size > 16) cache.clear()
                cache[pattern] = it
            }
            sdf.format(now)
        } catch (e: Exception) {
            "--"
        }

    fun divider(id: String): String = when (id) {
        "space" -> "  "
        "middot" -> " · "
        "pipe" -> " | "
        "slash" -> " / "
        "endash" -> " – "
        "emdash" -> " — "
        "bullet" -> " • "
        else -> " · "
    }
}
