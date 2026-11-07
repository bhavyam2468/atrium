package com.atrium.launcher

import android.app.Activity
import android.content.pm.PackageManager

sealed class SettingNode(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList()
) {
    class Category(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val children: List<SettingNode>
    ) : SettingNode(id, title, description, tags)

    class Toggle(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    ) : SettingNode(id, title, description, tags)

    class Action(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val action: () -> Unit
    ) : SettingNode(id, title, description, tags)

    class Choice(
        id: String,
        title: String,
        description: String = "",
        tags: List<String> = emptyList(),
        val options: List<String>,
        val getCurrent: () -> String,
        val onSelect: (String) -> Unit
    ) : SettingNode(id, title, description, tags)
}

class SettingsRegistry(
    private val activity: Activity,
    private val settings: LauncherSettings
) {
    val rootTree: List<SettingNode> by lazy {
        listOf(
            SettingNode.Category(
                id = "display",
                title = "Display",
                description = "Status bar and visual presentation",
                tags = listOf("status bar", "fullscreen", "clean", "clock", "battery", "icons"),
                children = listOf(
                    SettingNode.Toggle(
                        id = "hide_status_bar",
                        title = "Hide Status Bar",
                        description = "Completely hide the system status bar for pure immersion",
                        tags = listOf("status bar", "battery", "clock", "notch", "fullscreen"),
                        get = { settings.hideStatusBar },
                        set = { settings.hideStatusBar = it }
                    ),
                    SettingNode.Action(
                        id = "default_home",
                        title = "Default Launcher Settings",
                        description = "Change system default home application",
                        tags = listOf("default", "home", "launcher", "system", "selector"),
                        action = { settings.openDefaultHomeSettings() }
                    )
                )
            ),
            SettingNode.Category(
                id = "animations",
                title = "Animations",
                description = "Blur, speed and physical spring curves",
                tags = listOf("blur", "focus", "speed", "duration", "motion", "spring"),
                children = listOf(
                    SettingNode.Toggle(
                        id = "blur_toggle",
                        title = "Blur-to-Focus Effect",
                        description = "Enable optical blur when launching apps and returning home",
                        tags = listOf("blur", "focus", "sharp", "depth", "render effect"),
                        get = { settings.blurEnabled },
                        set = { settings.blurEnabled = it }
                    ),
                    SettingNode.Choice(
                        id = "anim_speed",
                        title = "Animation Speed",
                        description = "Transition velocity across unlock and launcher gestures",
                        tags = listOf("speed", "fast", "slow", "relaxed", "snappy"),
                        options = listOf("Snappy (0.75x)", "Standard (1.0x)", "Relaxed (1.5x)"),
                        getCurrent = {
                            when (settings.animSpeed) {
                                0.75f -> "Snappy (0.75x)"
                                1.5f -> "Relaxed (1.5x)"
                                else -> "Standard (1.0x)"
                            }
                        },
                        onSelect = {
                            settings.animSpeed = when (it) {
                                "Snappy (0.75x)" -> 0.75f
                                "Relaxed (1.5x)" -> 1.5f
                                else -> 1.0f
                            }
                        }
                    )
                )
            ),
            SettingNode.Category(
                id = "permissions",
                title = "Permissions",
                description = "Manage system access for launcher integrations",
                tags = listOf("permission", "contacts", "storage", "files", "access"),
                children = listOf(
                    SettingNode.Action(
                        id = "perm_contacts",
                        title = "Contacts Search Access",
                        description = "Allow Atrium to search contacts in the universal search bar",
                        tags = listOf("contacts", "phone", "people", "address book", "permission"),
                        action = {
                            if (activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                                activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 101)
                            } else {
                                settings.openAppSettings()
                            }
                        }
                    ),
                    SettingNode.Action(
                        id = "app_details",
                        title = "Atrium App Info",
                        description = "Open Android system details for full permission control",
                        tags = listOf("app info", "details", "storage", "permissions", "all"),
                        action = { settings.openAppSettings() }
                    )
                )
            )
        )
    }

    /**
     * Search across settings using FZF matching against title, description, and tags.
     */
    fun search(q: String): List<SettingNode> {
        val trimmed = q.trim()
        val allLeaves = ArrayList<SettingNode>()
        fun collectLeaves(nodes: List<SettingNode>) {
            for (node in nodes) {
                if (node is SettingNode.Category) {
                    collectLeaves(node.children)
                } else {
                    allLeaves += node
                }
            }
        }
        collectLeaves(rootTree)

        if (trimmed.isEmpty()) return allLeaves

        val scored = ArrayList<Pair<Int, SettingNode>>()
        for (node in allLeaves) {
            var bestScore: Int? = Fzf.score(trimmed, node.title)
            val descScore = Fzf.score(trimmed, node.description)
            if (descScore != null) {
                bestScore = maxOf(bestScore ?: 0, descScore - 5) // slight penalty for description match
            }
            for (tag in node.tags) {
                val tagScore = Fzf.score(trimmed, tag)
                if (tagScore != null) {
                    bestScore = maxOf(bestScore ?: 0, tagScore)
                }
            }
            if (bestScore != null) {
                scored += bestScore to node
            }
        }
        scored.sortByDescending { it.first }
        return scored.map { it.second }
    }
}
