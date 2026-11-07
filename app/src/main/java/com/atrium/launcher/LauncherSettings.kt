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

    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {}
    }
}
