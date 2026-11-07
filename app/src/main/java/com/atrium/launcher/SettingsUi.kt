package com.atrium.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/* ============================================================================
   Settings rows and sheets.

   Rows are self-contained: a toggle flips on tap, a choice opens a sheet of
   options (rendered with live examples — formats show themselves, typefaces
   show in their own face, accents show their colour), and numeric values are
   adjusted by dragging the row itself, with haptic ticks at every step.
   The same renderer serves the settings column and the design studio's
   long-press sheets.
   ============================================================================ */

internal object SettingsUi {

    fun renderLeaf(
        context: Context,
        node: SettingNode,
        rootHost: FrameLayout,
        theme: AtriumTheme
    ): View = when (node) {
        is SettingNode.Toggle -> ToggleRow(context, node, theme)
        is SettingNode.Choice -> ChoiceRow(context, node, rootHost, theme)
        is SettingNode.Adjust -> AdjustRow(context, node, theme)
        is SettingNode.Action -> ActionRow(context, node, theme)
        is SettingNode.Info -> InfoRow(context, node, theme)
        is SettingNode.Category ->
            InfoRow(context, SettingNode.Info(node.id, node.title, node.description), theme)
    }
}

/* ------------------------- shared row plumbing --------------------------- */

private fun rowPadding(v: View) {
    v.setPadding(v.context.dp(16f).toInt(), v.context.dp(9f).toInt(), v.context.dp(16f).toInt(), v.context.dp(9f).toInt())
}

private fun makeTitle(context: Context, node: SettingNode, theme: AtriumTheme): Label {
    val title = Label(context)
    theme.styleUi(title, 16f)
    title.text = node.title
    return title
}

private fun makeValue(context: Context, theme: AtriumTheme): Label {
    val v = Label(context)
    theme.styleValue(v)
    v.gravity = Gravity.END
    v.setSingleLine()
    v.ellipsize = android.text.TextUtils.TruncateAt.END
    return v
}

private fun addDescription(row: LinearLayout, node: SettingNode, theme: AtriumTheme) {
    if (node.description.isEmpty()) return
    val d = TextView(row.context)
    d.text = node.description
    theme.styleValue(d)
    d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
    d.setSingleLine()
    d.ellipsize = android.text.TextUtils.TruncateAt.END
    row.addView(d, LinearLayout.LayoutParams(-2, -2).apply { topMargin = row.context.dp(2f).toInt() })
}

/** Title + trailing value on one line, description under. */
private fun addHead(row: LinearLayout, node: SettingNode, theme: AtriumTheme): Label {
    val title = makeTitle(node = node, context = row.context, theme = theme)
    val value = makeValue(row.context, theme)
    val head = LinearLayout(row.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(title, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
    head.addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = row.context.dp(12f).toInt() })
    row.addView(head, LinearLayout.LayoutParams(-1, -2))
    addDescription(row, node, theme)
    return value
}

/* ------------------------------- toggle ---------------------------------- */

internal class ToggleRow(
    context: Context,
    private val node: SettingNode.Toggle,
    private val theme: AtriumTheme
) : PressableRow(context) {

    private val value: Label

    init {
        orientation = LinearLayout.VERTICAL
        rowPadding(this)
        isClickable = true
        value = addHead(this, node, theme)
        refresh()
        setOnClickListener {
            node.set(!node.get())
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            refresh()
        }
    }

    fun refresh() {
        val on = node.get()
        value.text = if (on) "on" else "off"
        value.setTextColor(if (on) theme.fg else theme.secondary)
    }
}

/* ------------------------------- choice ---------------------------------- */

internal class ChoiceRow(
    context: Context,
    private val node: SettingNode.Choice,
    private val rootHost: FrameLayout,
    private val theme: AtriumTheme
) : PressableRow(context) {

    private val value: Label

    init {
        orientation = LinearLayout.VERTICAL
        rowPadding(this)
        isClickable = true
        value = addHead(this, node, theme)
        refresh()
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val sheet = OptionSheet(context, rootHost, theme)
            sheet.showOptions(node.title, node.options(), node.current()) { i ->
                node.select(i)
                refresh()
            }
        }
    }

    fun refresh() {
        val opts = node.options()
        val cur = node.current()
        value.text = if (cur in opts.indices) opts[cur].label else ""
    }
}

/* ------------------------------- action ---------------------------------- */

internal class ActionRow(
    context: Context,
    node: SettingNode.Action,
    theme: AtriumTheme
) : PressableRow(context) {

    init {
        orientation = LinearLayout.VERTICAL
        rowPadding(this)
        isClickable = true
        val value = addHead(this, node, theme).apply {
            text = if (node.emphasis) "open →" else "→"
            if (node.emphasis) setTextColor(theme.accentColor)
        }
        if (node.emphasis) {
            (getChildAt(0) as? LinearLayout)?.let { head ->
                (head.getChildAt(0) as? Label)?.setTextColor(theme.accentColor)
            }
        }
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            node.action()
        }
    }
}

/* -------------------------------- info ----------------------------------- */

internal class InfoRow(
    context: Context,
    node: SettingNode.Info,
    theme: AtriumTheme
) : PressableRow(context) {

    init {
        orientation = LinearLayout.VERTICAL
        rowPadding(this)
        isClickable = false
        addView(makeTitle(context, node, theme), LinearLayout.LayoutParams(-2, -2))
        addDescription(this, node, theme)
    }
}

/* ------------------------------- adjust ---------------------------------- */

@SuppressLint("ClickableViewAccessibility")
internal class AdjustRow(
    context: Context,
    private val node: SettingNode.Adjust,
    private val theme: AtriumTheme
) : PressableRow(context) {

    private val value: Label
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var startVal = 0f
    private var lastTick = 0f
    private var moved = false

    init {
        orientation = LinearLayout.VERTICAL
        rowPadding(this)
        isClickable = true
        value = addHead(this, node, theme)
        refreshValue()

        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    startVal = node.get()
                    lastTick = startVal
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (!moved && abs(dx) > slop) {
                        moved = true
                        theme.deferWrites()
                    }
                    if (moved) {
                        val span = (node.max - node.min).coerceAtLeast(0.001f)
                        val v = (startVal + dx / context.dp(240f) * span)
                            .coerceIn(node.min, node.max)
                        if (abs(v - lastTick) >= node.step) {
                            lastTick = v
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        node.set(v)
                        refreshValue()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) performClick()
                    if (moved) theme.flushWrites()
                    moved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) theme.flushWrites()
                    moved = false
                }
            }
            true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        var next = node.get() + node.step
        if (next > node.max + 0.001f) next = node.min
        node.set(next)
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        refreshValue()
        return true
    }

    private fun refreshValue() {
        value.text = node.format(node.get())
    }
}

/* ----------------------------- option sheet ------------------------------ */

/** ScrollView that never grows past a fraction of the screen. */
private class CappedScrollView(ctx: Context) : ScrollView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val h = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, h)
    }
}

internal class OptionSheet(
    context: Context,
    private val rootHost: FrameLayout,
    private val theme: AtriumTheme
) : FrameLayout(context) {

    private val scrimView: View
    private val panel: VLayer
    private val body: VLayer
    private val titleView: Label
    private var dismissed = false

    init {
        scrimView = View(context).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        panel = VLayer(context).apply {
            background = GradientDrawable().apply {
                setColor(0xF60B0B0E.toInt())
                cornerRadii = floatArrayOf(
                    context.dp(22f), context.dp(22f), context.dp(22f), context.dp(22f),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(0, context.dp(14f).toInt(), 0, context.dp(8f).toInt())
        }
        titleView = Label(context).apply {
            theme.styleHeader(this)
            text = ""
        }
        panel.addView(titleView, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = context.dp(20f).toInt()
            marginBottom = context.dp(4f).toInt()
        })
        body = VLayer(context)
        panel.addView(body, LinearLayout.LayoutParams(-1, -2))
        addView(scrimView, LayoutParams(-1, -1))
        addView(panel, LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    fun showOptions(title: String, options: List<Opt>, current: Int, onSelect: (Int) -> Unit) {
        titleView.text = title.uppercase()
        body.removeAllViews()
        options.forEachIndexed { i, opt ->
            body.addView(optionRow(opt, i == current) {
                onSelect(i)
                dismiss()
            })
        }
        attach()
    }

    fun showNodes(title: String, nodes: List<SettingNode>) {
        titleView.text = title.uppercase()
        body.removeAllViews()
        val scroll = CappedScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val list = VLayer(context)
        for (n in nodes) {
            list.addView(SettingsUi.renderLeaf(context, n, rootHost, theme))
            list.addView(hairline(context, theme.colorAt(0.08f)))
        }
        scroll.addView(list, LayoutParams(-1, -2))
        body.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        attach()
    }

    private fun optionRow(opt: Opt, selected: Boolean, onClick: () -> Unit): View {
        val row = PressableRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20f).toInt(), context.dp(11f).toInt(), context.dp(20f).toInt(), context.dp(11f).toInt())
            isClickable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        val c = opt.color
        if (c != null) {
            val dot = TextView(context).apply {
                text = "●"
                setTextColor(c)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            row.addView(dot, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(12f).toInt() })
        }
        val label = Label(context)
        val fam = opt.typefaceFamily
        if (fam != null) {
            label.typeface = Fonts.weighted(context, fam, 480)
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            label.includeFontPadding = false
            label.setTextColor(theme.fg)
        } else {
            theme.styleUi(label, 16f)
        }
        label.text = opt.label
        row.addView(label, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        if (selected) {
            val mark = Label(context).apply {
                text = "●"
                setTextColor(theme.accentColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            row.addView(mark, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(12f).toInt() })
        }
        val ex = opt.example
        if (ex != null && c == null) {
            val exv = TextView(context)
            theme.styleValue(exv)
            exv.text = ex
            row.addView(exv, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(12f).toInt() })
        }
        return row
    }

    private fun attach() {
        rootHost.addView(this, FrameLayout.LayoutParams(-1, -1))
        post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(340).setInterpolator(ExpoOut).start()
            scrimView.animate().alpha(1f).setDuration(220).setInterpolator(ExpoOut).start()
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        scrimView.animate().cancel()
        scrimView.animate().alpha(0f).setDuration(160).start()
        panel.animate().cancel()
        panel.animate().translationY(panel.height.toFloat()).setDuration(240)
            .setInterpolator(ExpoOut).withEndAction {
                (parent as? FrameLayout)?.removeView(this)
            }.start()
        postDelayed({ (parent as? FrameLayout)?.removeView(this) }, 420)
    }
}
