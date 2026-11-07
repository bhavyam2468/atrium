package com.atrium.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings column. Bottom-anchored like every other column (fillViewport
 * + bottom gravity keeps rows rising from the search bar), with page-drill
 * navigation: root → area → page of leaves, each push sliding in from the
 * right. Search flattens the whole tree into ranked rows, and a "recently
 * adjusted" section on the root page keeps frequent tweaks one tap away.
 */
class SettingsColumnView(
    context: Context,
    private val registry: SettingsRegistry,
    private val theme: AtriumTheme,
    private val sheetHost: FrameLayout
) : ScrollView(context) {

    private class Page(val title: String, val nodes: List<SettingNode>)

    private val pages = ArrayList<Page>()
    private var query = ""
    private var dirty = false

    private val header = Label(context)
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isFillViewport = true
        pages.add(Page("Settings", registry.rootTree))
        container.addView(header, LinearLayout.LayoutParams(-1, -2))
        container.addView(list, LinearLayout.LayoutParams(-1, -2))
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuild()
    }

    /* ------------------------------ query ------------------------------- */

    fun setQuery(q: String) {
        if (query == q) return
        query = q
        rebuild(slide = 0)
    }

    /* ---------------------------- navigation ---------------------------- */

    fun pushPage(cat: SettingNode.Category) {
        pages.add(Page(cat.title, cat.children))
        rebuild(slide = 1)
    }

    fun popPage() {
        if (pages.size > 1) {
            pages.removeAt(pages.size - 1)
            rebuild(slide = -1)
        }
    }

    /** Returns true if it consumed a back press. */
    fun consumeBack(): Boolean {
        for (i in 0 until sheetHost.childCount) {
            val c = sheetHost.getChildAt(i)
            if (c is OptionSheet) { c.dismiss(); return true }
        }
        if (pages.size > 1) { popPage(); return true }
        return false
    }

    /* ------------------------------ render ------------------------------ */

    fun markDirty() {
        if (isShown) rebuild(slide = 0) else dirty = true
    }

    fun rebuildIfDirty() {
        if (dirty) { dirty = false; rebuild(slide = 0) }
    }

    private fun rebuild(slide: Int) {
        dirty = false
        rebuildHeader()
        list.removeAllViews()

        if (query.isNotBlank()) {
            val matches = registry.search(query)
            if (matches.isEmpty()) {
                val empty = TextView(context).apply {
                    text = "nothing here"
                    theme.styleValue(this)
                    gravity = Gravity.CENTER
                    setPadding(0, context.dp(20f).toInt(), 0, context.dp(20f).toInt())
                }
                list.addView(empty)
            } else {
                matches.forEachIndexed { i, n ->
                    list.addView(SettingsUi.renderLeaf(context, n, sheetHost, theme))
                    if (i < matches.size - 1) list.addView(hairline(context, theme.colorAt(0.08f)))
                }
            }
        } else {
            val page = pages[pages.size - 1]
            val nodes = page.nodes
            nodes.forEachIndexed { i, n ->
                if (n is SettingNode.Category) {
                    list.addView(categoryRow(n))
                } else {
                    list.addView(SettingsUi.renderLeaf(context, n, sheetHost, theme))
                }
                if (i < nodes.size - 1) list.addView(hairline(context, theme.colorAt(0.08f)))
            }
            if (pages.size == 1) appendRecent()
        }

        if (slide != 0) {
            val from = if (slide > 0) context.dp(26f) else -context.dp(26f)
            container.translationX = from
            container.alpha = 0f
            container.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(ExpoOut).start()
        }
    }

    private fun rebuildHeader() {
        theme.styleHeader(header)
        header.setPadding(
            context.dp(16f).toInt(), context.dp(4f).toInt(),
            context.dp(16f).toInt(), context.dp(10f).toInt()
        )
        if (query.isNotBlank()) {
            header.text = "RESULTS"
            header.setOnClickListener(null)
        } else if (pages.size > 1) {
            val title = pages[pages.size - 1].title.uppercase()
            header.text = "‹ $title"
            header.setOnClickListener { popPage() }
        } else {
            header.text = "SETTINGS"
            header.setOnClickListener(null)
        }
    }

    private fun categoryRow(cat: SettingNode.Category): View {
        val row = PressableRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                context.dp(16f).toInt(), context.dp(11f).toInt(),
                context.dp(16f).toInt(), context.dp(11f).toInt()
            )
            isClickable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                pushPage(cat)
            }
        }
        val title = Label(context)
        theme.styleUi(title, 16f)
        title.text = cat.title
        row.addView(title, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        val chev = Label(context).apply {
            theme.styleValue(this)
            text = "→"
        }
        row.addView(chev, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(12f).toInt() })
        return row
    }

    private fun appendRecent() {
        val recent = registry.recentNodes()
        if (recent.isEmpty()) return
        val label = TextView(context).apply {
            text = "RECENT"
            theme.styleHeader(this)
            setPadding(
                context.dp(16f).toInt(), context.dp(14f).toInt(),
                context.dp(16f).toInt(), context.dp(6f).toInt()
            )
        }
        list.addView(label, LinearLayout.LayoutParams(-1, -2))
        recent.forEachIndexed { i, n ->
            list.addView(SettingsUi.renderLeaf(context, n, sheetHost, theme))
            if (i < recent.size - 1) list.addView(hairline(context, theme.colorAt(0.08f)))
        }
    }
}
