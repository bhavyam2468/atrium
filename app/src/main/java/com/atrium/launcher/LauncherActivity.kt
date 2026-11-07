package com.atrium.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class LauncherActivity : Activity() {
    private lateinit var index: AppIndex
    private lateinit var settings: LauncherSettings
    private lateinit var registry: SettingsRegistry
    private lateinit var root: HomeRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            if (Build.VERSION.SDK_INT >= 29) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
        }

        settings = LauncherSettings(this)
        index = AppIndex(this)
        registry = SettingsRegistry(this, settings, index)

        val engine = SearchEngine(
            listOf(
                AppSearch(index),
                ContactsSearch(this, settings),
                WebSearch(settings) { q, _ ->
                    root.blurOut()
                    try {
                        startActivity(webIntent(q, settings.webEngine))
                    } catch (_: Exception) {
                        root.playIntro()
                    }
                },
            )
        )

        root = HomeRoot(this, engine, index, settings, registry)
        root.prepareIntro()
        setContentView(root)

        registry.onApply = { root.refresh() }
        registry.onStructure = { root.refreshSettings() }
        index.onChanged = {
            root.refresh()
            root.refreshSettings()
        }
        index.onLaunch = { root.blurOut() }
        index.onLaunchFailed = { root.playIntro() }
        index.onPinsChanged = {
            root.refresh()
            root.refreshSettings()
        }

        // Always white-on-black system chrome, in every appearance.
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        settings.applyInitial()
        index.reload()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { root.onBack() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SettingsRegistry.REQUEST_CONTACTS) {
            root.refresh()
            root.refreshSettings()
        }
    }

    @Deprecated("Pre-33 back handling")
    override fun onBackPressed() {
        if (!root.onBack()) super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) root.onBack()
    }

    override fun onResume() {
        super.onResume()
        settings.applyBars()
        Haptics.enabled = settings.hapticsEnabled
        root.playIntro()
    }

    override fun onStop() {
        super.onStop()
        root.reset()
        root.prepareIntro()
    }

    override fun onDestroy() {
        super.onDestroy()
        index.destroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
