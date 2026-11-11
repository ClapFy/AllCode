package com.allcode.connect.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.data.AppPreferences
import com.allcode.connect.data.ThemeMode
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.AllCodeMark
import com.allcode.connect.ui.components.Pill
import com.allcode.connect.ui.components.SectionLabel
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.theme.AllCode
import com.allcode.connect.ui.thread.RUNTIME_MODES

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator) {
    val prefs by graph.prefs.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun update(transform: (AppPreferences) -> AppPreferences) = graph.prefs.update(transform)

    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        TopBar("Settings", onBack = navigator::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionLabel("Appearance")
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(ThemeMode.System, "System", Icons.Rounded.PhoneAndroid),
                    Triple(ThemeMode.Light, "Light", Icons.Rounded.LightMode),
                    Triple(ThemeMode.Dark, "Dark", Icons.Rounded.DarkMode),
                ).forEach { (mode, label, icon) ->
                    Pill(
                        label,
                        icon = icon,
                        container = if (prefs.theme == mode) AllCode.colors.ink else AllCode.colors.inset,
                        tone = if (prefs.theme == mode) AllCode.colors.paper else AllCode.colors.inkSoft,
                    ) { update { it.copy(theme = mode) } }
                }
            }

            SectionLabel("Notifications")
            Toggle("When an agent needs you", "Approvals, questions, and plans waiting for review.", prefs.notifyAttention) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                update { it.copy(notifyAttention = on) }
            }
            Toggle("When work finishes", "A turn completed or stopped with an error.", prefs.notifyFinished) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                update { it.copy(notifyFinished = on) }
            }
            Toggle(
                "Stay connected while agents work",
                "Keeps a quiet notification up so updates reach you when the app is closed. Stops on its own when everything is idle.",
                prefs.stayConnected,
            ) { on -> update { it.copy(stayConnected = on) } }
            Row(
                Modifier.fillMaxWidth().pressable {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }.padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text("Notification channels…", style = AllCode.type.body, color = AllCode.colors.clay) }

            SectionLabel("New tasks")
            Text("Default permissions", style = AllCode.type.bodyStrong, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RUNTIME_MODES.forEach { (id, label, _) ->
                    Pill(
                        label,
                        container = if (prefs.defaultRuntimeMode == id) AllCode.colors.claySoft else AllCode.colors.inset,
                        tone = if (prefs.defaultRuntimeMode == id) AllCode.colors.clay else AllCode.colors.inkSoft,
                    ) { update { it.copy(defaultRuntimeMode = id) } }
                }
            }

            SectionLabel("Feel")
            Toggle("Haptics", "Gentle taps when you send, approve, and switch.", prefs.haptics) { on -> update { it.copy(haptics = on) } }

            SectionLabel("About")
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AllCodeMark(36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("AllCode Connect", style = AllCode.type.title, color = AllCode.colors.ink)
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
                    Text("Version $version · speaks T3 Code protocol 2", style = AllCode.type.caption, color = AllCode.colors.inkFaint)
                }
            }
            Text(
                "Connects directly to T3 Code servers on your network or tailnet. Nothing passes through a third-party relay.\n\nSource Serif 4, DM Sans, and JetBrains Mono are used under the SIL Open Font License.",
                style = AllCode.type.caption,
                color = AllCode.colors.inkSoft,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().pressable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ClapFy/AllCode")))
                }.padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text("Source on GitHub", style = AllCode.type.body, color = AllCode.colors.clay) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(haptic = false) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AllCode.type.bodyStrong, color = AllCode.colors.ink)
            Text(body, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AllCode.colors.onClay,
                checkedTrackColor = AllCode.colors.clay,
                uncheckedThumbColor = AllCode.colors.inkFaint,
                uncheckedTrackColor = AllCode.colors.inset,
                uncheckedBorderColor = AllCode.colors.lineStrong,
            ),
        )
    }
}
