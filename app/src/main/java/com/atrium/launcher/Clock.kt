package com.atrium.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/**
 * The clock is the poster of the launcher, so it is drawn rather than laid out:
 * a light-weight grotesque at display size with tabular figures, a recessive
 * colon, and a small mono date sitting above it. Digits never reflow, and a
 * minute change is a soft cross-fade instead of a jump.
 */
internal class ClockView(ctx: Context, private val settings: LauncherSettings) : View(ctx) {

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var scale = 1f
        set(value) {
            val v = value.coerceIn(0.6f, 2f)
            if (field == v) return
            field = v
            applyType()
            requestLayout()
            invalidate()
        }

    private var timeSize = 0f
    private var dateSize = 0f
    private var smallSize = 0f

    private var timeBaseline = 0f
    private var dateBaseline = 0f

    private var hourRef = 0f
    private var colonW = 0f
    private var minRef = 0f
    private var secRef = 0f
    private var suffixRef = 0f

    private var hourText = ""
    private var minuteText = ""
    private var secondText = ""
    private var suffixText = ""
    private var dateText = ""

    private var oldHour = ""
    private var oldMinute = ""
    private var oldSecond = ""
    private var oldSuffix = ""

    private var flash = 1f
    private var flashAnim: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            sync()
            val now = System.currentTimeMillis()
            val period = if (settings.showSeconds) 1000L else 60_000L
            handler.postDelayed(this, period - now % period + 12L)
        }
    }

    init {
        applyType()
        sync()
    }

    fun sync() {
        val loc = locale()
        val cal = Calendar.getInstance()
        val use24 = when (settings.clockFormat) {
            1 -> true
            2 -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val h = if (use24) pad2(h24) else ((h24 + 11) % 12 + 1).toString()
        val m = pad2(cal.get(Calendar.MINUTE))
        val s = pad2(cal.get(Calendar.SECOND))
        val suffix = if (use24) "" else SimpleDateFormat("a", loc).format(cal.time).uppercase(loc)
        val date = if (settings.showDate) {
            SimpleDateFormat("EEEE, d MMMM", loc).format(cal.time).uppercase(loc)
        } else ""

        val minutesChanged = h != hourText || m != minuteText
        val visibleSecondaryChanged = suffix != suffixText || date != dateText
        if (!minutesChanged && s == secondText && !visibleSecondaryChanged) return

        // Only the minute earns a transition; a per-second cross-fade would flicker.
        if (minutesChanged && hourText.isNotEmpty()) {
            oldHour = hourText
            oldMinute = minuteText
            oldSecond = secondText
            oldSuffix = suffixText
            flash = 0f
            flashAnim?.cancel()
            val dur = (420L * settings.animSpeed).toLong().coerceIn(160L, 900L)
            flashAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = dur
                interpolator = ExpoOut
                addUpdateListener {
                    flash = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        val dateWasShown = dateText.isNotEmpty()
        hourText = h
        minuteText = m
        secondText = s
        suffixText = suffix
        dateText = date
        if (dateWasShown != date.isNotEmpty()) requestLayout()
        contentDescription = buildString {
            append(if (use24) "$h:$m" else "$h:$m ${suffix.lowercase(loc)}")
            if (date.isNotEmpty()) append(", ").append(date.lowercase(loc))
        }
        invalidate()
    }

    private fun locale(): Locale {
        val c = context.resources.configuration
        return if (c.locales.isEmpty) Locale.getDefault() else c.locales[0]
    }

    private fun pad2(v: Int) = if (v < 10) "0$v" else v.toString()

    private fun applyType() {
        val c = context
        timeSize = c.spToPx(66f * scale)
        dateSize = c.spToPx(10.5f * scale)
        smallSize = c.spToPx(15f * scale)

        timePaint.apply {
            reset()
            isAntiAlias = true
            color = Ink.FULL
            textSize = timeSize
            typeface = Typo.sans(c, 250)
            letterSpacing = -0.028f
            fontFeatureSettings = "tnum"
        }
        datePaint.apply {
            reset()
            isAntiAlias = true
            color = Ink.FAINT
            textSize = dateSize
            typeface = Typo.mono(c, 450)
            letterSpacing = 0.2f
        }
        smallPaint.apply {
            reset()
            isAntiAlias = true
            color = Ink.GHOST
            textSize = smallSize
            typeface = Typo.mono(c, 400)
            letterSpacing = 0.04f
        }

        hourRef = timePaint.measureText("00")
        colonW = timePaint.measureText(":")
        minRef = timePaint.measureText("00")
        secRef = smallPaint.measureText(":00")
        suffixRef = smallPaint.measureText("PM")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val dateH = if (dateText.isNotEmpty()) datePaint.fontSpacing else 0f
        val gap = if (dateText.isEmpty()) 0f else context.dp(8f * scale)
        val timeH = timePaint.fontSpacing
        val h = (dateH + gap + timeH + context.dp(4f)).toInt()
        dateBaseline = if (dateText.isEmpty()) 0f else -datePaint.fontMetrics.ascent
        timeBaseline = dateH + gap - timePaint.fontMetrics.ascent
        setMeasuredDimension(w, resolveSize(max(h, 1), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        if (dateText.isNotEmpty()) {
            val w = datePaint.measureText(dateText)
            canvas.drawText(dateText, cx - w / 2f, dateBaseline, datePaint)
        }

        val showSeconds = settings.showSeconds
        val showSuffix = suffixText.isNotEmpty()
        val secBlock = if (showSeconds) secRef + context.dp(6f * scale) else 0f
        val suffixBlock = if (showSuffix) suffixRef + context.dp(10f * scale) else 0f
        val blockW = hourRef + colonW + minRef + secBlock + suffixBlock
        val left = cx - blockW / 2f

        val f = flash.coerceIn(0f, 1f)
        if (f < 1f && oldHour.isNotEmpty()) {
            drawMoment(canvas, oldHour, oldMinute, oldSecond, oldSuffix, left, 1f - f, -context.dp(5f) * f)
        }
        drawMoment(canvas, hourText, minuteText, secondText, suffixText, left, f, context.dp(5f) * (1f - f))
    }

    /** Draws one time set. All positions derive from reference widths, so digits never shift. */
    private fun drawMoment(
        canvas: Canvas,
        h: String,
        m: String,
        s: String,
        suffix: String,
        left: Float,
        alpha: Float,
        dy: Float,
    ) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0.004f) return
        val baseline = timeBaseline + dy
        val hourW = timePaint.measureText(h)

        timePaint.alpha = (a * 255).toInt()
        canvas.drawText(h, left + (hourRef - hourW), baseline, timePaint)

        var x = left + hourRef
        val dimAlpha = (a * 0.34f * 255).toInt()
        timePaint.alpha = dimAlpha
        canvas.drawText(":", x, baseline, timePaint)
        x += colonW

        timePaint.alpha = (a * 255).toInt()
        canvas.drawText(m, x, baseline, timePaint)
        x += minRef

        if (settings.showSeconds) {
            x += context.dp(6f * scale)
            smallPaint.alpha = (a * 0.55f * 255).toInt()
            canvas.drawText(":$s", x, baseline, smallPaint)
            x += secRef
        }
        if (suffix.isNotEmpty()) {
            x += context.dp(10f * scale)
            smallPaint.alpha = (a * 0.62f * 255).toInt()
            canvas.drawText(suffix, x, baseline, smallPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        flashAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
