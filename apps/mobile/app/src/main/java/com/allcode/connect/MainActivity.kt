package com.allcode.connect

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.data.ThemeMode
import com.allcode.connect.ui.AppNav
import com.allcode.connect.ui.theme.AllCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Links the app was opened with: a pairing QR, or a notification for a thread. */
sealed interface LaunchLink {
    data class Pair(val raw: String) : LaunchLink
    data class Thread(val environmentId: String, val threadId: String) : LaunchLink
}

class MainActivity : ComponentActivity() {
    private val links = MutableStateFlow<LaunchLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val prefs = graph.prefs
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val preferences by prefs.state.collectAsStateWithLifecycle()
            SideEffect { com.allcode.connect.ui.components.Haptics.enabled = preferences.haptics }
            val dark = when (preferences.theme) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            // System bar icons follow the app's theme, not only the system's.
            DisposableEffect(dark) {
                val transparent = Color.Transparent.toArgb()
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AllCodeTheme(preferences.theme) {
                AppNav(graph, links)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        when {
            data.host == "pair" -> links.value = LaunchLink.Pair(data.toString())
            data.scheme == "allcode" && data.host == "thread" -> {
                val env = data.getQueryParameter("env") ?: return
                val id = data.getQueryParameter("id") ?: return
                links.value = LaunchLink.Thread(env, id)
            }
        }
    }
}
