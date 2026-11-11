package com.allcode.connect.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CleaningServices
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.obj
import com.allcode.connect.core.str
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.IconAction
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ANSI_COLORS = listOf(
    Color(0xFF3B3A37), Color(0xFFE06C5A), Color(0xFF8DB87A), Color(0xFFE2B26A),
    Color(0xFF7EA6D8), Color(0xFFC792C7), Color(0xFF79C2C2), Color(0xFFE8E4DA),
)

/** A small line-oriented screen: SGR colors, carriage returns, and erase-line. Enough for shells and logs. */
class TerminalBuffer(private val maxLines: Int = 3000) {
    private val lines = ArrayList<AnnotatedString.Builder>().apply { add(AnnotatedString.Builder()) }
    private var column = 0
    private var style = SpanStyle()
    private var pending = ""

    fun write(data: String) {
        var text = pending + data
        pending = ""
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001b' -> {
                    val end = sequenceEnd(text, i)
                    if (end == -1) { pending = text.substring(i); return }
                    handleEscape(text.substring(i, end + 1))
                    i = end
                }
                c == '\n' -> { lines += AnnotatedString.Builder(); column = 0 }
                c == '\r' -> column = 0
                c == '\b' -> column = (column - 1).coerceAtLeast(0)
                c == '\t' -> repeat(4 - column % 4) { put(' ') }
                c < ' ' -> Unit
                else -> put(c)
            }
            i++
        }
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun sequenceEnd(text: String, start: Int): Int {
        if (start + 1 >= text.length) return -1
        return when (text[start + 1]) {
            '[' -> { var j = start + 2; while (j < text.length && text[j] !in '@'..'~') j++; if (j < text.length) j else -1 }
            ']' -> { val bel = text.indexOf('\u0007', start); val st = text.indexOf("\u001b\\", start); listOf(bel, st + 1).filter { it > start }.minOrNull() ?: -1 }
            else -> start + 1
        }
    }

    private fun handleEscape(seq: String) {
        if (!seq.startsWith("\u001b[")) return
        val final = seq.last()
        val params = seq.substring(2, seq.length - 1).trimStart('?').split(';').map { it.toIntOrNull() ?: 0 }
        when (final) {
            'm' -> applySgr(params)
            'K' -> truncateLine()
            'J' -> if (params.firstOrNull() == 2 || params.firstOrNull() == 3) { lines.clear(); lines += AnnotatedString.Builder(); column = 0 }
            'G' -> column = ((params.firstOrNull() ?: 1) - 1).coerceAtLeast(0)
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) { style = SpanStyle(); return }
        var k = 0
        while (k < params.size) {
            when (val p = params[k]) {
                0 -> style = SpanStyle()
                1 -> style = style.copy(fontWeight = FontWeight.Bold)
                22 -> style = style.copy(fontWeight = FontWeight.Normal)
                in 30..37 -> style = style.copy(color = ANSI_COLORS[p - 30])
                in 90..97 -> style = style.copy(color = ANSI_COLORS[p - 90])
                39 -> style = style.copy(color = Color.Unspecified)
                38 -> { if (params.getOrNull(k + 1) == 5) k += 2 else if (params.getOrNull(k + 1) == 2) k += 4 }
                48 -> { if (params.getOrNull(k + 1) == 5) k += 2 else if (params.getOrNull(k + 1) == 2) k += 4 }
            }
            k++
        }
    }

    private fun current() = lines.last()

    private fun truncateLine() {
        val line = current().toAnnotatedString()
        if (column < line.length) {
            lines[lines.lastIndex] = AnnotatedString.Builder(line.subSequence(0, column))
        }
    }

    private fun put(c: Char) {
        val line = current()
        if (column < line.length) {
            val existing = line.toAnnotatedString()
            val rebuilt = AnnotatedString.Builder(existing.subSequence(0, column))
            rebuilt.withStyle(style) { append(c) }
            if (column + 1 < existing.length) rebuilt.append(existing.subSequence(column + 1, existing.length))
            lines[lines.lastIndex] = rebuilt
        } else {
            line.withStyle(style) { append(c) }
        }
        column++
    }

    fun snapshot(): List<AnnotatedString> = lines.map { it.toAnnotatedString() }
}

@Composable
fun TerminalScreen(graph: AppGraph, navigator: Navigator, environmentId: String, threadId: String?, projectId: String?) {
    val connection = graph.connections.connection(environmentId)
    val shell = connection?.shell?.value
    val thread = threadId?.let { shell?.threads?.get(it) }
    val project = shell?.project(projectId ?: thread?.projectId)
    val cwd = thread?.worktreePath ?: project?.workspaceRoot ?: connection?.config?.value?.cwd
    val scopeId = threadId ?: "allcode:${project?.id ?: "machine"}"
    val terminalId = "allcode-mobile"
    val buffer = remember { TerminalBuffer() }
    var lines by remember { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    var status by remember { mutableStateOf("Connecting…") }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun session() = buildJsonObject { put("threadId", scopeId); put("terminalId", terminalId) }

    fun send(data: String) {
        if (data.isEmpty()) return
        scope.launch {
            runCatching {
                connection?.call("terminal.write", buildJsonObject { put("threadId", scopeId); put("terminalId", terminalId); put("data", data) })
            }.onFailure { status = it.message ?: "Couldn't write to the terminal." }
        }
    }

    LaunchedEffect(scopeId, cwd) {
        if (connection == null || cwd == null) { status = "No working directory for this terminal."; return@LaunchedEffect }
        runCatching {
            connection.call("terminal.open", buildJsonObject {
                put("threadId", scopeId); put("terminalId", terminalId); put("cwd", cwd)
                thread?.worktreePath?.let { put("worktreePath", it) }
                put("cols", 80); put("rows", 32)
            })
        }.onFailure { status = it.message ?: "The terminal didn't open." }
        status = cwd.substringAfterLast('/')
        connection.stream("terminal.attach", session()).collect { value ->
            val event = value.asObject() ?: return@collect
            when (event.str("type")) {
                "snapshot" -> event.obj("snapshot")?.str("history")?.let { buffer.write(it) }
                "output" -> event.str("data")?.let { buffer.write(it) }
                "exited" -> { buffer.write("\r\n[process exited]\r\n"); status = "Exited" }
                "cleared" -> { buffer.write("\u001b[2J") }
                "error" -> status = event.str("message") ?: "Terminal error"
            }
            lines = buffer.snapshot()
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(AllCode.colors.paperDeep).imePadding()) {
        TopBar("Terminal", subtitle = status, onBack = navigator::back, actions = {
            IconAction(Icons.Rounded.CleaningServices, "Clear", {
                scope.launch { runCatching { connection?.call("terminal.clear", session()) } }
            })
        })
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1A19)),
            contentPadding = PaddingValues(10.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(line, style = AllCode.type.monoSmall.copy(color = Color(0xFFE8E4DA)), softWrap = true)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("esc" to "\u001b", "tab" to "\t", "^C" to "\u0003", "^D" to "\u0004", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, data) ->
                Text(
                    label,
                    style = AllCode.type.mono,
                    color = AllCode.colors.ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(AllCode.colors.inset).pressable { send(data) }.padding(vertical = 9.dp),
                )
            }
        }
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(AllCode.colors.surface).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = AllCode.type.mono, color = AllCode.colors.clay)
            BasicTextField(
                input,
                { input = it },
                singleLine = true,
                textStyle = AllCode.type.mono.copy(color = AllCode.colors.ink),
                cursorBrush = SolidColor(AllCode.colors.clay),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input + "\r"); input = "" }),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp),
            )
            IconAction(Icons.AutoMirrored.Rounded.Send, "Run", { send(input + "\r"); input = "" }, tint = AllCode.colors.clay)
        }
    }
}
