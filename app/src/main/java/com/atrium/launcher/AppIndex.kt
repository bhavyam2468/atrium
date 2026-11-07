package com.atrium.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.View
import java.util.Locale
import java.util.concurrent.Executors

class AppEntry(
    val label: String,
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
)

/**
 * Source of truth for installed launchable activities. Uses LauncherApps (not PackageManager)
 * so work profiles / shortcuts / pinned-shortcut APIs can be layered on later.
 */
class AppIndex(private val activity: Activity) {
    private val la = activity.getSystemService(LauncherApps::class.java)
    private val prefs = activity.getSharedPreferences("atrium", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var apps: List<AppEntry> = emptyList(); private set
    var onChanged: (() -> Unit)? = null
    var onLaunch: (() -> Unit)? = null
    var onLaunchFailed: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reload()
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    init { la.registerCallback(callback, main) }

    fun destroy() { la.unregisterCallback(callback); io.shutdown() }

    fun reload() {
        io.execute {
            val list = ArrayList<AppEntry>(128)
            for (u in la.profiles) for (i: LauncherActivityInfo in la.getActivityList(null, u)) {
                val label = i.label.toString()
                list += AppEntry(label, label.lowercase(Locale.ROOT), i.componentName, u)
            }
            list.sortBy { it.key }
            apps = list
            main.post { onChanged?.invoke() }
        }
    }

    /** Pinned apps shown on home. Defaults resolved by label on first run, then persisted. */
    fun pinned(): List<AppEntry> {
        val all = apps
        if (all.isEmpty()) return emptyList()
        val saved = prefs.getString(PINS, null)
        val comps: List<String> = if (saved != null) saved.split('\n').filter { it.isNotEmpty() } else {
            val d = DEFAULTS.mapNotNull { n -> all.firstOrNull { it.key == n }?.component?.flattenToString() }
            prefs.edit().putString(PINS, d.joinToString("\n")).apply()
            d
        }
        return comps.mapNotNull { f -> all.firstOrNull { it.component.flattenToString() == f } }
    }

    fun launch(e: AppEntry, from: View?) {
        val opts = if (from != null && from.width > 0)
            ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle() else null
        val r = Rect()
        from?.getGlobalVisibleRect(r)
        onLaunch?.invoke()
        try { la.startMainActivity(e.component, e.user, r, opts) } catch (_: Exception) { onLaunchFailed?.invoke() }
    }

    private companion object {
        const val PINS = "pins"
        val DEFAULTS = listOf("youtube", "phonepe", "spotify", "whatsapp")
    }
}
