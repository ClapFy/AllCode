package com.allcode.connect.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.bool
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.net.FrameEvent
import com.allcode.connect.net.PreviewStreamClient
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.IconAction
import com.allcode.connect.ui.components.Pill
import com.allcode.connect.ui.components.PrimaryButton
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.TextInput
import com.allcode.connect.ui.components.FilterRow
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun PreviewScreen(graph: AppGraph, navigator: Navigator, environmentId: String, threadId: String?, projectId: String?) {
    val connection = graph.connections.connection(environmentId)
    if (threadId == null || connection == null) {
        Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
            TopBar("Preview", onBack = navigator::back)
            EmptyState("Previews live in threads", "Open a thread and choose Preview to watch the browser its agent uses.")
        }
        return
    }
    var sessions by remember { mutableStateOf<List<JsonObject>?>(null) }
    var tabId by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("http://localhost:3000") }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        runCatching { connection.call("preview.list", buildJsonObject { put("threadId", threadId) }).asObject()?.objects("sessions").orEmpty() }
            .onSuccess { list -> sessions = list; if (tabId == null || list.none { it.str("tabId") == tabId }) tabId = list.firstOrNull()?.str("tabId") }
            .onFailure { error = it.message; sessions = emptyList() }
    }

    fun open() {
        scope.launch {
            runCatching {
                connection.call("preview.open", buildJsonObject { put("threadId", threadId); put("url", url.trim()); put("reveal", true) }).asObject()
            }.onSuccess { snapshot -> tabId = snapshot?.str("tabId"); reload++ }
                .onFailure { error = it.message ?: "The preview didn't open." }
        }
    }

    Column(Modifier.fillMaxSize().background(AllCode.colors.paper).imePadding()) {
        TopBar("Preview", subtitle = sessions?.firstOrNull { it.str("tabId") == tabId }?.obj("navStatus")?.str("url"), onBack = navigator::back, actions = {
            IconAction(Icons.Rounded.Refresh, "Refresh list", { reload++ })
        })
        if ((sessions?.size ?: 0) > 1) {
            val tabs = sessions!!.mapNotNull { s ->
                val id = s.str("tabId") ?: return@mapNotNull null
                id to (s.obj("navStatus")?.str("title")?.ifBlank { null } ?: s.obj("navStatus")?.str("url") ?: id).take(28)
            }
            FilterRow(tabs.map { it.second }, tabs.indexOfFirst { it.first == tabId }) { tabId = tabs[it].first }
        }
        when {
            sessions == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AllCode.colors.clay, strokeWidth = 2.dp) }
            tabId == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open a page in the machine's browser", style = AllCode.type.title, color = AllCode.colors.ink)
                Text("It runs on your computer, so localhost means your dev server there.", style = AllCode.type.body, color = AllCode.colors.inkSoft)
                TextInput(url, { url = it }, "http://localhost:3000", label = "URL", keyboard = KeyboardType.Uri, ime = ImeAction.Go, mono = true) { open() }
                PrimaryButton("Open preview", Modifier.fillMaxWidth()) { open() }
                error?.let { Text(it, style = AllCode.type.body, color = AllCode.colors.rust) }
            }
            else -> StreamView(graph, environmentId, threadId, tabId!!)
        }
    }
}

@Composable
private fun StreamView(graph: AppGraph, environmentId: String, threadId: String, tabId: String) {
    val connection = graph.connections.connection(environmentId) ?: return
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewport by remember { mutableStateOf(IntSize(1280, 800)) }
    var status by remember { mutableStateOf("Connecting…") }
    var client by remember { mutableStateOf<PreviewStreamClient?>(null) }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    var typed by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val density = LocalDensity.current

    LaunchedEffect(tabId) {
        val ticket = runCatching { connection.http.postJson("/api/auth/websocket-ticket/media").asObject()?.str("ticket") }.getOrNull()
        if (ticket == null) { status = "Couldn't authorize the preview stream."; return@LaunchedEffect }
        val base = connection.env.value.wsBaseUrl.trimEnd('/')
        val width = with(density) { 1080 }
        val url = "$base/api/preview-stream/ws?threadId=${enc(threadId)}&tabId=${enc(tabId)}&maxWidth=$width&maxHeight=${width * 2}&wsTicket=${enc(ticket)}"
        val stream = PreviewStreamClient(url)
        client = stream
        launch {
            kotlinx.coroutines.delay(20_000)
            if (frame == null && status == "Connecting…") status = "Waiting for the browser on ${connection.env.value.label}…"
        }
        stream.events().collect { event ->
            when (event) {
                is FrameEvent.Frame -> { frame = event.bitmap; status = "Live" }
                is FrameEvent.Message -> when (event.json.str("type")) {
                    "viewport" -> viewport = IntSize(event.json.int("width") ?: viewport.width, event.json.int("height") ?: viewport.height)
                    "control" -> status = if (event.json.bool("canOperate") == true) "You can interact" else "Watching"
                }
                is FrameEvent.Closed -> status = when (event.code) {
                    // The server's browser needs a one-time host setup; its reason names the command.
                    4503 -> runCatching { com.allcode.connect.core.WireJson.parseToJsonElement(event.reason ?: "").asObject()?.str("command") }.getOrNull()
                        ?.let { "Previews need a one-time setup on ${connection.env.value.label}: run `$it` there." }
                        ?: "Previews need a one-time setup on ${connection.env.value.label}."
                    4404 -> "This preview tab was closed."
                    else -> event.reason ?: "Disconnected"
                }
            }
        }
    }

    fun toPage(x: Float, y: Float): Pair<Int, Int> {
        val w = displaySize.width.coerceAtLeast(1)
        val scale = viewport.width.toFloat() / w
        return (x * scale).toInt() to (y * scale).toInt()
    }

    fun mouse(action: String, x: Int, y: Int, buttons: Int) = buildJsonObject {
        put("type", "mouse"); put("action", action); put("x", x); put("y", y)
        put("button", if (action == "move") "none" else "left"); put("buttons", buttons); put("clickCount", 1); put("modifiers", 0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { client?.send(buildJsonObject { put("type", "history"); put("delta", -1) }) })
            IconAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", { client?.send(buildJsonObject { put("type", "history"); put("delta", 1) }) })
            BasicTextField(
                address,
                { address = it },
                singleLine = true,
                textStyle = AllCode.type.body.copy(color = AllCode.colors.ink),
                cursorBrush = SolidColor(AllCode.colors.clay),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { client?.send(buildJsonObject { put("type", "navigate"); put("url", address) }) }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AllCode.colors.inset).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        if (address.isEmpty()) Text("Go to address", style = AllCode.type.body, color = AllCode.colors.inkFaint)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            IconAction(Icons.Rounded.Refresh, "Reload", { client?.send(buildJsonObject { put("type", "reload") }) })
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(AllCode.colors.paperDeep),
            contentAlignment = Alignment.TopCenter,
        ) {
            val bitmap = frame
            if (bitmap == null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = AllCode.colors.clay, strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(status, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
                }
            } else {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Preview",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                        .onSizeChanged { displaySize = it }
                        .pointerInput(client) {
                            detectTapGestures { offset ->
                                val (x, y) = toPage(offset.x, offset.y)
                                client?.send(buildJsonObject { put("type", "takeControl") })
                                client?.send(mouse("down", x, y, 1))
                                client?.send(mouse("up", x, y, 0))
                            }
                        }
                        .pointerInput(client) {
                            detectDragGestures { change, drag ->
                                val (x, y) = toPage(change.position.x, change.position.y)
                                client?.send(buildJsonObject {
                                    put("type", "wheel"); put("x", x); put("y", y)
                                    put("deltaX", -drag.x * 1.5); put("deltaY", -drag.y * 1.5); put("modifiers", 0)
                                })
                            }
                        },
                )
            }
        }
        Row(
            Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AllCode.colors.surface).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(Icons.Rounded.TouchApp, null, Modifier.size(16.dp), tint = AllCode.colors.inkFaint)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                typed,
                { typed = it },
                singleLine = true,
                textStyle = AllCode.type.body.copy(color = AllCode.colors.ink),
                cursorBrush = SolidColor(AllCode.colors.clay),
                decorationBox = { inner ->
                    Box(Modifier.padding(vertical = 12.dp)) {
                        if (typed.isEmpty()) Text("Type into the page · $status", style = AllCode.type.body, color = AllCode.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            IconAction(Icons.AutoMirrored.Rounded.KeyboardReturn, "Send keys", {
                if (typed.isNotEmpty()) client?.send(buildJsonObject { put("type", "text"); put("text", typed) })
                listOf("down", "up").forEach { action ->
                    client?.send(buildJsonObject { put("type", "key"); put("action", action); put("key", "Enter"); put("code", "Enter"); put("keyCode", 13); put("modifiers", 0) })
                }
                typed = ""
            }, tint = AllCode.colors.clay)
        }
    }
}

private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
