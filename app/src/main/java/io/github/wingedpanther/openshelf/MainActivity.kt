package io.github.wingedpanther.openshelf

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tls.init(this)
        Prefs.init(this)
        ManualLinks.init(this)
        Lock.init(this)
        L.init(this)
        Images.init(this)
        Prefs.systemDark = isNight(resources.configuration)
        Links.pending = intent?.data
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (L.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { OpenShelf() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { Links.pending = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Prefs.systemDark = isNight(newConfig)
    }

    private fun isNight(c: Configuration) = c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onResume() {
        super.onResume()
        Installer.onScreen = true
        Device.tick++
    }

    override fun onPause() {
        super.onPause()
        Installer.onScreen = false
    }
}
