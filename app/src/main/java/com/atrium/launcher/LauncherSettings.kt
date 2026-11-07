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

/**
 * Device-level concerns that aren't visual tokens: window policy, status bar
 * visibility, and jumping the user into the right system screen.
 */
class LauncherSettings(private val activity: Activity) {

    private val prefs: SharedPreferences =
        activity.getSharedPreferences("atrium_settings", Context.MODE_PRIVATE)

    fun applyInitial() {
        applyBars()
    }

    fun applyBars() {
        val window = activity.window ?: return
        if (window.peekDecorView() == null) return
        val c = try { window.insetsController } catch (_: Exception) { null } ?: return
        val tokens = Layout.tokens
        if (tokens != null) {
            if (tokens.b(K.sysStatusBar)) c.show(WindowInsets.Type.statusBars())
            else c.hide(WindowInsets.Type.statusBars())
            if (tokens.b(K.sysNavBar)) c.show(WindowInsets.Type.navigationBars())
            else c.hide(WindowInsets.Type.navigationBars())
            return
        }
        c.hide(WindowInsets.Type.statusBars())
        c.hide(WindowInsets.Type.navigationBars())
    }

    fun openDefaultHomeSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e2: Exception) {
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
        } catch (e: Exception) {}
    }

    fun pickWallpaper(requestCode: Int = 202) {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {}
    }

    fun persistWallpaper(uri: Uri?) {
        if (uri == null) return
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {}
        Layout.tokens?.set(K.bgWallpaper, uri.toString())
        Layout.tokens?.set(K.bgMode, "wallpaper")
        Layout.tokens?.commit()
    }
}

/** A tiny service locator for things views need but shouldn't own. */
object Layout {
    @Volatile var tokens: Tokens? = null
    @Volatile var settings: LauncherSettings? = null
    @Volatile var index: AppIndex? = null
    @Volatile var root: HomeRoot? = null
    @Volatile var registry: SettingsRegistry? = null
}

/** Bridges the token store to SharedPreferences. */
class PrefsStore(private val prefs: SharedPreferences) : Store {
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String) { prefs.edit().putString(KEY, value).apply() }

    companion object { const val KEY = "token_map" }
}
