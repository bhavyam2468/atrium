package com.atrium.launcher

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Clean, minimal dropdown tree view for launcher settings.
 * Stacked bottom-to-top so items rise upward from the search bar.
 */
class SettingsColumnView(
    context: Context,
    private val registry: SettingsRegistry
) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private val expandedCategories = HashSet<String>()
    private var currentQuery: String = ""

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Initially expand categories by default for easy discovery
        registry.rootTree.forEach { if (it is SettingNode.Category) expandedCategories.add(it.id) }
        rebuild()
    }

    fun setQuery(q: String) {
        if (currentQuery != q) {
            currentQuery = q
            rebuild()
        }
    }

    fun rebuild() {
        container.removeAllViews()

        if (currentQuery.isBlank()) {
            // Render structured dropdown tree
            for (node in registry.rootTree) {
                renderNode(node, depth = 0)
            }
        } else {
            // Render filtered search results matching query
            val matches = registry.search(currentQuery)
            if (matches.isEmpty()) {
                val empty = TextView(context).apply {
                    text = "No settings found"
                    setTextColor(0x66FFFFFF)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    setPadding(0, context.dp(20f).toInt(), 0, context.dp(20f).toInt())
                }
                container.addView(empty)
            } else {
                for (leaf in matches) {
                    renderLeaf(leaf, depth = 0)
                }
            }
        }
    }

    private fun renderNode(node: SettingNode, depth: Int) {
        when (node) {
            is SettingNode.Category -> {
                val isExpanded = expandedCategories.contains(node.id)
                val catHeader = Label(context).apply {
                    val prefix = if (isExpanded) "▾ " else "▸ "
                    text = "$prefix${node.title.uppercase()}"
                    setTextColor(0x99FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(context.dp(8f).toInt() + depth * context.dp(16f).toInt(), context.dp(10f).toInt(), context.dp(8f).toInt(), context.dp(6f).toInt())
                    setOnClickListener {
                        if (isExpanded) expandedCategories.remove(node.id) else expandedCategories.add(node.id)
                        rebuild()
                    }
                }
                container.addView(catHeader)

                if (isExpanded) {
                    for (child in node.children) {
                        renderNode(child, depth + 1)
                    }
                }
            }
            else -> renderLeaf(node, depth)
        }
    }

    private fun renderLeaf(node: SettingNode, depth: Int) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = context.dp(12f).toInt() + depth * context.dp(14f).toInt()
            setPadding(padH, context.dp(8f).toInt(), context.dp(12f).toInt(), context.dp(8f).toInt())
            isClickable = true
            isFocusable = true
        }

        val titleView = Label(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

        val descView = TextView(context).apply {
            text = node.description
            setTextColor(0x66FFFFFF)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(2f).toInt(), 0, 0)
        }

        when (node) {
            is SettingNode.Toggle -> {
                val stateText = if (node.get()) " [ON]" else " [OFF]"
                titleView.text = "${node.title}$stateText"
                row.setOnClickListener {
                    node.set(!node.get())
                    rebuild()
                }
            }
            is SettingNode.Choice -> {
                titleView.text = "${node.title}: ${node.getCurrent()}"
                row.setOnClickListener {
                    val opts = node.options
                    val curIdx = opts.indexOf(node.getCurrent())
                    val nextOpt = opts[(curIdx + 1) % opts.size]
                    node.onSelect(nextOpt)
                    rebuild()
                }
            }
            is SettingNode.Action -> {
                titleView.text = "${node.title} →"
                row.setOnClickListener {
                    node.action()
                }
            }
            is SettingNode.Category -> {}
        }

        row.addView(titleView)
        if (node.description.isNotEmpty()) {
            row.addView(descView)
        }
        container.addView(row)
    }
}
