package com.atrium.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * The settings surface. A real preference tree rather than a list of labels:
 * collapsible sections, drill-down pages, switches that exist as objects, and
 * values that update in place instead of rebuilding the world.
 */
class SettingsColumnView(
    context: Context,
    private val registry: SettingsRegistry,
    private val settings: LauncherSettings,
) : ScrollView(context) {

    private class Entry(val page: SettingNode.Page)

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpInt(2f), 0, context.dpInt(36f))
    }

    private val stack = ArrayList<Entry>()
    private val collapsed = HashSet<String>()
    private var currentQuery = ""
    private var rendered = false

    /** Notified with (title, canGoBack) whenever the visible page changes. */
    var onHeader: ((String, Boolean) -> Unit)? = null

    private val usableWidth: Int
        get() = if (container.width > 0) container.width else width

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isFillViewport = true
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshHeader()
    }

    // ---------- Public surface ----------

    fun rebuild() {
        if (rendered) render() else refreshHeader()
    }

    fun setQuery(q: String) {
        if (q == currentQuery) return
        currentQuery = q
        if (rendered) render()
    }

    /** First paint is deferred until the user actually pages toward settings. */
    fun ensureRendered() {
        if (!rendered) render()
    }

    /** Returns true when a drill-down page was closed. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        render()
        scrollTop()
        glide(from = -14f)
        return true
    }

    fun refreshHeader() {
        val back = stack.isNotEmpty()
        val title = when {
            currentQuery.isNotBlank() -> "SEARCH"
            stack.isNotEmpty() -> stack.last().page.title.uppercase()
            else -> "SETTINGS"
        }
        onHeader?.invoke(title, back)
    }

    // ---------- Rendering ----------

    private fun render() {
        rendered = true
        container.removeAllViews()
        if (currentQuery.isNotBlank()) {
            renderSearch()
        } else {
            val page = stack.lastOrNull()?.page
            if (page != null && page.description.isNotEmpty()) addIntro(page.description)
            val nodes = page?.children?.invoke() ?: registry.rootTree
            if (nodes.isEmpty()) addEmpty("Nothing here yet", "Installed apps will appear in this list")
            else renderNodes(nodes)
        }
        refreshHeader()
    }

    private fun renderNodes(nodes: List<SettingNode>) {
        for (node in nodes) {
            if (node is SettingNode.Category) addSection(node)
            else container.addView(row(node), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addSection(cat: SettingNode.Category) {
        val expanded = !collapsed.contains(cat.id)
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val label = TextView(context).apply {
            text = cat.title
            type(10f, 520, if (expanded) Ink.MID else Ink.LOW, mono = true, tracking = 0.22f, caps = true)
        }
        val rule = View(context).apply { setBackgroundColor(Ink.HAIR) }
        val chevron = Chevron(context).also { it.snap(expanded) }

        val header = PressRow(context).apply {
            setPadding(context.dpInt(20f), context.dpInt(22f), context.dpInt(20f), context.dpInt(6f))
        }
        header.addView(label, LinearLayout.LayoutParams(-2, -2))
        header.addView(
            rule,
            LinearLayout.LayoutParams(0, max(1, context.dpInt(0.8f)), 1f).apply {
                marginStart = context.dpInt(12f)
                marginEnd = context.dpInt(12f)
            }
        )
        header.addView(chevron, LinearLayout.LayoutParams(-2, -2))
        section.addView(header, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        cat.children.forEachIndexed { i, child ->
            if (i > 0) body.addView(divider(20f))
            body.addView(row(child), LinearLayout.LayoutParams(-1, -2))
        }
        section.addView(body, LinearLayout.LayoutParams(-1, if (expanded) -2 else 0))
        if (!expanded) body.visibility = View.GONE

        header.setOnClickListener {
            val expand = collapsed.contains(cat.id)
            if (expand) collapsed.remove(cat.id) else collapsed.add(cat.id)
            chevron.expanded = expand
            label.setTextColor(if (expand) Ink.MID else Ink.LOW)
            animateBody(body, expand)
        }

        container.addView(section, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(node: SettingNode): View {
        val padV = context.dpInt(if (settings.compact) 10f else 14f)
        val row = PressRow(context).apply {
            setPadding(context.dpInt(20f), padV, context.dpInt(20f), padV)
            // Controls align with the title line, not the centre of a two-line row.
            gravity = Gravity.TOP
        }

        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(
            TextView(context).apply {
                text = node.title
                type(if (settings.compact) 15f else 16f, 430, Ink.HIGH)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        val sub = node.liveDescription?.invoke() ?: node.description
        if (sub.isNotEmpty()) {
            col.addView(
                TextView(context).apply {
                    text = sub
                    type(12.5f, 380, Ink.FAINT, singleLine = false)
                    setPadding(0, context.dpInt(3f), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2)
            )
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        when (node) {
            is SettingNode.Toggle -> {
                val sw = MiniSwitch(context).apply { snap(node.get()) }
                row.addView(sw, LinearLayout.LayoutParams(-2, -2))
                row.setOnClickListener {
                    val next = !node.get()
                    node.set(next)
                    sw.checked = next
                }
            }

            is SettingNode.Choice -> {
                val value = valueText(node.getCurrent())
                row.addView(value, LinearLayout.LayoutParams(-2, -2))
                row.setOnClickListener {
                    val opts = node.options
                    val idx = opts.indexOf(node.getCurrent())
                    val next = opts[(idx + 1) % opts.size]
                    node.onSelect(next)
                    value.text = node.getCurrent()
                    value.animate().cancel()
                    value.alpha = 0f
                    value.translationY = context.dp(5f)
                    value.animate().alpha(1f)
                        .translationY(0f)
                        .setDuration((220 * settings.animSpeed).toLong().coerceAtLeast(90L))
                        .setInterpolator(ExpoOut)
                        .start()
                }
            }

            is SettingNode.Page -> {
                row.addView(
                    Chevron(context).apply { snap(false) },
                    LinearLayout.LayoutParams(-2, -2)
                )
                row.setOnClickListener { pushPage(node) }
            }

            is SettingNode.Action -> {
                row.addView(valueText(node.verb), LinearLayout.LayoutParams(-2, -2))
                row.setOnClickListener {
                    node.action()
                    render()
                }
            }

            is SettingNode.Info -> {
                row.addView(
                    valueText(node.value(), Ink.FAINT),
                    LinearLayout.LayoutParams(-2, -2)
                )
                row.isClickable = false
            }

            is SettingNode.Category -> Unit
        }
        return row
    }

    private fun renderSearch() {
        val matches = registry.search(currentQuery)
        if (matches.isEmpty()) {
            addEmpty("No matches", "Nothing in settings answers to \u201C$currentQuery\u201D")
            return
        }
        val label = TextView(context).apply {
            text = if (matches.size == 1) "1 setting" else "${matches.size} settings"
            type(10f, 520, Ink.LOW, mono = true, tracking = 0.22f, caps = true)
            setPadding(context.dpInt(20f), context.dpInt(20f), context.dpInt(20f), context.dpInt(8f))
        }
        container.addView(label, LinearLayout.LayoutParams(-1, -2))
        matches.forEachIndexed { i, node ->
            if (i > 0) container.addView(divider(20f))
            container.addView(row(node), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addIntro(text: String) {
        container.addView(
            TextView(context).apply {
                this.text = text
                type(13f, 380, Ink.FAINT, singleLine = false)
                maxLines = 3
                setPadding(context.dpInt(20f), context.dpInt(14f), context.dpInt(20f), context.dpInt(4f))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun addEmpty(title: String, detail: String) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpInt(20f), context.dpInt(30f), context.dpInt(20f), context.dpInt(30f))
        }
        box.addView(
            TextView(context).apply {
                text = title
                type(10f, 520, Ink.LOW, mono = true, tracking = 0.22f, caps = true)
            }
        )
        box.addView(
            TextView(context).apply {
                text = detail
                type(14f, 380, Ink.FAINT, singleLine = false)
                setPadding(0, context.dpInt(7f), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        container.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun valueText(value: String, color: Int = Ink.MID): TextView =
        TextView(context).apply {
            text = value
            type(10.5f, 500, color, mono = true, tracking = 0.14f, caps = true)
            gravity = Gravity.END
            setPadding(context.dpInt(18f), 0, 0, 0)
        }

    private fun divider(insetDp: Float): View {
        val v = View(context)
        v.setBackgroundColor(Ink.HAIR)
        v.layoutParams = LinearLayout.LayoutParams(-1, max(1, context.dpInt(0.8f))).apply {
            marginStart = context.dpInt(insetDp)
            marginEnd = context.dpInt(insetDp)
        }
        return v
    }

    // ---------- Motion ----------

    private fun animateBody(body: LinearLayout, expand: Boolean) {
        val start = body.height
        val target = if (expand) {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(usableWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            body.measuredHeight
        } else {
            0
        }
        if (expand) body.visibility = View.VISIBLE
        body.layoutParams.height = start
        body.requestLayout()
        val anim = ValueAnimator.ofInt(start, target)
        anim.duration = (300 * settings.animSpeed).toLong().coerceIn(110L, 720L)
        anim.interpolator = ExpoOut
        anim.addUpdateListener {
            body.layoutParams.height = it.animatedValue as Int
            body.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (expand) {
                    body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    body.requestLayout()
                } else {
                    body.visibility = View.GONE
                }
            }
        })
        anim.start()
    }

    private fun scrollTop() {
        post { scrollTo(0, 0) }
    }

    private fun glide(from: Float) {
        container.animate().cancel()
        container.alpha = 0f
        container.translationY = context.dp(from)
        container.animate().alpha(1f)
            .translationY(0f)
            .setDuration((300 * settings.animSpeed).toLong().coerceIn(120L, 700L))
            .setInterpolator(ExpoOut)
            .start()
    }

    private fun pushPage(page: SettingNode.Page) {
        stack.add(Entry(page))
        render()
        scrollTop()
        glide(from = 16f)
    }
}
