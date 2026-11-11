package com.allcode.connect.ui.machines

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.bool
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.data.EnvSnapshot
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.net.mjpegFrames
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.AllCodeMark
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.CozyCard
import com.allcode.connect.ui.components.Pill
import com.allcode.connect.ui.components.PrimaryButton
import com.allcode.connect.ui.components.QuietButton
import com.allcode.connect.ui.components.SectionLabel
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.home.RenameDialog
import com.allcode.connect.ui.home.statusColor
import com.allcode.connect.ui.theme.AllCode
import com.allcode.connect.ui.usage.ProviderUsageCard
import com.allcode.connect.ui.usage.usageWindows
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

fun statusText(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "Online"
    ConnectionStatus.Syncing -> "Syncing"
    is ConnectionStatus.Connecting -> "Connecting"
    is ConnectionStatus.Retrying -> "Reconnecting"
    is ConnectionStatus.Blocked -> if (status.needsPairing) "Needs pairing" else "Needs update"
    ConnectionStatus.Offline -> "Offline"
}

@Composable
fun MachinesTab(graph: AppGraph, navigator: Navigator) {
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TopBar("Machines", subtitle = "where your agents run") }
        items(envs, key = { it.env.environmentId }) { snap -> MachineCard(snap, Modifier) { navigator.machine(snap.env.environmentId) } }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Add a machine", Modifier.weight(1f), icon = Icons.Rounded.Add) { navigator.pair() }
                QuietButton("Usage", icon = Icons.Rounded.BarChart) { navigator.usage() }
            }
        }
    }
}

@Composable
private fun MachineCard(snap: EnvSnapshot, modifier: Modifier, onClick: () -> Unit) {
    val threads = snap.shell?.threads?.values.orEmpty().filter { !it.isSubagent }
    val working = threads.count { it.phase == ThreadPhase.Working }
    val waiting = threads.count { it.phase == ThreadPhase.NeedsYou }
    CozyCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(AllCode.colors.inset), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, tint = AllCode.colors.ink)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(snap.env.label, style = AllCode.type.title, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snap.env.httpBaseUrl.removeSuffix("/"), style = AllCode.type.caption, color = AllCode.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(statusColor(snap.status)))
                    Spacer(Modifier.width(6.dp))
                    Text(statusText(snap.status), style = AllCode.type.label, color = AllCode.colors.inkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${snap.shell?.projects?.size ?: 0} projects")
                if (working > 0) Pill("$working working", tone = AllCode.colors.amber, container = AllCode.colors.amberSoft)
                if (waiting > 0) Pill("$waiting need you", tone = AllCode.colors.clay, container = AllCode.colors.claySoft)
                snap.env.serverVersion?.let { Pill("T3 $it", tone = AllCode.colors.inkFaint, container = AllCode.colors.paperDeep) }
            }
            val tightest = snap.config?.providers.orEmpty().flatMap { p -> p.usageWindows().map { p.displayName to it } }.maxByOrNull { it.second.usedPercent }
            tightest?.let { (name, window) ->
                Spacer(Modifier.height(10.dp))
                Text("$name ${window.label.lowercase()}: ${window.usedPercent.toInt()}% used", style = AllCode.type.caption, color = AllCode.colors.inkSoft)
            }
        }
    }
}

@Composable
fun MachineScreen(graph: AppGraph, navigator: Navigator, environmentId: String) {
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    val snap = envs.firstOrNull { it.env.environmentId == environmentId }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (snap == null) {
        Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) { TopBar("Machine", onBack = navigator::back) }
        return
    }
    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        TopBar(snap.env.label, subtitle = statusText(snap.status), onBack = navigator::back, actions = {
            com.allcode.connect.ui.components.IconAction(Icons.Rounded.Refresh, "Reconnect", { snap.connection.retryNow() })
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            (snap.status as? ConnectionStatus.Blocked)?.let { blocked ->
                Text(
                    blocked.message,
                    style = AllCode.type.body,
                    color = AllCode.colors.ink,
                    modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AllCode.colors.rustSoft).padding(14.dp),
                )
            }
            (snap.status as? ConnectionStatus.Retrying)?.let { retry ->
                Text(retry.message, style = AllCode.type.caption, color = AllCode.colors.inkSoft, modifier = Modifier.padding(horizontal = 20.dp))
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuietButton("Terminal", icon = Icons.Rounded.Terminal) { navigator.terminal(environmentId) }
                QuietButton("Rename", icon = Icons.Rounded.Edit) { renaming = true }
                QuietButton("Pair again", icon = Icons.Rounded.QrCodeScanner) { navigator.pair() }
            }
            SectionLabel("Details")
            Detail("Address", snap.env.httpBaseUrl.removeSuffix("/"))
            snap.env.serverVersion?.let { Detail("T3 Code", it) }
            snap.env.os?.let { Detail("System", it.replaceFirstChar { c -> c.uppercase() }) }
            snap.config?.cwd?.let { Detail("Home", it) }

            val projects = snap.shell?.projects.orEmpty().filterNot { it.archived }
            if (projects.isNotEmpty()) {
                SectionLabel("Projects")
                projects.forEach { project ->
                    Row(
                        Modifier.fillMaxWidth().pressable(haptic = false) { navigator.newTask(environmentId, project.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp), tint = AllCode.colors.inkSoft)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(project.title, style = AllCode.type.bodyStrong, color = AllCode.colors.ink)
                            Text(project.workspaceRoot, style = AllCode.type.caption, color = AllCode.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Terminal, "Terminal", Modifier.size(20.dp).pressable { navigator.terminal(environmentId, projectId = project.id) }, tint = AllCode.colors.inkFaint)
                    }
                }
            }

            val limited = snap.config?.providers.orEmpty().filter { it.usageWindows().isNotEmpty() }
            if (limited.isNotEmpty()) {
                SectionLabel("Usage limits", trailing = {
                    Text("History", style = AllCode.type.label, color = AllCode.colors.clay, modifier = Modifier.pressable { navigator.usage(environmentId) })
                })
                limited.forEach { ProviderUsageCard(it) }
            }

            Devices(snap)

            Spacer(Modifier.height(16.dp))
            Text(
                "Forget this machine",
                style = AllCode.type.bodyStrong,
                color = AllCode.colors.rust,
                modifier = Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(10.dp)).pressable { removing = true }.padding(vertical = 10.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (renaming) RenameDialog(snap.env.label, onDismiss = { renaming = false }) { graph.connections.rename(environmentId, it); renaming = false }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            containerColor = AllCode.colors.surface,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = AllCode.colors.rust) },
            title = { Text("Forget ${snap.env.label}?", style = AllCode.type.title) },
            text = { Text("This phone stops connecting to it. Its threads stay on the machine, and you can pair again any time.", style = AllCode.type.body) },
            confirmButton = { TextButton({ removing = false; graph.connections.remove(environmentId); navigator.home() }) { Text("Forget", color = AllCode.colors.rust) } },
            dismissButton = { TextButton({ removing = false }) { Text("Keep", color = AllCode.colors.inkSoft) } },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = AllCode.type.body, color = AllCode.colors.inkSoft, modifier = Modifier.width(96.dp))
        Text(value, style = AllCode.type.body, color = AllCode.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Devices(snap: EnvSnapshot) {
    var state by remember { mutableStateOf<JsonObject?>(null) }
    var watching by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(snap.env.environmentId, snap.status == ConnectionStatus.Connected) {
        if (snap.status != ConnectionStatus.Connected) return@LaunchedEffect
        state = runCatching { snap.connection.call("device.list", buildJsonObject {}).asObject() }.getOrNull()
    }
    val data = state ?: return
    val devices = data.objects("devices")
    val hosts = data.objects("hosts")
    SectionLabel("Devices")
    if (devices.isEmpty()) {
        val reasons = hosts.flatMap { h -> h.objects("platforms").filter { it.bool("available") == false }.mapNotNull { it.str("reason") } }
        Text(
            if (reasons.isNotEmpty()) reasons.joinToString("\n") else "No simulators or emulators on this machine.",
            style = AllCode.type.caption,
            color = AllCode.colors.inkFaint,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    devices.forEach { device ->
        val running = device.str("state")?.lowercase() in setOf("booted", "running", "online")
        Row(
            Modifier.fillMaxWidth().pressable(enabled = running, haptic = false) { watching = device }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp), tint = AllCode.colors.inkSoft)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.str("name") ?: device.str("id") ?: "Device", style = AllCode.type.bodyStrong, color = AllCode.colors.ink)
                Text(listOfNotNull(device.str("platform"), device.str("state")).joinToString(" · "), style = AllCode.type.caption, color = AllCode.colors.inkFaint)
            }
            if (running) Text("Watch", style = AllCode.type.label, color = AllCode.colors.clay)
        }
    }
    watching?.let { device ->
        DeviceViewer(snap, device, data.str("hubBasePath") ?: "/api/device-hub") { watching = null }
    }
}

@Composable
private fun DeviceViewer(snap: EnvSnapshot, device: JsonObject, hubBasePath: String, onDismiss: () -> Unit) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(device) {
        val ticket = runCatching { snap.connection.http.postJson("/api/auth/websocket-ticket/media").asObject()?.str("ticket") }.getOrNull()
        val id = device.str("id") ?: return@LaunchedEffect
        val url = snap.env.httpBaseUrl.trimEnd('/') + hubBasePath + "/helper/" + java.net.URLEncoder.encode(id, Charsets.UTF_8) +
            "/stream.mjpeg" + (ticket?.let { "?wsTicket=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
        runCatching { mjpegFrames(url).collect { frame = it } }.onFailure { error = it.message ?: "The stream ended." }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(AllCode.colors.surface).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(device.str("name") ?: "Device", style = AllCode.type.heading, color = AllCode.colors.ink, modifier = Modifier.padding(8.dp))
            val bitmap = frame
            when {
                bitmap != null -> Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 560.dp).clip(RoundedCornerShape(16.dp)))
                error != null -> Text(error!!, style = AllCode.type.body, color = AllCode.colors.rust, modifier = Modifier.padding(16.dp))
                else -> Spark(16.dp, modifier = Modifier.padding(32.dp))
            }
        }
    }
}
