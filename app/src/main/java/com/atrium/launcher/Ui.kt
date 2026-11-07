package com.atrium.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Bare text. Press feedback is a tiny scale + dim, no background anywhere. */
internal class Label(ctx: Context) : TextView(ctx) {
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().alpha(if (pressed) 0.45f else 1f).scaleX(if (pressed) 0.97f else 1f)
            .scaleY(if (pressed) 0.97f else 1f).setDuration(if (pressed) 70 else 220)
            .setInterpolator(ExpoOut).start()
    }
    override fun hasOverlappingRendering() = false
}

internal fun styleLabel(t: TextView, sp: Float) {
    val c = t.context
    t.setTextColor(Color.WHITE)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    t.includeFontPadding = false
    t.setSingleLine()
    t.ellipsize = TextUtils.TruncateAt.END
    val h = c.dp(2f).toInt(); val v = c.dp(5f).toInt()
    t.setPadding(h, v, h, v)
}

internal class VLayer(ctx: Context) : LinearLayout(ctx) {
    init { orientation = VERTICAL }
    override fun hasOverlappingRendering() = false
}

internal class Layer(ctx: Context) : FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

/** cubic-bezier(0.16, 1, 0.3, 1) – quick start, very long soft landing. */
internal object ExpoOut : Interpolator {
    private val p = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    override fun getInterpolation(t: Float) = p.getInterpolation(t)
}
