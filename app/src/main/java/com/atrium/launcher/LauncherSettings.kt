package com.atrium.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController

class LauncherSettings(private val activity: Activity) {
    private val prefs: SharedPreferences = activity.getSharedPreferences("atrium_settings", Context.MODE_PRIVATE)

    var hideStatusBar: Boolean
        get() = prefs.getBoolean("hide_status_bar", false)
        set(value) {
            prefs.edit().putBoolean("hide_status_bar", value).apply()
            applyStatusBar(value)
        }

    var blurEnabled: Boolean
        get() = prefs.getBoolean("blur_enabled", true)
        set(value) = prefs.edit().putBoolean("blur_enabled", value).apply()

    // Animation duration multiplier: 0.5f (fast), 1.0f (normal), 1.5f (relaxed)
    var animSpeed: Float
        get() = prefs.getFloat("anim_speed", 1.0f)
        set(value) = prefs.edit().putFloat("anim_speed", value).apply()

    val allFallbackEngines = listOf(
        FallbackEngine("web", "Web", null) { q ->
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        },
        FallbackEngine("youtube", "YouTube", "com.google.android.youtube") { q ->
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        },
        FallbackEngine("spotify", "Spotify", "com.spotify.music") { q ->
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))
        },
        FallbackEngine("maps", "Maps", "com.google.android.apps.maps") { q ->
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
        },
        FallbackEngine("playstore", "Play Store", "com.android.vending") { q ->
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q)))
        }
    )

    fun isEngineInstalled(engine: FallbackEngine): Boolean {
        val pkg = engine.packageName ?: return true
        return try {
            activity.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getInstalledFallbackEngines(): List<FallbackEngine> {
        return allFallbackEngines.filter { isEngineInstalled(it) }
    }

    var fallbackEnginesOrder: String
        get() = prefs.getString("fallback_engines_order", "web,youtube,spotify,maps,playstore") ?: "web,youtube,spotify,maps,playstore"
        set(value) = prefs.edit().putString("fallback_engines_order", value).apply()

    fun isEngineEnabled(id: String): Boolean = prefs.getBoolean("fallback_engine_$id", true)

    fun setEngineEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("fallback_engine_$id", enabled).apply()
    }

    fun getActiveFallbackEngines(): List<FallbackEngine> {
        val installed = getInstalledFallbackEngines().associateBy { it.id }
        val order = fallbackEnginesOrder.split(',').filter { it.isNotEmpty() }
        val result = ArrayList<FallbackEngine>()
        for (id in order) {
            val engine = installed[id]
            if (engine != null && isEngineEnabled(id)) {
                result.add(engine)
            }
        }
        for (engine in installed.values) {
            if (engine !in result && isEngineEnabled(engine.id)) {
                result.add(engine)
            }
        }
        return result
    }

    fun moveEngine(id: String, up: Boolean) {
        val list = fallbackEnginesOrder.split(',').filter { it.isNotEmpty() }.toMutableList()
        val idx = list.indexOf(id)
        if (idx == -1) return
        val targetIdx = if (up) idx - 1 else idx + 1
        if (targetIdx in list.indices) {
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            fallbackEnginesOrder = list.joinToString(",")
        }
    }

    fun applyInitial() {
        applyStatusBar(hideStatusBar)
    }

    private fun applyStatusBar(hide: Boolean) {
        val controller = activity.window.insetsController ?: return
        if (hide) {
            controller.hide(WindowInsets.Type.statusBars())
        } else {
            controller.show(WindowInsets.Type.statusBars())
        }
    }

    fun openDefaultHomeSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (_: Exception) {
                openAppSettings()
            }
        }
    }

    fun openAppSettings(packageName: String = activity.packageName) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {}
    }
}

data class FallbackEngine(
    val id: String,
    val name: String,
    val packageName: String?,
    val intentBuilder: (String) -> Intent
)
