package com.allcode.connect.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.str
import com.allcode.connect.data.ThreadFeed
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.FilterRow
import com.allcode.connect.ui.components.Pill
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DiffLine(val kind: Char, val text: String, val oldNo: Int?, val newNo: Int?)
data class DiffFile(val path: String, val additions: Int, val deletions: Int, val lines: List<DiffLine>, val binary: Boolean)

/** Parses `git diff` output into files with numbered lines. */
fun parseUnifiedDiff(diff: String): List<DiffFile> {
    val files = ArrayList<DiffFile>()
    var path: String? = null
    var lines = ArrayList<DiffLine>()
    var adds = 0
    var dels = 0
    var binary = false
    var oldNo = 0
    var newNo = 0
    fun flush() {
        path?.let { files += DiffFile(it, adds, dels, lines, binary) }
        lines = ArrayList(); adds = 0; dels = 0; binary = false
    }
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("diff --git ") -> {
                flush()
                path = line.substringAfter(" b/", line.substringAfterLast(' '))
            }
            line.startsWith("+++ ") -> line.removePrefix("+++ ").removePrefix("b/").takeIf { it != "/dev/null" }?.let { path = it }
            line.startsWith("--- ") || line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") ||
                line.startsWith("similarity") || line.startsWith("rename ") -> Unit
            line.startsWith("Binary files") -> binary = true
            line.startsWith("@@") -> {
                val match = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)").find(line)
                oldNo = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                newNo = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
                lines += DiffLine('@', match?.groupValues?.get(3)?.trim()?.ifEmpty { null } ?: "⋯", null, null)
            }
            path == null -> Unit
            line.startsWith("+") -> { adds++; lines += DiffLine('+', line.drop(1), null, newNo++) }
            line.startsWith("-") -> { dels++; lines += DiffLine('-', line.drop(1), oldNo++, null) }
            line.startsWith("\\") -> Unit
            else -> lines += DiffLine(' ', line.drop(1), oldNo++, newNo++)
        }
    }
    flush()
    return files
}

@Composable
fun DiffScreen(graph: AppGraph, navigator: Navigator, environmentId: String, threadId: String) {
    val connection = graph.connections.connection(environmentId)
    var turns by remember { mutableStateOf(0) }
    var turn by remember { mutableStateOf<Int?>(null) }
    var files by remember { mutableStateOf<List<DiffFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val open = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(threadId) {
        val projection = withTimeoutOrNull(15_000) {
            connection?.thread(threadId)?.filterIsInstance<ThreadFeed.Ready>()?.first()?.projection
        }
        turns = projection?.checkpoints?.mapNotNull { it.str("id")?.substringAfterLast(':')?.toIntOrNull() }?.maxOrNull() ?: 0
        if (projection == null) error = "Couldn't reach ${connection?.env?.value?.label ?: "the machine"}."
    }
    LaunchedEffect(turns, turn) {
        if (turns == 0) { if (error == null && files == null) files = null; return@LaunchedEffect }
        files = null
        error = null
        val result = runCatching {
            val payload = buildJsonObject {
                put("threadId", threadId)
                if (turn == null) put("toTurnCount", turns) else { put("fromTurnCount", turn!! - 1); put("toTurnCount", turn!!) }
            }
            connection!!.call(if (turn == null) "orchestration.getFullThreadDiff" else "orchestration.getTurnDiff", payload).asObject()?.str("diff") ?: ""
        }
        result.onSuccess { text ->
            val parsed = parseUnifiedDiff(text)
            files = parsed
            if (parsed.size <= 3) parsed.forEach { open[it.path] = true }
        }.onFailure { error = it.message ?: "The diff couldn't load." }
    }

    val totals = files?.let { list -> list.sumOf { it.additions } to list.sumOf { it.deletions } }
    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        TopBar(
            "Changes",
            subtitle = totals?.let { "${files!!.size} files · +${it.first} −${it.second}" },
            onBack = navigator::back,
        )
        if (turns > 1) {
            run {
                val options = listOf<Int?>(null) + (turns downTo 1).toList()
                FilterRow(options.map { n -> if (n == null) "all turns" else "turn $n" }, options.indexOf(turn)) { turn = options[it] }
            }
        }
        when {
            error != null -> EmptyState("No diff", error ?: "")
            turns == 0 && files == null -> EmptyState("No changes yet", "Checkpoints appear after each turn finishes.")
            files == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AllCode.colors.clay, strokeWidth = 2.dp)
            }
            files!!.isEmpty() -> EmptyState("Clean", "This range didn't change any files.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                files!!.forEach { file ->
                    item(key = "h:${file.path}") {
                        FileHeader(file, open[file.path] == true) { open[file.path] = open[file.path] != true }
                    }
                    if (open[file.path] == true) {
                        if (file.binary) item(key = "b:${file.path}") {
                            Text("Binary file", style = AllCode.type.caption, color = AllCode.colors.inkFaint, modifier = Modifier.padding(20.dp))
                        }
                        items(file.lines.size, key = { "l:${file.path}:$it" }) { index -> LineRow(file.lines[index]) }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun FileHeader(file: DiffFile, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AllCode.colors.paper).pressable(haptic = false, onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp).rotate(if (open) 90f else 0f), tint = AllCode.colors.inkFaint)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Description, null, Modifier.size(16.dp), tint = AllCode.colors.inkSoft)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(file.path.substringAfterLast('/'), style = AllCode.type.bodyStrong, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.path.substringBeforeLast('/', ""), style = AllCode.type.caption, color = AllCode.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("+${file.additions}", style = AllCode.type.monoSmall, color = AllCode.colors.sage)
        Spacer(Modifier.width(8.dp))
        Text("−${file.deletions}", style = AllCode.type.monoSmall, color = AllCode.colors.rust)
    }
}

@Composable
private fun LineRow(line: DiffLine) {
    val background = when (line.kind) {
        '+' -> AllCode.colors.addition
        '-' -> AllCode.colors.deletion
        '@' -> AllCode.colors.inset
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 1.dp)) {
        Text(
            (line.oldNo ?: line.newNo)?.toString() ?: "",
            style = AllCode.type.monoSmall,
            color = AllCode.colors.inkFaint,
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp, end = 8.dp),
        )
        Text(
            if (line.kind == '@') line.text else "${if (line.kind == ' ') ' ' else line.kind} ${line.text}",
            style = AllCode.type.monoSmall,
            color = if (line.kind == '@') AllCode.colors.inkFaint else AllCode.colors.ink,
            softWrap = true,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}
