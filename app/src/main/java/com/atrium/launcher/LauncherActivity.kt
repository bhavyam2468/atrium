package com.atrium.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class LauncherActivity : Activity() {
    lateinit var index: AppIndex; private set
    lateinit var settings: LauncherSettings; private set
    lateinit var registry: SettingsRegistry; private set
    lateinit var engine: SearchEngine; private set
    lateinit var root: HomeRoot; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.apply {
            setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        settings = LauncherSettings(this)
        registry = SettingsRegistry(this, settings)
        index = AppIndex(this)

        engine = SearchEngine(listOf(
            AppSearch(index),
            ContactsSearch(this),
            FallbackSearchProvider(settings) { intent, _ ->
                root.blurOut()
                try { startActivity(intent) } catch (_: Exception) { root.playIntro() }
            }
        ))

        root = HomeRoot(this, engine, index, settings, registry)
        root.prepareIntro()
        setContentView(root)

        settings.applyInitial()

        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        index.onChanged = { root.refresh() }
        index.onLaunch = { root.blurOut() }
        index.onLaunchFailed = { root.playIntro() }
        index.reload()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                root.onBack()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            root.refresh()
        }
    }

    @Deprecated("Pre-33 back handling")
    override fun onBackPressed() {
        if (!root.onBack()) {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) root.onBack()
    }

    override fun onResume() {
        super.onResume()
        root.playIntro()
    }

    override fun onStop() {
        super.onStop()
        root.reset()
        root.prepareIntro()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        index.destroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    companion object {
        var instance: LauncherActivity? = null
            private set
    }
}
