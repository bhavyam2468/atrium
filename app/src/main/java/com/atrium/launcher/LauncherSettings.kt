package com.atrium.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Every user-facing preference lives here, and every write is applied immediately
 * so settings never need a restart to feel real.
 */
class LauncherSettings(private val activity: Activity) {
    private val prefs: SharedPreferences =
        activity.getSharedPreferences("atrium_settings", Context.MODE_PRIVATE)

    private fun put(block: SharedPreferences.Editor.() -> Unit) =
        prefs.edit().apply(block).apply()

    /** 0 = follow the system, 1 = 24-hour, 2 = 12-hour. */
    var clockFormat: Int
        get() = prefs.getInt("clock_format", 0)
        set(v) = put { putInt("clock_format", v) }

    var showDate: Boolean
        get() = prefs.getBoolean("show_date", true)
        set(v) = put { putBoolean("show_date", v) }

    var showSeconds: Boolean
        get() = prefs.getBoolean("show_seconds", false)
        set(v) = put { putBoolean("show_seconds", v) }

    /** 0 = comfortable, 1 = compact. */
    var density: Int
        get() = prefs.getInt("density", 0)
        set(v) = put { putInt("density", v) }

    var hideStatusBar: Boolean
        get() = prefs.getBoolean("hide_status_bar", false)
        set(v) {
            put { putBoolean("hide_status_bar", v) }
            applyBars()
        }

    var hideNavBar: Boolean
        get() = prefs.getBoolean("hide_nav_bar", false)
        set(v) {
            put { putBoolean("hide_nav_bar", v) }
            applyBars()
        }

    var blurEnabled: Boolean
        get() = prefs.getBoolean("blur_enabled", true)
        set(v) = put { putBoolean("blur_enabled", v) }

    /** 0 = subtle, 1 = medium, 2 = deep. */
    var blurStrength: Int
        get() = prefs.getInt("blur_strength", 1)
        set(v) = put { putInt("blur_strength", v) }

    /** Animation duration multiplier: 0.65 = snappy … 1.7 = slow. */
    var animSpeed: Float
        get() = prefs.getFloat("anim_speed", 1.0f)
        set(v) = put { putFloat("anim_speed", v.coerceIn(0.4f, 2.5f)) }

    var staggerReveal: Boolean
        get() = prefs.getBoolean("stagger", true)
        set(v) = put { putBoolean("stagger", v) }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) {
            put { putBoolean("haptics", v) }
            Haptics.enabled = v
        }

    var contactsInSearch: Boolean
        get() = prefs.getBoolean("contacts_search", true)
        set(v) = put { putBoolean("contacts_search", v) }

    /** 0 = Google, 1 = DuckDuckGo, 2 = Brave. */
    var webEngine: Int
        get() = prefs.getInt("web_engine", 0)
        set(v) = put { putInt("web_engine", v) }

    val blurRadius: Float
        get() = when (blurStrength) {
            0 -> 11f
            2 -> 34f
            else -> 21f
        }

    val compact: Boolean get() = density == 1

    fun applyInitial() {
        Haptics.enabled = hapticsEnabled
        applyBars()
    }

    fun applyBars() {
        val controller = activity.window.insetsController ?: return
        if (hideStatusBar) controller.hide(WindowInsets.Type.statusBars())
        else controller.show(WindowInsets.Type.statusBars())

        if (hideNavBar) {
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.navigationBars())
        } else {
            controller.show(WindowInsets.Type.navigationBars())
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
        applyInitial()
    }

    fun versionName(): String {
        val info = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        return info?.versionName ?: "1.0"
    }

    fun openDefaultHomeSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                openAppSettings()
            }
        }
    }

    fun openAppSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    fun requestContactsAccess(requestCode: Int) {
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.READ_CONTACTS),
            requestCode
        )
    }

    fun hasContactsAccess(): Boolean =
        activity.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
