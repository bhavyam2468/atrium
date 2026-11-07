package com.atrium.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
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
 * Source of truth for installed launchable activities. Uses LauncherApps (not
 * PackageManager) so work profiles / shortcuts can be layered on later.
 */
class AppIndex(private val activity: Activity) {
    private val la = activity.getSystemService(LauncherApps::class.java)
    private val prefs = activity.getSharedPreferences("atrium", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable?>(128)

    @Volatile var apps: List<AppEntry> = emptyList(); private set
    var onChanged: (() -> Unit)? = null
    var onLaunch: (() -> Unit)? = null
    var onLaunchFailed: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) {
            synchronized(iconCache) { iconCache.clear() }
            reload()
        }
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) {
            synchronized(iconCache) { iconCache.clear() }
            reload()
        }
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    init { la.registerCallback(callback, main) }

    fun destroy() {
        try { la.unregisterCallback(callback) } catch (e: Exception) {}
        io.shutdown()
    }

    fun reload() {
        io.execute {
            val list = ArrayList<AppEntry>(160)
            try {
                for (u in la.profiles) for (i: LauncherActivityInfo in la.getActivityList(null, u)) {
                    val label = i.label.toString()
                    if (label.isEmpty()) continue
                    list += AppEntry(label, label.lowercase(Locale.ROOT), i.componentName, u)
                }
            } catch (e: Exception) {}
            list.sortBy { it.key }
            apps = list
            main.post { onChanged?.invoke() }
        }
    }

    /** Icons are large and expensive; cache per component. */
    fun icon(e: AppEntry): Drawable? {
        val key = e.component.flattenToString()
        synchronized(iconCache) {
            if (iconCache.containsKey(key)) return iconCache[key]
        }
        val dr = try {
            activity.packageManager.getActivityIcon(e.component)
        } catch (ex: Exception) {
            null
        }
        synchronized(iconCache) { iconCache[key] = dr }
        return dr
    }

    /** Pinned apps shown on home, resolved by label on first run then persisted. */
    fun pinned(): List<AppEntry> {
        val all = apps
        if (all.isEmpty()) return emptyList()
        val saved = prefs.getString(PINS, null)
        val comps: List<String> = if (saved != null) {
            saved.split('\n').filter { it.isNotEmpty() }
        } else {
            val d = DEFAULTS.mapNotNull { n -> all.firstOrNull { it.key == n }?.component?.flattenToString() }
                .ifEmpty { all.take(6).map { it.component.flattenToString() } }
            prefs.edit().putString(PINS, d.joinToString("\n")).apply()
            d
        }
        return comps.mapNotNull { f -> all.firstOrNull { it.component.flattenToString() == f } }
    }

    fun setPinned(entries: List<AppEntry>) {
        prefs.edit().putString(PINS, entries.joinToString("\n") { it.component.flattenToString() }).apply()
        onChanged?.invoke()
    }

    fun isPinned(e: AppEntry): Boolean =
        prefs.getString(PINS, "")?.split('\n')?.contains(e.component.flattenToString()) == true

    fun togglePin(e: AppEntry) {
        val cur = pinned().toMutableList()
        if (cur.any { it.component == e.component }) cur.removeAll { it.component == e.component }
        else cur.add(e)
        setPinned(cur)
    }

    fun launch(e: AppEntry, from: View?) {
        val opts = if (from != null && from.width > 0) {
            try {
                ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
            } catch (ex: Exception) { null }
        } else null
        val r = Rect()
        from?.getGlobalVisibleRect(r)
        onLaunch?.invoke()
        try {
            la.startMainActivity(e.component, e.user, r, opts)
        } catch (ex: Exception) {
            onLaunchFailed?.invoke()
        }
    }

    private companion object {
        const val PINS = "pins"
        val DEFAULTS = listOf("youtube", "phonepe", "spotify", "whatsapp", "maps", "camera")
    }
}
