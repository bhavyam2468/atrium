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

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    var onChanged: (() -> Unit)? = null
    var onLaunch: (() -> Unit)? = null
    var onLaunchFailed: (() -> Unit)? = null
    var onPinsChanged: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reload()
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    init {
        la.registerCallback(callback, main)
    }

    fun destroy() {
        la.unregisterCallback(callback)
        io.shutdown()
    }

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

    // ---------- Pins ----------

    /** Persisted pinned components, seeded from defaults on first run. */
    fun pinnedComponents(): List<String> {
        val saved = prefs.getString(PINS, null) ?: return seedDefaults()
        return saved.split('\n').filter { it.isNotEmpty() }
    }

    private fun seedDefaults(): List<String> {
        // Never persist a seed derived from an empty index — the first app scan would
        // otherwise leave the home screen permanently bare.
        if (apps.isEmpty()) return emptyList()
        val d = DEFAULTS.mapNotNull { n -> apps.firstOrNull { it.key == n }?.component?.flattenToString() }
        prefs.edit().putString(PINS, d.joinToString("\n")).apply()
        return d
    }

    fun isPinned(entry: AppEntry): Boolean =
        pinnedComponents().contains(entry.component.flattenToString())

    fun setPinned(entry: AppEntry, pinned: Boolean) {
        val current = pinnedComponents().toMutableList()
        val id = entry.component.flattenToString()
        if (pinned) {
            if (!current.contains(id)) current.add(id)
        } else {
            current.remove(id)
        }
        prefs.edit().putString(PINS, current.joinToString("\n")).apply()
        onPinsChanged?.invoke()
    }

    fun togglePin(entry: AppEntry) = setPinned(entry, !isPinned(entry))

    fun resetPins() {
        prefs.edit().remove(PINS).apply()
        seedDefaults()
        onPinsChanged?.invoke()
    }

    /** Resolved pinned apps, in the order the user arranged them. */
    fun pinned(): List<AppEntry> {
        val all = apps
        if (all.isEmpty()) return emptyList()
        return pinnedComponents().mapNotNull { f -> all.firstOrNull { it.component.flattenToString() == f } }
    }

    /** Unique apps, for the pin picker. */
    fun uniqueApps(): List<AppEntry> = apps.distinctBy { it.component.flattenToString() }

    // ---------- Launch ----------

    fun launch(e: AppEntry, from: View?) {
        val opts = if (from != null && from.width > 0)
            ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        else null
        val r = Rect()
        from?.getGlobalVisibleRect(r)
        onLaunch?.invoke()
        try {
            la.startMainActivity(e.component, e.user, r, opts)
        } catch (_: Exception) {
            onLaunchFailed?.invoke()
        }
    }

    private companion object {
        const val PINS = "pins"
        val DEFAULTS = listOf("youtube", "phonepe", "spotify", "whatsapp")
    }
}
