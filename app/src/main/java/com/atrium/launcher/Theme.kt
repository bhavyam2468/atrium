package com.atrium.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
   Atrium design tokens.

   One observable source of truth for every visual property of the launcher.
   The home screen, the settings tree and the design studio all read and
   write here, so a change made anywhere shows up everywhere, live.

   A token is a key with a type, a default and a range. Values persist to
   SharedPreferences individually and snapshot to JSON for undo history.
   ============================================================================ */

/* ----------------------------- typefaces -------------------------------- */

internal data class FontSpec(val name: String, val res: Int?, val stack: String?)

internal object Fonts {
    const val SYSTEM = 0
    const val INTER = 1
    const val GROTESK = 2
    const val FRAUNCES = 3
    const val MONO = 4
    const val SERIF = 5

    val specs = listOf(
        FontSpec("System", null, Typeface.SANS_SERIF),
        FontSpec("Inter", R.font.inter_variable, null),
        FontSpec("Space Grotesk", R.font.space_grotesk_variable, null),
        FontSpec("Fraunces", R.font.fraunces_variable, null),
        FontSpec("JetBrains Mono", R.font.jetbrains_mono_variable, null),
        FontSpec("Serif", null, Typeface.SERIF),
    )

    private val baseCache = HashMap<Int, Typeface>()
    private val weightCache = HashMap<Long, Typeface>()

    fun base(ctx: Context, i: Int): Typeface {
        val idx = i.coerceIn(0, specs.size - 1)
        baseCache[idx]?.let { return it }
        val s = specs[idx]
        val tf = if (s.res != null) ctx.resources.getFont(s.res!!)
            else Typeface.create(s.stack!!, Typeface.NORMAL)
        baseCache[idx] = tf
        return tf
    }

    /** Weighted instance of a (variable-aware) family; cached per 100-step. */
    fun weighted(ctx: Context, i: Int, weight: Int): Typeface {
        val f = i.coerceIn(0, specs.size - 1)
        val w = weight.coerceIn(100, 900) / 100
        val key = f * 16L + w
        weightCache[key]?.let { return it }
        val tf = Typeface.create(base(ctx, f), w * 100, false)
        weightCache[key] = tf
        return tf
    }
}

/* ------------------------------- keys ----------------------------------- */

internal object Tk {
    // typography
    const val FAMILY = "family"
    const val UI_WEIGHT = "ui_weight"
    const val UI_SIZE = "ui_size"
    const val TRACKING = "tracking"

    // clock
    const val CLOCK_FAMILY = "clock_family"
    const val CLOCK_WEIGHT = "clock_weight"
    const val CLOCK_SCALE = "clock_scale"
    const val CLOCK_TRACKING = "clock_tracking"
    const val TIME_FORMAT = "time_format"
    const val DATE_FORMAT = "date_format"
    const val ARRANGEMENT = "clock_arrangement"
    const val SEPARATOR = "clock_separator"
    const val DATE_UPPER = "date_upper"
    const val DATE_SCALE = "date_scale"
    const val DATE_WEIGHT = "date_weight"
    const val DATE_TRACKING = "date_tracking"

    // home layout
    const val ALIGN_X = "home_align_x"
    const val ANCHOR_Y = "home_anchor_y"
    const val SIDE_INSET = "side_inset"
    const val CLOCK_GAP = "clock_gap"
    const val PINS_VISIBLE = "pins_visible"
    const val PINS_SIZE = "pins_size"
    const val PINS_ALIGN = "pins_align"

    // apps & results
    const val ROW_STYLE = "row_style"
    const val ICON_SIZE = "icon_size"
    const val ICON_SHAPE = "icon_shape"
    const val ICON_MONO = "icon_mono"
    const val ROW_DENSITY = "row_density"
    const val RESULT_ALIGN = "result_align"
    const val SHOW_SUBTITLES = "show_subtitles"
    const val SHOW_HEADERS = "show_headers"
    const val COLUMN_WIDTH = "column_width"

    // search bar
    const val SEARCH_STYLE = "search_style"
    const val SEARCH_SIZE = "search_size"

    // colour & background
    const val ACCENT = "accent"
    const val TINT_TEXT = "tint_text"
    const val BRIGHTNESS = "brightness"
    const val BACKGROUND = "background_mode"
    const val WALLPAPER_DIM = "wallpaper_dim"
}

internal enum class TokType { INT, FLOAT, BOOL }

internal class TokDef(
    val key: String,
    val type: TokType,
    val def: Any,
    val min: Double,
    val max: Double
)

internal val TOKEN_DEFS: LinkedHashMap<String, TokDef> = linkedMapOf(
    Tk.FAMILY to TokDef(Tk.FAMILY, TokType.INT, Fonts.GROTESK, 0.0, 5.0),
    Tk.UI_WEIGHT to TokDef(Tk.UI_WEIGHT, TokType.INT, 470, 300.0, 800.0),
    Tk.UI_SIZE to TokDef(Tk.UI_SIZE, TokType.FLOAT, 19.0, 13.0, 30.0),
    Tk.TRACKING to TokDef(Tk.TRACKING, TokType.FLOAT, 0.0, -0.04, 0.12),

    Tk.CLOCK_FAMILY to TokDef(Tk.CLOCK_FAMILY, TokType.INT, -1, -1.0, 5.0),
    Tk.CLOCK_WEIGHT to TokDef(Tk.CLOCK_WEIGHT, TokType.INT, 300, 100.0, 900.0),
    Tk.CLOCK_SCALE to TokDef(Tk.CLOCK_SCALE, TokType.FLOAT, 3.6, 1.6, 6.0),
    Tk.CLOCK_TRACKING to TokDef(Tk.CLOCK_TRACKING, TokType.FLOAT, -0.015, -0.06, 0.1),
    Tk.TIME_FORMAT to TokDef(Tk.TIME_FORMAT, TokType.INT, 0, 0.0, 4.0),
    Tk.DATE_FORMAT to TokDef(Tk.DATE_FORMAT, TokType.INT, 0, 0.0, 8.0),
    Tk.ARRANGEMENT to TokDef(Tk.ARRANGEMENT, TokType.INT, 0, 0.0, 2.0),
    Tk.SEPARATOR to TokDef(Tk.SEPARATOR, TokType.INT, 0, 0.0, 4.0),
    Tk.DATE_UPPER to TokDef(Tk.DATE_UPPER, TokType.BOOL, true, 0.0, 0.0),
    Tk.DATE_SCALE to TokDef(Tk.DATE_SCALE, TokType.FLOAT, 1.15, 0.5, 2.0),
    Tk.DATE_WEIGHT to TokDef(Tk.DATE_WEIGHT, TokType.INT, 440, 100.0, 900.0),
    Tk.DATE_TRACKING to TokDef(Tk.DATE_TRACKING, TokType.FLOAT, 0.12, -0.02, 0.3),

    Tk.ALIGN_X to TokDef(Tk.ALIGN_X, TokType.INT, 1, 0.0, 2.0),
    Tk.ANCHOR_Y to TokDef(Tk.ANCHOR_Y, TokType.FLOAT, 0.2, 0.02, 0.95),
    Tk.SIDE_INSET to TokDef(Tk.SIDE_INSET, TokType.FLOAT, 22.0, 8.0, 56.0),
    Tk.CLOCK_GAP to TokDef(Tk.CLOCK_GAP, TokType.FLOAT, 30.0, 0.0, 96.0),
    Tk.PINS_VISIBLE to TokDef(Tk.PINS_VISIBLE, TokType.BOOL, true, 0.0, 0.0),
    Tk.PINS_SIZE to TokDef(Tk.PINS_SIZE, TokType.FLOAT, 1.5, 0.8, 2.4),
    Tk.PINS_ALIGN to TokDef(Tk.PINS_ALIGN, TokType.INT, -1, -1.0, 2.0),

    Tk.ROW_STYLE to TokDef(Tk.ROW_STYLE, TokType.INT, 0, 0.0, 2.0),
    Tk.ICON_SIZE to TokDef(Tk.ICON_SIZE, TokType.FLOAT, 22.0, 14.0, 40.0),
    Tk.ICON_SHAPE to TokDef(Tk.ICON_SHAPE, TokType.INT, 0, 0.0, 3.0),
    Tk.ICON_MONO to TokDef(Tk.ICON_MONO, TokType.BOOL, false, 0.0, 0.0),
    Tk.ROW_DENSITY to TokDef(Tk.ROW_DENSITY, TokType.FLOAT, 1.0, 0.5, 2.2),
    Tk.RESULT_ALIGN to TokDef(Tk.RESULT_ALIGN, TokType.INT, 0, 0.0, 2.0),
    Tk.SHOW_SUBTITLES to TokDef(Tk.SHOW_SUBTITLES, TokType.BOOL, true, 0.0, 0.0),
    Tk.SHOW_HEADERS to TokDef(Tk.SHOW_HEADERS, TokType.BOOL, true, 0.0, 0.0),
    Tk.COLUMN_WIDTH to TokDef(Tk.COLUMN_WIDTH, TokType.FLOAT, 0.85, 0.55, 0.95),

    Tk.SEARCH_STYLE to TokDef(Tk.SEARCH_STYLE, TokType.INT, 0, 0.0, 2.0),
    Tk.SEARCH_SIZE to TokDef(Tk.SEARCH_SIZE, TokType.FLOAT, 26.0, 16.0, 40.0),

    Tk.ACCENT to TokDef(Tk.ACCENT, TokType.INT, 0, 0.0, 6.0),
    Tk.TINT_TEXT to TokDef(Tk.TINT_TEXT, TokType.BOOL, false, 0.0, 0.0),
    Tk.BRIGHTNESS to TokDef(Tk.BRIGHTNESS, TokType.FLOAT, 1.0, 0.45, 1.0),
    Tk.BACKGROUND to TokDef(Tk.BACKGROUND, TokType.INT, 0, 0.0, 1.0),
    Tk.WALLPAPER_DIM to TokDef(Tk.WALLPAPER_DIM, TokType.FLOAT, 0.55, 0.0, 0.95),
)

/* --------------------------- format presets ----------------------------- */

internal val TIME_FORMATS = listOf("h:mm", "h:mm a", "H:mm", "h:mm:ss a", "H:mm:ss")
internal val DATE_FORMATS = listOf(
    "EEE, d MMMM", "EEEE, d MMMM", "d MMMM", "EEE d MMM", "MMMM d",
    "EEE, MMM d", "d/M/yyyy", "EEEE", ""
)
internal const val DATE_HIDDEN = 8
internal val SEPARATORS = listOf(" · ", " | ", " — ", "  ", ", ")
internal val ACCENTS = listOf(
    "Snow" to 0xFFFFFFFF.toInt(),
    "Paper" to 0xFFF1E9DC.toInt(),
    "Amber" to 0xFFF5C168.toInt(),
    "Rose" to 0xFFFF8A9B.toInt(),
    "Mint" to 0xFF7FE7B4.toInt(),
    "Sky" to 0xFF86C5FF.toInt(),
    "Violet" to 0xFFC0A7FF.toInt(),
)

internal fun patternExample(pattern: String): String =
    try { SimpleDateFormat(pattern, Locale.getDefault()).format(Date()) } catch (_: Exception) { pattern }

/* ------------------------------ presets --------------------------------- */

internal class Preset(val name: String, val values: Map<String, Any>)

internal val PRESETS = listOf(
    Preset("Atrium", mapOf(
        Tk.FAMILY to Fonts.GROTESK, Tk.CLOCK_FAMILY to -1, Tk.CLOCK_SCALE to 3.6f,
        Tk.CLOCK_WEIGHT to 300, Tk.UI_SIZE to 19f, Tk.UI_WEIGHT to 470,
        Tk.ARRANGEMENT to 0, Tk.DATE_FORMAT to 0, Tk.TIME_FORMAT to 0,
        Tk.ALIGN_X to 1, Tk.ANCHOR_Y to 0.2f, Tk.ROW_STYLE to 0,
        Tk.SEARCH_STYLE to 0, Tk.BACKGROUND to 0, Tk.ACCENT to 0,
        Tk.PINS_VISIBLE to true, Tk.PINS_ALIGN to -1, Tk.RESULT_ALIGN to 0,
        Tk.DATE_UPPER to true, Tk.DATE_SCALE to 1.15f, Tk.DATE_TRACKING to 0.12f,
        Tk.ROW_DENSITY to 1.0f, Tk.TRACKING to 0.0f, Tk.BRIGHTNESS to 1.0f,
        Tk.TINT_TEXT to false, Tk.PINS_SIZE to 1.5f, Tk.SHOW_HEADERS to true,
        Tk.SHOW_SUBTITLES to true, Tk.COLUMN_WIDTH to 0.85f, Tk.CLOCK_GAP to 30f,
        Tk.SIDE_INSET to 22f, Tk.SEARCH_SIZE to 26f, Tk.SEARCH_STYLE to 0,
        Tk.CLOCK_TRACKING to -0.015f, Tk.DATE_WEIGHT to 440,
    )),
    Preset("Editorial", mapOf(
        Tk.FAMILY to Fonts.INTER, Tk.CLOCK_FAMILY to Fonts.FRAUNCES,
        Tk.CLOCK_SCALE to 4.4f, Tk.CLOCK_WEIGHT to 380, Tk.CLOCK_TRACKING to -0.01f,
        Tk.DATE_UPPER to true, Tk.DATE_TRACKING to 0.22f, Tk.DATE_SCALE to 1.05f,
        Tk.DATE_WEIGHT to 420, Tk.ARRANGEMENT to 0, Tk.DATE_FORMAT to 0,
        Tk.ALIGN_X to 0, Tk.ANCHOR_Y to 0.14f, Tk.UI_SIZE to 18f, Tk.UI_WEIGHT to 500,
        Tk.RESULT_ALIGN to 0, Tk.PINS_SIZE to 1.4f, Tk.PINS_ALIGN to 0,
        Tk.ROW_STYLE to 0, Tk.ROW_DENSITY to 1.0f, Tk.SEARCH_STYLE to 0,
    )),
    Preset("Terminal", mapOf(
        Tk.FAMILY to Fonts.MONO, Tk.CLOCK_FAMILY to Fonts.MONO,
        Tk.CLOCK_SCALE to 3.0f, Tk.CLOCK_WEIGHT to 500, Tk.CLOCK_TRACKING to 0.0f,
        Tk.ARRANGEMENT to 2, Tk.SEPARATOR to 0, Tk.DATE_UPPER to false,
        Tk.DATE_SCALE to 1.0f, Tk.DATE_TRACKING to 0.0f, Tk.TIME_FORMAT to 2,
        Tk.UI_SIZE to 16f, Tk.UI_WEIGHT to 400, Tk.TRACKING to 0.02f,
        Tk.SEARCH_STYLE to 1, Tk.RESULT_ALIGN to 0, Tk.ROW_DENSITY to 0.9f,
        Tk.ALIGN_X to 0, Tk.ANCHOR_Y to 0.16f, Tk.PINS_ALIGN to 0,
    )),
    Preset("Poster", mapOf(
        Tk.FAMILY to Fonts.INTER, Tk.CLOCK_FAMILY to Fonts.INTER,
        Tk.CLOCK_SCALE to 5.2f, Tk.CLOCK_WEIGHT to 200, Tk.CLOCK_TRACKING to -0.03f,
        Tk.ARRANGEMENT to 0, Tk.DATE_FORMAT to 2, Tk.DATE_SCALE to 0.95f,
        Tk.DATE_TRACKING to 0.28f, Tk.DATE_UPPER to true, Tk.DATE_WEIGHT to 380,
        Tk.ALIGN_X to 1, Tk.ANCHOR_Y to 0.34f, Tk.PINS_VISIBLE to false,
        Tk.UI_SIZE to 18f, Tk.UI_WEIGHT to 450, Tk.SEARCH_STYLE to 2,
        Tk.RESULT_ALIGN to 1, Tk.BACKGROUND to 0,
    )),
    Preset("Nightfall", mapOf(
        Tk.FAMILY to Fonts.GROTESK, Tk.CLOCK_FAMILY to Fonts.GROTESK,
        Tk.BACKGROUND to 1, Tk.WALLPAPER_DIM to 0.6f, Tk.BRIGHTNESS to 0.95f,
        Tk.CLOCK_SCALE to 3.4f, Tk.CLOCK_WEIGHT to 300, Tk.UI_SIZE to 18f,
        Tk.ALIGN_X to 1, Tk.ANCHOR_Y to 0.2f, Tk.ARRANGEMENT to 0,
        Tk.DATE_FORMAT to 0, Tk.SEARCH_STYLE to 0, Tk.ROW_STYLE to 0,
    )),
    Preset("Drift", mapOf(
        Tk.FAMILY to Fonts.GROTESK, Tk.CLOCK_FAMILY to Fonts.GROTESK,
        Tk.ARRANGEMENT to 1, Tk.ALIGN_X to 2, Tk.ANCHOR_Y to 0.6f,
        Tk.PINS_ALIGN to 2, Tk.CLOCK_SCALE to 3.2f, Tk.CLOCK_WEIGHT to 300,
        Tk.DATE_SCALE to 1.3f, Tk.DATE_TRACKING to 0.1f, Tk.UI_SIZE to 19f,
        Tk.ROW_DENSITY to 1.15f, Tk.RESULT_ALIGN to 2, Tk.TIME_FORMAT to 1,
    )),
)

/* ------------------------------- theme ---------------------------------- */

internal class AtriumTheme(private val ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("atrium_theme", Context.MODE_PRIVATE)

    private val vals = HashMap<String, Any>()
    private val listeners = ArrayList<(String?) -> Unit>()
    private val recent = ArrayDeque<String>()

    private var batchDepth = 0
    private var dirty = false
    private var deferWrites = false
    private val pendingWrites = HashSet<String>()

    init {
        for (d in TOKEN_DEFS.values) vals[d.key] = coerce(d, readPref(d))
        if (!prefs.contains(Tk.TIME_FORMAT)) {
            vals[Tk.TIME_FORMAT] =
                if (android.text.format.DateFormat.is24HourFormat(ctx)) 2 else 0
        }
    }

    /* ---------- core ---------- */

    private fun readPref(d: TokDef): Any {
        val raw = prefs.getString(d.key, null) ?: return d.def
        return try {
            when (d.type) {
                TokType.INT -> raw.toInt()
                TokType.FLOAT -> raw.toFloat()
                TokType.BOOL -> raw.toBoolean()
            }
        } catch (_: Exception) { d.def }
    }

    private fun coerce(d: TokDef, v: Any): Any = when (d.type) {
        TokType.INT -> (v as Number).toInt().coerceIn(d.min.toInt(), d.max.toInt())
        TokType.FLOAT -> (v as Number).toFloat().coerceIn(d.min.toFloat(), d.max.toFloat())
        TokType.BOOL -> v as Boolean
    }

    private fun writePref(key: String, v: Any) {
        prefs.edit().putString(key, v.toString()).apply()
    }

    fun raw(key: String): Any? = vals[key]

    fun set(key: String, value: Any) {
        val d = TOKEN_DEFS[key] ?: return
        val v = coerce(d, value)
        if (vals[key] == v) return
        vals[key] = v
        if (deferWrites) pendingWrites.add(key) else writePref(key, v)
        pushRecent(key)
        if (batchDepth > 0) dirty = true else fire(key)
    }

    /** Batch many changes; listeners fire once with a null key at the end. */
    fun <T> batch(block: () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && dirty) { dirty = false; fire(null) }
        }
    }

    /** Suppress disk writes during continuous gestures (drags). */
    fun deferWrites() { deferWrites = true }

    fun flushWrites() {
        deferWrites = false
        for (k in pendingWrites) vals[k]?.let { writePref(k, it) }
        pendingWrites.clear()
    }

    private fun fire(key: String?) {
        for (l in listeners.toList()) l(key)
    }

    fun addListener(l: (String?) -> Unit) { listeners.add(l) }

    fun removeListener(l: (String?) -> Unit) { listeners.remove(l) }

    private fun pushRecent(key: String) {
        if (!RECENT_KEYS.contains(key)) return
        recent.remove(key)
        recent.addLast(key)
        while (recent.size > 6) recent.removeFirst()
    }

    fun recentKeys(): List<String> = recent.toList().reversed()

    /* ---------- snapshots (undo / redo) ---------- */

    fun snapshot(): String {
        val o = JSONObject()
        for ((k, v) in vals) o.put(k, v.toString())
        return o.toString()
    }

    fun restore(json: String) {
        batch {
            try {
                val o = JSONObject(json)
                for (k in o.keys()) {
                    val d = TOKEN_DEFS[k] ?: continue
                    val raw = o.optString(k)
                    try {
                        when (d.type) {
                            TokType.INT -> set(k, raw.toInt())
                            TokType.FLOAT -> set(k, raw.toFloat())
                            TokType.BOOL -> set(k, raw.toBoolean())
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    fun resetAll() {
        batch { for (d in TOKEN_DEFS.values) set(d.key, d.def) }
    }

    fun applyPreset(p: Preset) {
        batch { for (d in TOKEN_DEFS.values) set(d.key, p.values[d.key] ?: d.def) }
    }

    /* ---------- typed accessors ---------- */

    var family: Int get() = vals[Tk.FAMILY] as Int; set(v) { set(Tk.FAMILY, v) }
    var ui_weight: Int get() = vals[Tk.UI_WEIGHT] as Int; set(v) { set(Tk.UI_WEIGHT, v) }
    var ui_size: Float get() = vals[Tk.UI_SIZE] as Float; set(v) { set(Tk.UI_SIZE, v) }
    var tracking: Float get() = vals[Tk.TRACKING] as Float; set(v) { set(Tk.TRACKING, v) }

    var clock_family: Int get() = vals[Tk.CLOCK_FAMILY] as Int; set(v) { set(Tk.CLOCK_FAMILY, v) }
    var clock_weight: Int get() = vals[Tk.CLOCK_WEIGHT] as Int; set(v) { set(Tk.CLOCK_WEIGHT, v) }
    var clock_scale: Float get() = vals[Tk.CLOCK_SCALE] as Float; set(v) { set(Tk.CLOCK_SCALE, v) }
    var clock_tracking: Float get() = vals[Tk.CLOCK_TRACKING] as Float; set(v) { set(Tk.CLOCK_TRACKING, v) }
    var time_format: Int get() = vals[Tk.TIME_FORMAT] as Int; set(v) { set(Tk.TIME_FORMAT, v) }
    var date_format: Int get() = vals[Tk.DATE_FORMAT] as Int; set(v) { set(Tk.DATE_FORMAT, v) }
    var clock_arrangement: Int get() = vals[Tk.ARRANGEMENT] as Int; set(v) { set(Tk.ARRANGEMENT, v) }
    var clock_separator: Int get() = vals[Tk.SEPARATOR] as Int; set(v) { set(Tk.SEPARATOR, v) }
    var date_upper: Boolean get() = vals[Tk.DATE_UPPER] as Boolean; set(v) { set(Tk.DATE_UPPER, v) }
    var date_scale: Float get() = vals[Tk.DATE_SCALE] as Float; set(v) { set(Tk.DATE_SCALE, v) }
    var date_weight: Int get() = vals[Tk.DATE_WEIGHT] as Int; set(v) { set(Tk.DATE_WEIGHT, v) }
    var date_tracking: Float get() = vals[Tk.DATE_TRACKING] as Float; set(v) { set(Tk.DATE_TRACKING, v) }

    var align_x: Int get() = vals[Tk.ALIGN_X] as Int; set(v) { set(Tk.ALIGN_X, v) }
    var anchor_y: Float get() = vals[Tk.ANCHOR_Y] as Float; set(v) { set(Tk.ANCHOR_Y, v) }
    var side_inset: Float get() = vals[Tk.SIDE_INSET] as Float; set(v) { set(Tk.SIDE_INSET, v) }
    var clock_gap: Float get() = vals[Tk.CLOCK_GAP] as Float; set(v) { set(Tk.CLOCK_GAP, v) }
    var pins_visible: Boolean get() = vals[Tk.PINS_VISIBLE] as Boolean; set(v) { set(Tk.PINS_VISIBLE, v) }
    var pins_size: Float get() = vals[Tk.PINS_SIZE] as Float; set(v) { set(Tk.PINS_SIZE, v) }
    var pins_align: Int get() = vals[Tk.PINS_ALIGN] as Int; set(v) { set(Tk.PINS_ALIGN, v) }

    var row_style: Int get() = vals[Tk.ROW_STYLE] as Int; set(v) { set(Tk.ROW_STYLE, v) }
    var icon_size: Float get() = vals[Tk.ICON_SIZE] as Float; set(v) { set(Tk.ICON_SIZE, v) }
    var icon_shape: Int get() = vals[Tk.ICON_SHAPE] as Int; set(v) { set(Tk.ICON_SHAPE, v) }
    var icon_mono: Boolean get() = vals[Tk.ICON_MONO] as Boolean; set(v) { set(Tk.ICON_MONO, v) }
    var row_density: Float get() = vals[Tk.ROW_DENSITY] as Float; set(v) { set(Tk.ROW_DENSITY, v) }
    var result_align: Int get() = vals[Tk.RESULT_ALIGN] as Int; set(v) { set(Tk.RESULT_ALIGN, v) }
    var show_subtitles: Boolean get() = vals[Tk.SHOW_SUBTITLES] as Boolean; set(v) { set(Tk.SHOW_SUBTITLES, v) }
    var show_headers: Boolean get() = vals[Tk.SHOW_HEADERS] as Boolean; set(v) { set(Tk.SHOW_HEADERS, v) }
    var column_width: Float get() = vals[Tk.COLUMN_WIDTH] as Float; set(v) { set(Tk.COLUMN_WIDTH, v) }

    var search_style: Int get() = vals[Tk.SEARCH_STYLE] as Int; set(v) { set(Tk.SEARCH_STYLE, v) }
    var search_size: Float get() = vals[Tk.SEARCH_SIZE] as Float; set(v) { set(Tk.SEARCH_SIZE, v) }

    var accent: Int get() = vals[Tk.ACCENT] as Int; set(v) { set(Tk.ACCENT, v) }
    var tint_text: Boolean get() = vals[Tk.TINT_TEXT] as Boolean; set(v) { set(Tk.TINT_TEXT, v) }
    var brightness: Float get() = vals[Tk.BRIGHTNESS] as Float; set(v) { set(Tk.BRIGHTNESS, v) }
    var background_mode: Int get() = vals[Tk.BACKGROUND] as Int; set(v) { set(Tk.BACKGROUND, v) }
    var wallpaper_dim: Float get() = vals[Tk.WALLPAPER_DIM] as Float; set(v) { set(Tk.WALLPAPER_DIM, v) }

    /* ---------- derived ---------- */

    val fg: Int
        get() = scaled(if (tint_text) accentColor else Color.WHITE, brightness)

    val accentColor: Int get() = ACCENTS[accent].second

    val secondary: Int get() = colorAt(0.62f)

    private fun scaled(c: Int, k: Float): Int = Color.argb(
        255,
        (Color.red(c) * k).roundToInt().coerceIn(0, 255),
        (Color.green(c) * k).roundToInt().coerceIn(0, 255),
        (Color.blue(c) * k).roundToInt().coerceIn(0, 255),
    )

    fun colorAt(a: Float): Int =
        (fg and 0x00FFFFFF) or (((a * 255).roundToInt()) shl 24)

    val uiSp: Float get() = ui_size
    val clockSp: Float get() = ui_size * clock_scale
    val dateSp: Float get() = ui_size * date_scale
    val pinsSp: Float get() = ui_size * pins_size

    fun clockFamilyIdx(): Int = if (clock_family < 0) family else clock_family

    val timePattern: String get() = TIME_FORMATS[time_format]
    val datePattern: String get() = DATE_FORMATS[date_format]

    val inlinePattern: String
        get() {
            val d = if (date_format == DATE_HIDDEN) ""
            else DATE_FORMATS[date_format] + SEPARATORS[clock_separator]
            return d + timePattern
        }

    fun hGravity(): Int = when (align_x) {
        0 -> H_START
        2 -> H_END
        else -> H_CENTER
    }

    fun pinsGravity(): Int = when (pins_align) {
        -1 -> hGravity()
        0 -> H_START
        2 -> H_END
        else -> H_CENTER
    }

    /* ---------- styling roles (shared by home + studio replica) ---------- */

    fun styleClock(t: TextView) {
        t.typeface = Fonts.weighted(ctx, clockFamilyIdx(), clock_weight)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSp)
        t.letterSpacing = clock_tracking
        t.setTextColor(fg)
        t.includeFontPadding = false
    }

    fun styleDate(t: TextView) {
        t.typeface = Fonts.weighted(ctx, clockFamilyIdx(), date_weight)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateSp)
        t.letterSpacing = date_tracking
        t.setTextColor(secondary)
        t.includeFontPadding = false
    }

    fun styleUi(t: TextView, sp: Float) {
        t.typeface = Fonts.weighted(ctx, family, ui_weight)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.letterSpacing = tracking
        t.setTextColor(fg)
        t.includeFontPadding = false
    }

    fun styleHeader(t: TextView) {
        t.typeface = Fonts.weighted(ctx, family, 500)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.letterSpacing = 0.18f
        t.setTextColor(colorAt(0.5f))
        t.includeFontPadding = false
    }

    fun styleValue(t: TextView) {
        t.typeface = Fonts.weighted(ctx, family, 450)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.letterSpacing = 0.01f
        t.setTextColor(secondary)
        t.includeFontPadding = false
    }

    companion object {
        const val H_START = android.view.Gravity.START
        const val H_CENTER = android.view.Gravity.CENTER_HORIZONTAL
        const val H_END = android.view.Gravity.END
        private val RECENT_KEYS = setOf(
            Tk.FAMILY, Tk.UI_WEIGHT, Tk.UI_SIZE, Tk.TRACKING,
            Tk.CLOCK_FAMILY, Tk.CLOCK_WEIGHT, Tk.CLOCK_SCALE, Tk.CLOCK_TRACKING,
            Tk.TIME_FORMAT, Tk.DATE_FORMAT, Tk.ARRANGEMENT, Tk.SEPARATOR,
            Tk.DATE_UPPER, Tk.DATE_SCALE, Tk.DATE_WEIGHT, Tk.DATE_TRACKING,
            Tk.ALIGN_X, Tk.ANCHOR_Y, Tk.SIDE_INSET, Tk.CLOCK_GAP,
            Tk.PINS_VISIBLE, Tk.PINS_SIZE, Tk.PINS_ALIGN,
            Tk.ROW_STYLE, Tk.ICON_SIZE, Tk.ICON_SHAPE, Tk.ICON_MONO,
            Tk.ROW_DENSITY, Tk.RESULT_ALIGN, Tk.SHOW_SUBTITLES, Tk.SHOW_HEADERS,
            Tk.COLUMN_WIDTH, Tk.SEARCH_STYLE, Tk.SEARCH_SIZE,
            Tk.ACCENT, Tk.TINT_TEXT, Tk.BRIGHTNESS, Tk.BACKGROUND, Tk.WALLPAPER_DIM,
        )
    }
}

/* --------------------------- value formatting ---------------------------- */

internal fun fmtW(v: Float): String = "W ${v.roundToInt()}"
internal fun fmtSp(v: Float): String = "${v.roundToInt()} sp"
internal fun fmtDp(v: Float): String = "${v.roundToInt()} dp"
internal fun fmtEm(v: Float): String = String.format(Locale.ROOT, "%+.3f em", v)
internal fun fmtPct(v: Float): String = "${(v * 100).roundToInt()}%"
internal fun fmtX(v: Float): String = String.format(Locale.ROOT, "%.1f×", v)
