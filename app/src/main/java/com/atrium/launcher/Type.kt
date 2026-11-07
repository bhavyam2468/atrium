package com.atrium.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * ATRIUM · TYPOGRAPHY
 * ────────────────────────────────────────────────────────────────────────────
 * A small, deliberate type system. Four real typefaces (bundled as variable
 * fonts, so a single file covers the whole weight axis) plus per-surface
 * density scales. Everything else is derived from tokens.
 */

object Type {

    // ---- faces -----------------------------------------------------------

    @Volatile private var cached: HashMap<String, Typeface>? = null
    private val lock = Any()

    private fun faces(ctx: Context): HashMap<String, Typeface> {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val m = HashMap<String, Typeface>(8)
            m["sans"] = load(ctx, R.font.inter)
            m["grotesk"] = load(ctx, R.font.space_grotesk)
            m["serif"] = load(ctx, R.font.instrument_serif)
            m["serifItalic"] = load(ctx, R.font.instrument_serif_italic)
            m["mono"] = load(ctx, R.font.jetbrains_mono)
            cached = m
            return m
        }
    }

    private fun load(ctx: Context, id: Int): Typeface =
        try {
            ctx.resources.getFont(id)
        } catch (e: Exception) {
            Typeface.SANS_SERIF
        }

    /**
     * Resolve the clock face for a given style id, at a given weight (100..900).
     * Variable weight is applied via [Typeface.create] with weight/italic args.
     */
    fun clockFace(ctx: Context, style: String, weight: Int): Typeface {
        val f = faces(ctx)
        return when (style) {
            "hairline" -> weighted(f["sans"]!!, weight.coerceIn(100, 300))
            "condensed" -> weighted(f["grotesk"]!!, weight.coerceIn(200, 700))
            "serif" -> f["serif"]!!
            "serifItalic" -> f["serifItalic"]!!
            "mono" -> weighted(f["mono"]!!, weight.coerceIn(100, 700))
            "wide" -> weighted(f["grotesk"]!!, weight.coerceIn(200, 700))
            else -> weighted(f["sans"]!!, weight.coerceIn(100, 800))
        }
    }

    private val weightCache = HashMap<String, Typeface>(32)

    /** Deriving a variable-weight face is expensive; scrubs ask for one per frame. */
    fun weighted(base: Typeface, weight: Int): Typeface {
        val w = ((weight.coerceIn(100, 900) / 10) * 10)
        val key = System.identityHashCode(base).toString() + ":" + w
        weightCache[key]?.let { return it }
        val f = try {
            Typeface.create(base, w, false)
        } catch (e: Exception) {
            base
        }
        if (weightCache.size > 96) weightCache.clear()
        weightCache[key] = f
        return f
    }

    fun sans(ctx: Context, weight: Int = 400): Typeface = weighted(faces(ctx)["sans"]!!, weight)

    /**
     * The interface face — one token, applied to every non-clock surface:
     * app names, results, the search field, the settings deck and the HUD.
     */
    @Volatile var uiFaceId: String = "inter"

    fun ui(ctx: Context, weight: Int = 400): Typeface {
        val f = faces(ctx)
        return when (uiFaceId) {
            "grotesk" -> weighted(f["grotesk"]!!, weight.coerceIn(200, 700))
            "serif" -> f["serif"]!!
            "serifItalic" -> f["serifItalic"]!!
            "mono" -> weighted(f["mono"]!!, weight.coerceIn(100, 700))
            "system" -> weighted(Typeface.SANS_SERIF, weight)
            "systemSerif" -> weighted(Typeface.SERIF, weight)
            "systemMono" -> weighted(Typeface.MONOSPACE, weight)
            else -> weighted(f["sans"]!!, weight)
        }
    }
    fun mono(ctx: Context, weight: Int = 400): Typeface = weighted(faces(ctx)["mono"]!!, weight)
    fun grotesk(ctx: Context, weight: Int = 400): Typeface = weighted(faces(ctx)["grotesk"]!!, weight)
    fun serif(ctx: Context): Typeface = faces(ctx)["serif"]!!
    fun serifItalic(ctx: Context): Typeface = faces(ctx)["serifItalic"]!!

    /** OpenType features: tabular figures keep the clock perfectly still. */
    fun tabular(p: Paint) {
        try { p.fontFeatureSettings = "tnum" } catch (e: Exception) {}
    }

    fun noKern(p: Paint) {
        try { p.fontFeatureSettings = "" } catch (e: Exception) {}
    }
}

/** Density scale for each surface — one knob moves the whole screen's rhythm. */
class Density(tokens: Tokens) {
    val base = tokens.f(K.typeScale).coerceIn(0.8f, 1.3f)
    val clock = tokens.f(K.clockScale).coerceIn(0.45f, 2.2f)
    val clockDate = tokens.f(K.clockDateScale).coerceIn(0.16f, 1f)
    val pins = tokens.f(K.pinsSize).coerceIn(0.6f, 1.8f)
    val list = tokens.f(K.listSize).coerceIn(0.6f, 1.7f)
    val search = tokens.f(K.searchSize).coerceIn(0.6f, 1.8f)
    val header = tokens.f(K.listHeaderSize).coerceIn(0.3f, 1f)
}

internal fun dpOf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
internal fun Context.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Blend two ARGB ints. */
internal fun blend(a: Int, b: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    val ar = (a shr 16 and 0xFF) + (((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * tt).toInt()
    val ag = (a shr 8 and 0xFF) + (((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * tt).toInt()
    val ab = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * tt).toInt()
    val aa = (a ushr 24) + (((b ushr 24) - (a ushr 24)) * tt).toInt()
    return (aa shl 24) or (ar shl 16) or (ag shl 8) or ab
}

internal fun withAlpha(color: Int, alpha: Float): Int {
    val a = (ColorCompat.alphaOf(color) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (color and 0x00FFFFFF)
}

internal object ColorCompat {
    fun alphaOf(c: Int) = (c ushr 24) and 0xFF
}

/** HSL → ARGB, used by the background/ink hue tokens. */
internal fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Int {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    val c = (1f - Math.abs(2f * ll - 1f)) * ss
    val x = c * (1f - Math.abs((hh / 60f) % 2f - 1f))
    val m = ll - c / 2f
    val (r, g, b) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
    return (a shl 24) or
        (((r + m).coerceIn(0f, 1f) * 255).toInt() shl 16) or
        (((g + m).coerceIn(0f, 1f) * 255).toInt() shl 8) or
        (((b + m).coerceIn(0f, 1f) * 255).toInt())
}

/**
 * Shared visual constants for the ink palette, derived from type tokens.
 */
class Ink(tokens: Tokens) {
    private val tone = tokens.f(K.typeTone).coerceIn(0.55f, 1f)
    private val sat = tokens.f(K.palSat).coerceIn(0f, 1f)
    private val warm = tokens.f(K.palWarm).coerceIn(-1f, 1f)
    private val hue = if (tokens.f(K.palHue) == 0f) tokens.f(K.typeHue) else tokens.f(K.palHue)

    /** Neutral ink at zero saturation, drifting warm/cool as the palette moves. */
    val primary: Int = if (sat <= 0.001f) {
        val v = (255 * tone).toInt().coerceIn(0, 255)
        val r = (v + warm * 10f).toInt().coerceIn(0, 255)
        val g = (v + warm * 3f).toInt().coerceIn(0, 255)
        val b = (v - warm * 12f).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    } else {
        val h = if (warm == 0f) hue else hue + warm * 22f
        hsl(h, sat * 0.34f, 0.5f + 0.45f * tone)
    }
    val secondary: Int = withAlpha(primary, 0.58f)
    val tertiary: Int = withAlpha(primary, 0.36f)
    val ghost: Int = withAlpha(primary, 0.2f)
    val hairline: Int = withAlpha(primary, 0.13f)
    val surface: Int = withAlpha(primary, 0.055f)
}

/** Transparent layout containers that skip needless compositing. */
internal class VLayer(ctx: Context) : android.widget.LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun hasOverlappingRendering() = false
}

internal class Layer(ctx: Context) : android.widget.FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

/** cubic-bezier(0.16, 1, 0.3, 1) — quick start, long soft landing. */
internal object ExpoOut : Interpolator {
    private val p = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}

/** cubic-bezier(0.3, 0, 0, 1) — for things leaving the screen. */
internal object ExpoIn : Interpolator {
    private val p = PathInterpolator(0.7f, 0f, 0.84f, 0f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}

/** A tiny spring-ish overshoot, pure function of t. */
internal object SoftSpring : Interpolator {
    private val p = PathInterpolator(0.34f, 1.28f, 0.64f, 1f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}

internal fun Paint.setLetterSpacing(em: Float, textSizePx: Float) {
    letterSpacing = em * textSizePx
}

// ---- text helpers -------------------------------------------------------

internal fun TextView.atriumType(t: Typeface, sizeSp: Float, color: Int, tracking: Float = 0f) {
    typeface = t
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    includeFontPadding = false
    letterSpacing = tracking
    setSingleLine()
    ellipsize = TextUtils.TruncateAt.END
}

internal fun TextPaint.configure(t: Typeface, sizePx: Float, color: Int, tracking: Float) {
    typeface = t
    textSize = sizePx
    this.color = color
    letterSpacing = tracking
    isAntiAlias = true
    isSubpixelText = true
}

/** Draw text with per-glyph alpha so values can cross-fade mid-change. */
internal fun Canvas.textFade(px: Float, py: Float, paint: TextPaint, chars: CharArray, a: FloatArray, fallbackAlpha: Float) {
    var x = px
    for (i in chars.indices) {
        paint.alpha = ((a.getOrElse(i) { 1f }) * fallbackAlpha * 255).toInt().coerceIn(0, 255)
        drawText(chars, i, 1, x, py, paint)
        x += paint.measureText(chars, i, 1)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  INSET — the design grid
// ════════════════════════════════════════════════════════════════════════════

/**
 * A deliberately boring 4dp rhythm plus the launcher's own spacing decisions.
 * Keeping these in one place is what stops a hand-built layout from drifting
 * into ad-hoc numbers: every gap in the product is one of these.
 */
internal object Inset {
    const val unit = 4f
    const val xxs = 2f
    const val xs = 4f
    const val s = 8f
    const val m = 12f
    const val l = 16f
    const val xl = 24f
    const val xxl = 32f

    /** Screen gutter — the air that makes full-bleed type feel placed. */
    const val gutter = 26f

    /** Distance a column keeps from the search surface. */
    const val aboveField = 18f
}
