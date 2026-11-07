package com.atrium.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared builders for home-screen atoms. Both the real home and the design
 * studio's live replica build from these, so what you shape in the studio is
 * exactly what you get on the home screen.
 */
internal object HomeComposer {

    const val TAG_TIME = "atrium.time"
    const val TAG_DATE = "atrium.date"

    /* ------------------------------ clock ------------------------------- */

    /**
     * Clock block per theme.
     * arrangement 0: time above date · 1: date above time · 2: one line.
     * Children are ClockTexts tagged TAG_TIME / TAG_DATE so they can be
     * restyled (and re-patterned) in place without rebuilding.
     */
    fun buildClockBlock(ctx: Context, theme: AtriumTheme): ViewGroup {
        val inline = theme.clock_arrangement == 2
        val hasDate = theme.date_format != DATE_HIDDEN
        if (inline || !hasDate) {
            val time = ClockText(ctx).apply { tag = TAG_TIME }
            return VLayer(ctx).apply { addView(time) }
        }
        val time = ClockText(ctx).apply { tag = TAG_TIME }
        val date = ClockText(ctx).apply { tag = TAG_DATE }
        val block = VLayer(ctx)
        val gap = ctx.dp(3f).toInt()
        if (theme.clock_arrangement == 0) {
            block.addView(time)
            block.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
        } else {
            block.addView(date)
            block.addView(time, LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
        }
        return block
    }

    fun restyleClockBlock(block: ViewGroup, theme: AtriumTheme) {
        for (i in 0 until block.childCount) {
            val c = block.getChildAt(i)
            if (c !is ClockText) continue
            when (c.tag) {
                TAG_TIME -> {
                    c.pattern = if (theme.clock_arrangement == 2) theme.inlinePattern
                                else theme.timePattern
                    c.upper = theme.clock_arrangement == 2 && theme.date_upper
                    theme.styleClock(c)
                }
                TAG_DATE -> {
                    c.pattern = theme.datePattern
                    c.upper = theme.date_upper
                    theme.styleDate(c)
                }
            }
        }
    }

    fun findClockChild(block: ViewGroup?, tag: String): ClockText? {
        block ?: return null
        for (i in 0 until block.childCount) {
            val c = block.getChildAt(i)
            if (c is ClockText && c.tag == tag) return c
        }
        return null
    }

    /* ------------------------------- pins ------------------------------- */

    fun stylePin(t: TextView, theme: AtriumTheme) {
        t.typeface = Fonts.weighted(t.context, theme.family,
            (theme.ui_weight + 40).coerceAtMost(900))
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, theme.pinsSp)
        t.letterSpacing = theme.tracking
        t.setTextColor(theme.fg)
        t.includeFontPadding = false
        t.setSingleLine()
        t.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /* ---------------------------- result rows --------------------------- */

    class RowRefs(val label: Label, val icon: IconView?, val result: Result)

    fun displayText(r: Result, theme: AtriumTheme): String =
        if (r.subtitle != null && theme.show_subtitles) "${r.title} · ${r.subtitle}" else r.title

    /**
     * One result row. Horizontal alignment is owned by the parent container
     * (itemsLayout gravity), so alignment changes restyle without a rebuild.
     */
    fun buildResultRow(ctx: Context, theme: AtriumTheme, index: AppIndex, r: Result): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = Label(ctx).apply { text = displayText(r, theme) }
        var icon: IconView? = null
        val entry = r.entry
        if (entry != null && theme.row_style != 0) {
            val iv = IconView(ctx)
            icon = iv
            if (theme.row_style == 1) {
                row.addView(iv)
                row.addView(label, LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = ctx.dp(12f).toInt()
                })
            } else {
                row.addView(iv)
                row.contentDescription = r.title
            }
        } else {
            row.addView(label)
        }
        row.setOnClickListener { r.run(row) }
        row.tag = RowRefs(label, icon, r)
        if (icon != null && entry != null) index.bindIcon(icon, entry)
        restyleResultRow(row, theme)
        return row
    }

    fun restyleResultRow(row: View?, theme: AtriumTheme) {
        if (row !is LinearLayout) return
        val refs = row.tag as? RowRefs ?: return
        val ctx = row.context
        theme.styleUi(refs.label, theme.ui_size)
        val vpad = (ctx.dp(5f) * theme.row_density).toInt()
        val hpad = ctx.dp(4f).toInt()
        row.setPadding(hpad, vpad, hpad, vpad)
        refs.icon?.let { iv ->
            iv.sizeDp = theme.icon_size
            iv.shape = theme.icon_shape
            iv.mono = theme.icon_mono
        }
    }
}
