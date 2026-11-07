package com.atrium.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class LauncherActivity : Activity() {

    private lateinit var index: AppIndex
    private lateinit var settings: LauncherSettings
    private lateinit var registry: SettingsRegistry
    private lateinit var tokens: Tokens
    private lateinit var root: HomeRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        tokens = Tokens(PrefsStore(getSharedPreferences("atrium_tokens", MODE_PRIVATE)))
        Main.post = { r -> runOnUiThread(r) }
        settings = LauncherSettings(this)
        registry = SettingsRegistry(this, tokens, settings)
        index = AppIndex(this)

        Layout.tokens = tokens
        Layout.settings = settings
        Layout.index = index
        Layout.registry = registry

        val contacts = ContactsSearch(this)
        val web = WebSearch { q, _ ->
            root.blurOut()
            try { startActivity(webIntent(q)) } catch (e: Exception) { root.playIntro() }
        }
        // Columns are search providers that can be switched off by a token.
        val contactsProvider = GatedProvider(contacts) { tokens.b(K.sysColumnContacts) }
        val webProvider = GatedProvider(web) { tokens.b(K.sysColumnWeb) }
        val engine = SearchEngine(listOf(AppSearch(index), contactsProvider, webProvider))

        root = HomeRoot(this, engine, index, settings, tokens, registry)
        Layout.root = root
        root.prepareIntro()
        setContentView(root)

        settings.applyInitial()

        try {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } catch (_: Exception) {}

        index.onChanged = { root.refresh() }
        index.onLaunch = { root.blurOut() }
        index.onLaunchFailed = { root.playIntro() }
        index.reload()

        // haptics flag mirrored where Haptics can reach it cheaply
        getSharedPreferences("atrium_tokens", MODE_PRIVATE).edit()
            .putBoolean("haptics", tokens.b(K.motionHaptics)).apply()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { root.onBack() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            root.refresh()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 202 && resultCode == RESULT_OK) {
            settings.persistWallpaper(data?.data)
            root.applyTheme()
        }
    }

    override fun onResume() {
        super.onResume()
        root.playIntro()
        getSharedPreferences("atrium_tokens", MODE_PRIVATE).edit()
            .putBoolean("haptics", tokens.b(K.motionHaptics)).apply()
        settings.applyBars()
    }

    override fun onStop() {
        super.onStop()
        root.reset()
        root.prepareIntro()
    }

    override fun onDestroy() {
        super.onDestroy()
        index.destroy()
        Layout.root = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.applyTheme()
    }
}

/** Small helper used by the settings deck for system-level rows. */
fun Activity.openUri(uri: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (e: Exception) {}
}
