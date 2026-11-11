package com.allcode.connect.ui.pair

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.allcode.connect.AppGraph
import com.allcode.connect.net.Pairing
import com.allcode.connect.net.PairingTarget
import com.allcode.connect.ui.components.AllCodeMark
import com.allcode.connect.ui.components.CozyCard
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.TextInput
import com.allcode.connect.ui.components.IconAction
import com.allcode.connect.ui.components.PrimaryButton
import com.allcode.connect.ui.components.QuietButton
import com.allcode.connect.ui.components.confirmHaptic
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.rejectHaptic
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PairMode { Choose, Scan, Link, Address }

private sealed interface PairProgress {
    data object Idle : PairProgress
    data class Working(val host: String) : PairProgress
    data class Done(val label: String) : PairProgress
    data class Failed(val message: String) : PairProgress
}

@Composable
fun PairScreen(graph: AppGraph, initialLink: String?, canGoBack: Boolean, onBack: () -> Unit, onPaired: (String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(PairMode.Choose) }
    var progress by remember { mutableStateOf<PairProgress>(PairProgress.Idle) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    fun pair(target: () -> PairingTarget) {
        val resolved = try {
            target()
        } catch (e: Exception) {
            view.rejectHaptic()
            progress = PairProgress.Failed(e.message ?: "That pairing link isn't valid.")
            return
        }
        progress = PairProgress.Working(java.net.URI(resolved.httpBaseUrl).host ?: "your machine")
        scope.launch {
            try {
                val saved = graph.connections.pair(resolved)
                view.confirmHaptic()
                progress = PairProgress.Done(saved.label)
                delay(900)
                onPaired(saved.environmentId)
            } catch (e: Exception) {
                view.rejectHaptic()
                progress = PairProgress.Failed(e.message ?: "Pairing failed.")
                if (mode == PairMode.Scan) mode = PairMode.Choose
            }
        }
    }

    LaunchedEffect(initialLink) {
        if (initialLink != null) pair { Pairing.parseLink(initialLink) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) mode = PairMode.Scan
        else progress = PairProgress.Failed("Camera access is off. Paste the pairing link instead.")
    }

    Box(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack || mode != PairMode.Choose) {
                    IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", {
                        if (mode != PairMode.Choose) mode = PairMode.Choose else onBack()
                    })
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AllCodeMark(56.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("allcode connect", style = AllCode.type.mono, color = AllCode.colors.inkSoft)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    if (canGoBack) "Add a machine" else "Your agents, wherever you are.",
                    style = AllCode.type.greeting,
                    color = AllCode.colors.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AllCode Connect drives the T3 Code running on your computer. Pair this phone once, then follow, steer, and approve your agents from anywhere on your network or tailnet.",
                    style = AllCode.type.body,
                    color = AllCode.colors.inkSoft,
                )
                Spacer(Modifier.height(22.dp))

                run {
                    when (mode) {
                        PairMode.Choose -> ChooseMethod(
                            onScan = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    mode = PairMode.Scan
                                } else cameraLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onLink = { mode = PairMode.Link },
                            onAddress = { mode = PairMode.Address },
                        )
                        PairMode.Scan -> ScanPane(
                            onError = { message -> progress = PairProgress.Failed(message); mode = PairMode.Link },
                        ) { code -> pair { Pairing.parseLink(code) } }
                        PairMode.Link -> LinkPane { link -> pair { Pairing.parseLink(link) } }
                        PairMode.Address -> AddressPane { host, code -> pair { Pairing.fromHostAndCode(host, code) } }
                    }
                }

                Spacer(Modifier.height(16.dp))
                ProgressBanner(progress)
                Spacer(Modifier.height(24.dp))
                HowTo()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChooseMethod(onScan: () -> Unit, onLink: () -> Unit, onAddress: () -> Unit) {
    CozyCard(Modifier.fillMaxWidth()) {
        Column {
            MethodRow(Icons.Rounded.QrCodeScanner, "Scan the pairing QR", "From T3 Code on your computer, or `t3 pair` in a terminal.", primary = true, onClick = onScan)
            Divider()
            MethodRow(Icons.Rounded.Link, "Paste a pairing link", "A link that ends in #token=…", onClick = onLink)
            Divider()
            MethodRow(Icons.Rounded.Dns, "Enter an address and code", "For LAN or Tailscale addresses like 100.x.y.z:3773.", onClick = onAddress)
        }
    }
}

@Composable
private fun MethodRow(icon: ImageVector, title: String, body: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (primary) AllCode.colors.clay else AllCode.colors.inkSoft)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AllCode.type.bodyStrong, color = if (primary) AllCode.colors.clay else AllCode.colors.ink)
            Text(body, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
        }
        Text("›", style = AllCode.type.mono, color = AllCode.colors.inkFaint)
    }
}

@Composable
private fun ScanPane(onError: (String) -> Unit, onCode: (String) -> Unit) {
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(AllCode.colors.paperDeep)
                .border(1.dp, AllCode.colors.lineStrong, RoundedCornerShape(10.dp)),
        ) {
            QrScanner(Modifier.fillMaxSize(), onError = onError, onCode = onCode)
            Box(Modifier.align(Alignment.Center).size(200.dp).border(1.5.dp, AllCode.colors.clay, RoundedCornerShape(10.dp)))
        }
        Spacer(Modifier.height(8.dp))
        Text("point at the QR code on your computer", style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint)
    }
}

@Composable
private fun LinkPane(onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInput(link, { link = it }, "http://100.101.1.2:3773/pair#token=…", label = "Pairing link", keyboard = KeyboardType.Uri, ime = ImeAction.Go, mono = true) { onSubmit(link) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Paste", icon = Icons.Rounded.ContentPaste) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it.trim() }
            }
            PrimaryButton("Pair", Modifier.weight(1f), enabled = link.isNotBlank()) { onSubmit(link) }
        }
    }
}

@Composable
private fun AddressPane(onSubmit: (String, String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInput(host, { host = it }, "192.168.1.20:3773", label = "Address", keyboard = KeyboardType.Uri, ime = ImeAction.Next, mono = true)
        TextInput(code, { code = it.uppercase() }, "EQLQJ8N2W8AR", label = "Pairing code", keyboard = KeyboardType.Ascii, ime = ImeAction.Go, capitalization = KeyboardCapitalization.Characters, mono = true) {
            onSubmit(host, code)
        }
        PrimaryButton("Pair", Modifier.fillMaxWidth(), enabled = host.isNotBlank() && code.isNotBlank()) { onSubmit(host, code) }
    }
}

@Composable
private fun ProgressBanner(progress: PairProgress) {
    if (progress is PairProgress.Idle) return
    val colors = AllCode.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.paperDeep)
            .border(1.dp, colors.line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            when (progress) {
                is PairProgress.Working -> Spark(13.dp)
                is PairProgress.Done -> Box(Modifier.size(7.dp).clip(CircleShape).background(colors.sage))
                else -> Text("×", style = AllCode.type.monoStrong, color = colors.rust)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (progress) {
                is PairProgress.Working -> "Pairing with ${progress.host}…"
                is PairProgress.Done -> "Paired with ${progress.label}"
                is PairProgress.Failed -> progress.message
                PairProgress.Idle -> ""
            },
            style = AllCode.type.body,
            color = if (progress is PairProgress.Failed) colors.rust else colors.ink,
        )
    }
}

@Composable
private fun HowTo() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("on your computer", style = AllCode.type.overline, color = AllCode.colors.inkFaint)
        Step(1, "Open T3 Code, go to Settings → Connections, and choose Create pairing link.")
        Step(2, "Or run `npx t3 pair --tailscale` to pair over your tailnet from anywhere.")
        Step(3, "Scan the code it shows. Pairing codes work once and expire after a few minutes.")
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row {
        Text("$number.", style = AllCode.type.mono, color = AllCode.colors.inkFaint, modifier = Modifier.width(24.dp).padding(top = 1.dp))
        Text(text, style = AllCode.type.body, color = AllCode.colors.inkSoft, modifier = Modifier.weight(1f))
    }
}
