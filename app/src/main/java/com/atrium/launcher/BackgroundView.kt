package com.atrium.launcher

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * ATRIUM · ATMOSPHERE
 * ────────────────────────────────────────────────────────────────────────────
 * The surface the whole launcher sits on. Five materials — void, solid tone,
 * gradient, aurora, wallpaper — each derived from a handful of tokens, plus two
 * finishing passes (vignette, film grain) that make flat digital black feel like
 * a physical surface.
 */
@SuppressLint("ViewConstructor")
class BackgroundView(ctx: Context, private val tokens: Tokens) : FrameLayout(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val surface = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var mode = "void"
    private var tone = 0.06f
    private var hue = 258f
    private var sat = 0.35f
    private var glow = 0.3f
    private var vignette = 0f
    private var grainAmt = 0.06f
    private var angle = 90f
    private var motion = false

    private var grainShader: BitmapShader? = null
    private var grainBmp: Bitmap? = null

    private var shaderW = -1f
    private var shaderH = -1f
    private var shaderKey = ""
    private fun key(): String =
        "$mode/$tone/$hue/$sat/$angle/$grainAmt"

    private var phase = 0f
    private var running = false
    private var frameStart = 0L

    private val wallpaper = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var wpUri: String = ""

    init {
        setWillNotDraw(false)
        addView(wallpaper, LayoutParams(-1, -1))
        refresh()
        startMotionIfNeeded()
    }

    fun refresh() {
        mode = tokens.s(K.bgMode)
        tone = tokens.f(K.bgTone)
        val palSat = tokens.f(K.palSat)
        val palHue = tokens.f(K.palHue)
        hue = if (palSat > 0.001f) palHue else tokens.f(K.bgHue)
        sat = if (palSat > 0.001f) tokens.f(K.bgSaturation) * (0.4f + palSat) else 0f
        glow = tokens.f(K.bgGlow)
        vignette = tokens.f(K.bgVignette)
        grainAmt = tokens.f(K.bgGrain)
        angle = tokens.f(K.bgGradientAngle)
        motion = tokens.b(K.bgMotion)
        if (grainAmt <= 0.001f) grainBmp = null
        buildShaders()
        if (mode == "wallpaper") loadWallpaper(tokens.s(K.bgWallpaper))
        wallpaper.visibility = if (mode == "wallpaper") View.VISIBLE else View.GONE
        startMotionIfNeeded()
        invalidate()
    }

    private fun loadWallpaper(uriStr: String) {
        if (uriStr == wpUri && (wallpaper.drawable != null || uriStr.isEmpty())) return
        wpUri = uriStr
        var bmp: Bitmap? = null
        try {
            bmp = if (uriStr.isNotEmpty()) {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                val wm = WallpaperManager.getInstance(context)
                val dr = wm.drawable
                if (dr is android.graphics.drawable.BitmapDrawable) dr.bitmap else null
            }
        } catch (e: Exception) { bmp = null }
        if (bmp == null) {
            try {
                val wm = WallpaperManager.getInstance(context)
                val dr = wm.drawable
                if (dr is android.graphics.drawable.BitmapDrawable) bmp = dr.bitmap
            } catch (e: Exception) {}
        }
        wallpaper.setImageBitmap(bmp)
        val dim = tokens.f(K.bgWallpaperDim)
        // dim over the wallpaper via a translucent scrim drawn in dispatchDraw
        val blur = tokens.f(K.bgWallpaperBlur)
        if (Build.VERSION.SDK_INT >= 31) {
            wallpaper.setRenderEffect(
                if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
            )
        }
        invalidate()
    }

    private fun surfaceColor(light: Float): Int =
        if (sat <= 0.001f) {
            val v = (light * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        } else hsl(hue, sat * 0.7f, (light * 0.9f).coerceIn(0f, 1f))

    private fun buildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (w == shaderW && h == shaderH && shaderKey == key()) {
            // geometry and tokens unchanged — keep the existing shaders
            return
        }
        shaderW = w
        shaderH = h
        shaderKey = key()
        rect.set(0f, 0f, w, h)
        when (mode) {
            "gradient" -> {
                val a = Math.toRadians(angle.toDouble())
                val cx = w / 2f
                val cy = h / 2f
                val r = (kotlin.math.hypot(w.toDouble(), h.toDouble()) / 2.0).toFloat()
                val x0 = cx - (cos(a) * r).toFloat()
                val y0 = cy - (sin(a) * r).toFloat()
                val x1 = cx + (cos(a) * r).toFloat()
                val y1 = cy + (sin(a) * r).toFloat()
                val base = tone.coerceIn(0f, 0.7f)
                surface.shader = LinearGradient(
                    x0, y0, x1, y1,
                    intArrayOf(
                        surfaceColor(base * 1.15f),
                        surfaceColor(base * 0.55f),
                        surfaceColor(base * 1.05f)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            "solid" -> {
                surface.shader = null
                surface.color = surfaceColor(tone)
            }
            "aurora", "wallpaper" -> {
                surface.shader = null
                surface.color = surfaceColor(tone * 0.5f)
            }
            else -> {
                surface.shader = null
                surface.color = Color.BLACK
            }
        }

        // grain: a tiny tiled noise field
        if (grainAmt > 0.001f) {
            if (grainBmp == null) grainBmp = makeNoise(96)
            val sh = BitmapShader(grainBmp!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grainShader = sh
        } else grainShader = null
    }

    private fun makeNoise(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val r = Random(7)
        for (i in px.indices) {
            val v = r.nextInt(256)
            val a = (Math.abs(v - 128) * 1.4f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        buildShaders()
    }

    // ── hero flash: a fabric of glints that sweeps the surface once ─────────
    private var flashP = 1f
    private var flashCount = 0
    private var flashSeed = 1L

    /** Fire a one-puff shimmer; used when a preset or shuffle lands. */
    fun flash() {
        flashSeed = System.nanoTime()
        flashCount = 26
        flashP = 0f
        invalidate()
    }

    private fun drawFlash(c: Canvas, w: Float, h: Float) {
        if (flashP >= 1f || flashCount == 0) return
        val p = flashP
        val r = java.util.Random(flashSeed)
        val glint = Paint(Paint.ANTI_ALIAS_FLAG)
        val col = 0xFFFFFFFF.toInt()
        for (i in 0 until flashCount) {
            val fx = r.nextFloat()
            val fy = r.nextFloat() * 0.9f
            val delay = r.nextFloat() * 0.45f
            val local = ((p - delay) / 0.55f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val a = (Math.sin(local * Math.PI) * 0.5f).toFloat()
            val px = fx * w
            val py = (fy + (1f - local) * 0.12f) * h
            val rad = dp(60f + r.nextFloat() * 140f)
            val sh = RadialGradient(px, py, rad,
                intArrayOf((col and 0x00FFFFFF) or ((a * 0.22f * 255).toInt() shl 24), 0x00000000),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            glint.shader = sh
            c.drawCircle(px, py, rad, glint)
        }
        glint.shader = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1 · base material
        if (surface.shader != null) canvas.drawRect(0f, 0f, w, h, surface)
        else canvas.drawColor(if (mode == "void") Color.BLACK else surface.color)

        // 2 · wallpaper (child)
        super.dispatchDraw(canvas)

        if (mode == "wallpaper") {
            overlay.shader = null
            overlay.color = Color.BLACK
            overlay.alpha = (tokens.f(K.bgWallpaperDim) * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, overlay)
        }

        // 3 · ambient light
        if (mode == "aurora" || (glow > 0.01f && mode != "wallpaper")) {
            drawAurora(canvas, w, h)
        }

        // 4 · vignette
        if (vignette > 0.01f) {
            val r = kotlin.math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.62f
            val sh = RadialGradient(
                w / 2f, h * 0.46f, r,
                intArrayOf(0x00000000, 0x00000000, ((vignette * 0.92f * 255).toInt()) shl 24),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            overlay.shader = sh
            overlay.alpha = 255
            canvas.drawRect(0f, 0f, w, h, overlay)
            overlay.shader = null
        }

        // 5 · hero flash
        if (flashP < 1f) {
            drawFlash(canvas, w, h)
            flashP += 0.028f
            if (flashP < 1f) Choreographer.getInstance().postFrameCallback { invalidate() }
        }

        // 6 · grain
        val gs = grainShader
        if (gs != null) {
            val m = Matrix()
            m.setScale(1f, 1f)
            gs.setLocalMatrix(m)
            overlay.shader = gs
            overlay.alpha = (grainAmt * 210).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, overlay)
            overlay.shader = null
        }
    }

    private fun drawAurora(c: Canvas, w: Float, h: Float) {
        val strength = if (mode == "aurora") glow.coerceIn(0f, 1f) else glow * 0.5f
        if (strength <= 0.005f) return
        val cx = w * (0.5f + 0.24f * sin(phase * 0.7f))
        val cy = h * (0.34f + 0.1f * cos(phase * 0.5f))
        val radius = kotlin.math.max(w, h) * (0.62f + 0.06f * sin(phase))
        val a1 = (strength * 0.5f * 255).toInt().coerceIn(0, 255) shl 24
        val c1 = hsl(hue, sat * 0.9f, 0.34f, 0f)
        val sh = RadialGradient(
            cx, cy, radius,
            intArrayOf((hsl(hue, sat * 0.85f, 0.42f) and 0x00FFFFFF) or a1,
                (hsl(hue + 26f, sat * 0.7f, 0.3f) and 0x00FFFFFF) or ((strength * 0.24f * 255).toInt() shl 24),
                0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = sh
        c.drawRect(0f, 0f, w, h, glowPaint)
        glowPaint.shader = null
    }

    // ---- living light ----------------------------------------------------

    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (frameStart == 0L) frameStart = now
            phase += 0.0022f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startMotionIfNeeded() {
        val want = motion && (mode == "aurora" || glow > 0.01f)
        if (want && !running) {
            running = true
            Choreographer.getInstance().postFrameCallback(cb)
        } else if (!want && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(cb)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMotionIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(cb)
    }
}

/** Marker for views that respond to a tap (so the studio can pass taps through). */
interface Tappable {
    fun tap()
    fun tapLabel(): String
    fun tapBounds(): Rect? = null
}
