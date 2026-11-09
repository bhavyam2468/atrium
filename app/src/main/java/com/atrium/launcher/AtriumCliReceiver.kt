package com.atrium.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Atrium ADB Remote Control Receiver.
 * Receives intent: adb shell am broadcast -a com.atrium.launcher.CLI --es cmd "<command>"
 * Sets resultData with confirmation/output for ADB stdout.
 */
class AtriumCliReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rawCmd = intent.getStringExtra("cmd")
            ?: intent.getStringExtra("command")
            ?: intent.getStringExtra("q")
            ?: "help"

        val activity = LauncherActivity.instance
        if (activity == null) {
            setResult(android.app.Activity.RESULT_OK, "ERROR: LauncherActivity is not running or active.", null)
            return
        }

        val output = try {
            execute(activity, rawCmd.trim())
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }

        setResult(android.app.Activity.RESULT_OK, output, null)
    }

    private fun execute(activity: LauncherActivity, cmd: String): String {
        val parts = cmd.split("\\s+".toRegex(), limit = 2)
        val verb = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        val root = activity.root
        val index = activity.index
        val settings = activity.settings
        val engine = activity.engine

        return when (verb) {
            "help" -> """
                Atrium ADB CLI Commands:
                - status
                - search <query>
                - open_app <name|pkg>
                - list_favorites
                - add_favorite <name|pkg>
                - remove_favorite <name|pkg>
                - open_settings
                - close_settings
                - open_search [query]
                - close_search
                - get_setting <key>
                - set_setting <key> <value>
            """.trimIndent()

            "status" -> {
                val open = root.open
                val settingsOpen = root.isSettingsOpen
                val query = root.input.text.toString()
                val pins = index.pinned().joinToString(", ") { it.label }
                val totalApps = index.apps.size
                """
                    status: ok
                    search_open: $open
                    settings_open: $settingsOpen
                    current_query: "$query"
                    favorites: [$pins]
                    total_apps: $totalApps
                    hide_status_bar: ${settings.hideStatusBar}
                    blur_enabled: ${settings.blurEnabled}
                    anim_speed: ${settings.animSpeed}x
                """.trimIndent()
            }

            "search" -> {
                root.openSearch()
                root.input.setText(arg)
                root.input.setSelection(arg.length)
                root.refreshResults()
                val cols = engine.query(arg, 10)
                if (cols.isEmpty()) {
                    "search: 0 results for \"$arg\""
                } else {
                    val sb = StringBuilder("search: results for \"$arg\":\n")
                    for (c in cols) {
                        sb.append("  [${c.title}]: ")
                        sb.append(c.results.joinToString(", ") { it.title })
                        sb.append("\n")
                    }
                    sb.toString().trimEnd()
                }
            }

            "open_app" -> {
                if (arg.isEmpty()) return "ERROR: specify app name or package"
                val app = index.apps.firstOrNull {
                    it.label.equals(arg, ignoreCase = true) ||
                        it.key == arg.lowercase() ||
                        it.component.packageName.equals(arg, ignoreCase = true)
                } ?: return "ERROR: app \"$arg\" not found"
                index.launch(app, null)
                "ok: launched ${app.label} (${app.component.packageName})"
            }

            "list_favorites" -> {
                val pins = index.pinned()
                if (pins.isEmpty()) "favorites: none"
                else "favorites (${pins.size}): " + pins.joinToString(", ") { it.label }
            }

            "add_favorite" -> {
                if (arg.isEmpty()) return "ERROR: specify app name or package"
                val app = index.apps.firstOrNull {
                    it.label.equals(arg, ignoreCase = true) ||
                        it.key == arg.lowercase() ||
                        it.component.packageName.equals(arg, ignoreCase = true)
                } ?: return "ERROR: app \"$arg\" not found"
                index.addPin(app)
                root.refreshPins()
                "ok: added ${app.label} to favorites"
            }

            "remove_favorite" -> {
                if (arg.isEmpty()) return "ERROR: specify app name or package"
                val app = index.apps.firstOrNull {
                    it.label.equals(arg, ignoreCase = true) ||
                        it.key == arg.lowercase() ||
                        it.component.packageName.equals(arg, ignoreCase = true)
                } ?: return "ERROR: app \"$arg\" not found"
                index.removePin(app)
                root.refreshPins()
                "ok: removed ${app.label} from favorites"
            }

            "open_settings" -> {
                root.openSettings()
                "ok: settings opened"
            }

            "close_settings" -> {
                root.closeSettings()
                "ok: settings closed"
            }

            "open_search" -> {
                root.openSearch()
                if (arg.isNotEmpty()) {
                    root.input.setText(arg)
                    root.input.setSelection(arg.length)
                    root.refreshResults()
                }
                "ok: search opened"
            }

            "close_search" -> {
                root.closeSearch()
                "ok: search closed"
            }

            "get_setting" -> {
                when (arg.lowercase()) {
                    "hide_status_bar" -> "hide_status_bar = ${settings.hideStatusBar}"
                    "blur_enabled" -> "blur_enabled = ${settings.blurEnabled}"
                    "anim_speed" -> "anim_speed = ${settings.animSpeed}"
                    "fallback_order" -> "fallback_order = ${settings.fallbackEnginesOrder}"
                    else -> "ERROR: unknown setting key \"$arg\""
                }
            }

            "set_setting" -> {
                val kv = arg.split("\\s+".toRegex(), limit = 2)
                if (kv.size < 2) return "ERROR: format is set_setting <key> <value>"
                val key = kv[0].lowercase()
                val value = kv[1]
                when (key) {
                    "hide_status_bar" -> {
                        settings.hideStatusBar = value.toBoolean()
                        "ok: hide_status_bar set to ${settings.hideStatusBar}"
                    }
                    "blur_enabled" -> {
                        settings.blurEnabled = value.toBoolean()
                        "ok: blur_enabled set to ${settings.blurEnabled}"
                    }
                    "anim_speed" -> {
                        settings.animSpeed = value.toFloatOrNull() ?: 1.0f
                        "ok: anim_speed set to ${settings.animSpeed}"
                    }
                    else -> "ERROR: unsupported setting key \"$key\""
                }
            }

            else -> "ERROR: unknown command \"$verb\". Run \"help\" for usage."
        }
    }
}
