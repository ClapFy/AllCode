package com.allcode.connect.ui.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.allcode.connect.core.asObject
import com.allcode.connect.core.str
import com.allcode.connect.data.FileTouch
import com.allcode.connect.data.TimelineEntry
import com.allcode.connect.data.WorkStep
import com.allcode.connect.data.duration
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.PrimaryButton
import com.allcode.connect.ui.components.QuietButton
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.TextInput
import com.allcode.connect.ui.components.confirmHaptic
import com.allcode.connect.ui.components.formatDuration
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.rejectHaptic
import com.allcode.connect.ui.components.rememberFlatIndication
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TimelineItem(entry: TimelineEntry, model: ThreadViewModel, navigator: Navigator, environmentId: String) {
    when (entry) {
        is TimelineEntry.User -> UserPrompt(entry, model)
        is TimelineEntry.Assistant -> AssistantText(entry)
        is TimelineEntry.Work -> WorkGroup(entry, model, navigator, environmentId)
        is TimelineEntry.Approval -> ApprovalPrompt(entry, model)
        is TimelineEntry.Questions -> QuestionsPrompt(entry, model)
        is TimelineEntry.Plan -> PlanCard(entry, model)
        is TimelineEntry.Todo -> TodoList(entry)
        is TimelineEntry.Subagent -> SubagentRow(entry, onStop = { entry.subagentId?.let(model::stopSubagent) }) { id -> navigator.thread(environmentId, id) }
        is TimelineEntry.Notice -> NoticeRow(entry)
        is TimelineEntry.Failure -> FailureRow(entry)
        is TimelineEntry.Secret -> SecretRow(entry)
    }
}

/** One transcript line: an 18 dp glyph column, then the content. Every entry shares this grid. */
@Composable
private fun Gutter(
    glyph: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = 6.dp,
    bottom: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = top, bottom = bottom)) {
        Box(Modifier.width(18.dp).height(24.dp), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun Elbow(show: Boolean) {
    Text(if (show) "└" else "", style = AllCode.type.mono, color = AllCode.colors.inkFaint, modifier = Modifier.width(18.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    return combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = rememberFlatIndication(),
        onLongClick = { view.confirmHaptic(); copy(context, text) },
        onClick = {},
    )
}

@Composable
private fun UserPrompt(entry: TimelineEntry.User, model: ThreadViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(8.dp)).background(AllCode.colors.inset)
            .copyOnLongPress(entry.text)
            .padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (entry.attachments.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 26.dp, bottom = if (entry.text.isNotBlank()) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entry.attachments.forEach { attachment -> AttachmentThumb(attachment, model) }
            }
        }
        if (entry.text.isNotBlank()) {
            Row {
                Box(Modifier.width(18.dp).height(22.dp), contentAlignment = Alignment.Center) {
                    Text("❯", style = AllCode.type.monoStrong, color = AllCode.colors.clay)
                }
                Spacer(Modifier.width(8.dp))
                Text(entry.text, style = AllCode.type.body, color = AllCode.colors.ink, modifier = Modifier.weight(1f))
            }
        }
        if (entry.queued) {
            Text("queued", style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
    }
}

@Composable
private fun AttachmentThumb(attachment: JsonObject, model: ThreadViewModel) {
    val id = attachment.str("id") ?: return
    val isImage = attachment.str("type") == "image"
    val url by produceState<String?>(null, id) { if (isImage) value = model.attachmentUrl(id) }
    Box(
        Modifier.size(if (isImage) 88.dp else 64.dp).clip(RoundedCornerShape(6.dp)).background(AllCode.colors.paperDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (isImage && url != null) {
            AsyncImage(url, attachment.str("name"), contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp))
        } else {
            Text(attachment.str("name") ?: "file", style = AllCode.type.monoSmall, color = AllCode.colors.inkSoft, maxLines = 3, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun AssistantText(entry: TimelineEntry.Assistant) {
    if (entry.text.isBlank()) return
    Gutter(glyph = { Dot(AllCode.colors.ink) }, modifier = Modifier.copyOnLongPress(entry.text)) {
        ProseMarkdown(entry.text)
    }
}

/** Tool calls between two messages: one summary line, with the steps underneath while live or opened. */
@Composable
private fun WorkGroup(entry: TimelineEntry.Work, model: ThreadViewModel, navigator: Navigator, environmentId: String) {
    var open by rememberSaveable(entry.key) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (entry.running) {
        LaunchedEffect(entry.key) {
            while (true) { delay(1000); now = System.currentTimeMillis() }
        }
    }
    val duration = entry.duration(Instant.ofEpochMilli(now))
    val failed = entry.steps.any { it.failed }
    Gutter(
        glyph = { if (entry.running) Spark(13.dp) else Dot(if (failed) AllCode.colors.rust else AllCode.colors.sage) },
        top = 4.dp,
        bottom = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).pressable(haptic = false) { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                workLabel(entry),
                style = AllCode.type.label,
                color = if (entry.running) AllCode.colors.clay else AllCode.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if ((duration?.seconds ?: 0) >= 1) {
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(duration), style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint)
            }
            Spacer(Modifier.width(6.dp))
            Text(if (open) "▾" else "▸", style = AllCode.type.mono, color = AllCode.colors.inkFaint)
        }
        val steps = when {
            open -> entry.steps
            entry.running -> entry.steps.takeLast(3)
            else -> emptyList()
        }
        steps.forEachIndexed { index, step -> StepRow(step, model, elbow = index == 0) }
        if (open) {
            entry.files.forEachIndexed { index, file ->
                FileRow(file, elbow = steps.isEmpty() && index == 0) { navigator.diff(environmentId, model.threadId) }
            }
        }
    }
}

/** Live: what it's doing now. Done: a short group names its steps; a long one counts them. */
private fun workLabel(entry: TimelineEntry.Work): String {
    if (entry.running) {
        return entry.latestThought?.take(80) ?: entry.steps.lastOrNull { it.kind != "thought" }?.let { toolLabel(it).let { (tool, arg) -> "$tool $arg".trim() } } ?: "Working…"
    }
    val actions = entry.steps.filter { it.kind != "thought" }
    if (actions.size in 1..2 && entry.files.isEmpty()) return actions.joinToString(" · ") { it.title.take(48) }
    fun count(n: Int, one: String, many: String) = if (n == 1) one else many.replace("#", "$n")
    val commands = actions.count { it.kind == "command" }
    val searches = actions.count { it.kind == "search" || it.kind == "web" }
    val other = actions.size - commands - searches - actions.count { it.kind == "edit" }
    val parts = buildList {
        if (commands > 0) add(count(commands, "ran a command", "ran # commands"))
        if (entry.files.isNotEmpty()) add(count(entry.files.size, "edited a file", "edited # files"))
        if (searches > 0) add(count(searches, "searched once", "searched # times"))
        if (other > 0) add(count(other, "used a tool", "used # tools"))
    }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { if (entry.steps.isNotEmpty()) "Thought it through" else "Worked" }
}

/** `Bash` + `git status`: the tool and its argument, the way a terminal transcript names a call. */
private fun toolLabel(step: WorkStep): Pair<String, String> = when (step.kind) {
    "command" -> "Bash" to (step.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: step.title)
    "edit" -> "Edit" to shortPath(step.detail ?: step.title)
    "search" -> "Search" to (step.item.str("pattern") ?: step.title.removePrefix("Searched files for "))
    "web" -> "Web" to (step.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "")
    "tool" -> {
        val tool = (step.item.str("toolName")?.substringAfterLast("__")?.substringAfterLast('.') ?: "tool").replaceFirstChar { it.uppercase() }
        // "Read cart.test.ts" from a tool named read becomes Read(cart.test.ts).
        val arg = if (step.title.startsWith("$tool ", ignoreCase = true)) step.title.substring(tool.length + 1) else step.title
        tool to arg.takeUnless { it.replace(" ", "_").equals(tool, ignoreCase = true) }.orEmpty()
    }
    "stop" -> "Stop" to step.title
    "handoff" -> "Model" to step.title
    else -> step.title to ""
}

private fun shortPath(path: String) = path.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

@Composable
private fun StepRow(step: WorkStep, model: ThreadViewModel, elbow: Boolean) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var output by remember(step.id) { mutableStateOf(step.item.str("output")) }
    var loading by remember { mutableStateOf(false) }
    val colors = AllCode.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).pressable(haptic = false) {
                open = !open
                if (open && step.kind == "command" && output == null && step.item.str("outputOmitted") == "true") {
                    loading = true
                    scope.launch {
                        output = runCatching {
                            model.connection?.call("orchestration.getTurnItem", buildJsonObject {
                                put("threadId", model.threadId)
                                put("itemId", step.id)
                            })?.asObject()?.get("item")?.asObject()?.str("output")
                        }.getOrNull() ?: ""
                        loading = false
                    }
                }
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Elbow(elbow)
            if (step.kind == "thought") {
                Text(
                    step.title,
                    style = AllCode.type.caption.copy(fontStyle = FontStyle.Italic),
                    color = colors.inkSoft,
                    maxLines = if (open) 12 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
            } else {
                val (tool, arg) = toolLabel(step)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (step.failed) colors.rust else colors.ink)) { append(tool) }
                        if (arg.isNotEmpty()) withStyle(SpanStyle(color = colors.inkSoft)) { append("($arg)") }
                    },
                    style = AllCode.type.mono,
                    maxLines = if (open) 8 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (step.status == "running" || step.status == "pending") {
                Spacer(Modifier.width(6.dp))
                Spark(10.dp, colors.clay, modifier = Modifier.padding(top = 5.dp))
            }
        }
        if (open) {
            val detail = when (step.kind) {
                "command" -> output
                "thought" -> step.detail?.takeIf { it.trim() != step.title }
                else -> step.detail?.takeIf { it != step.title }
            }
            when {
                loading -> Text("loading output…", style = AllCode.type.monoSmall, color = colors.inkFaint, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
                !detail.isNullOrBlank() -> SelectionContainer {
                    Text(
                        detail.takeLast(6000),
                        style = AllCode.type.monoSmall,
                        color = colors.inkSoft,
                        modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp)).background(colors.code)
                            .heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: FileTouch, elbow: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).pressable(haptic = false, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Elbow(elbow)
        Text(shortPath(file.path), style = AllCode.type.mono, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        file.additions?.let { Text(" +$it", style = AllCode.type.monoSmall, color = AllCode.colors.sage) }
        file.deletions?.let { Text(" −$it", style = AllCode.type.monoSmall, color = AllCode.colors.rust) }
    }
}

private fun requestTitle(kind: String) = when (kind) {
    "command" -> "Bash command"
    "file-change", "file_change" -> "Edit files"
    "file-read", "file_read" -> "Read files"
    "mcp-elicitation" -> "Tool use"
    else -> "Permission"
}

private fun requestVerb(kind: String) = when (kind) {
    "command" -> "run a command"
    "file-change", "file_change" -> "edit files"
    "file-read", "file_read" -> "read files"
    "mcp-elicitation" -> "use a tool"
    else -> "continue"
}

/** A numbered answer row, the way a terminal prompt lists choices. */
@Composable
private fun Choice(number: Int, label: String, highlighted: Boolean, description: String? = null, onClick: () -> Unit) {
    val colors = AllCode.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) colors.claySoft else Color.Transparent)
            .pressable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(if (highlighted) "❯" else "", style = AllCode.type.monoStrong, color = colors.clay, modifier = Modifier.width(16.dp).padding(top = 1.dp))
        Text("$number.", style = AllCode.type.mono, color = if (highlighted) colors.clay else colors.inkFaint, modifier = Modifier.width(24.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = AllCode.type.body, color = if (highlighted) colors.clay else colors.ink)
            if (description != null && description != label) Text(description, style = AllCode.type.caption, color = colors.inkSoft)
        }
    }
}

@Composable
private fun PromptBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(AllCode.colors.surface)
            .border(1.dp, AllCode.colors.clay.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun ApprovalPrompt(entry: TimelineEntry.Approval, model: ThreadViewModel) {
    val view = LocalView.current
    var answered by remember(entry.requestId) { mutableStateOf<String?>(null) }
    if (!entry.pending || answered != null) {
        val decision = answered ?: entry.decision
        val allowed = decision?.startsWith("accept") == true
        Gutter(glyph = { Dot(if (allowed) AllCode.colors.sage else AllCode.colors.inkFaint) }, top = 2.dp, bottom = 2.dp) {
            Text(
                "${if (allowed) "Allowed" else if (decision == null) "Skipped" else "Declined"}: ${requestVerb(entry.requestKind)}",
                style = AllCode.type.label,
                color = AllCode.colors.inkSoft,
            )
        }
        return
    }
    fun respond(decision: String) {
        if (decision == "decline") view.rejectHaptic() else view.confirmHaptic()
        answered = decision
        model.respond(entry.requestId, decision)
    }
    PromptBox {
        Text(requestTitle(entry.requestKind), style = AllCode.type.heading, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
        if (entry.prompt.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                entry.prompt.take(1500),
                style = AllCode.type.mono,
                color = AllCode.colors.ink,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(AllCode.colors.code)
                    .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            )
        }
        entry.reason?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = AllCode.type.caption, color = AllCode.colors.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Do you want to proceed?", style = AllCode.type.body, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        Choice(1, "Yes", highlighted = true) { respond("accept") }
        Choice(2, "Yes, and don't ask again this session", highlighted = false) { respond("acceptForSession") }
        Choice(3, "No", highlighted = false) { respond("decline") }
    }
}

@Composable
private fun QuestionsPrompt(entry: TimelineEntry.Questions, model: ThreadViewModel) {
    val view = LocalView.current
    val picks = remember(entry.requestId) { mutableStateMapOf<String, String>() }
    var submitted by remember(entry.requestId) { mutableStateOf(false) }
    if (!entry.pending || submitted) {
        Gutter(glyph = { Dot(AllCode.colors.inkFaint) }, top = 2.dp, bottom = 2.dp) {
            entry.questions.forEach { q ->
                Text(q.question, style = AllCode.type.caption, color = AllCode.colors.inkFaint)
                (entry.answers[q.id] ?: picks[q.id])?.let { answer ->
                    Row {
                        Elbow(true)
                        Text(answer, style = AllCode.type.mono, color = AllCode.colors.inkSoft, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        return
    }
    PromptBox {
        entry.questions.forEachIndexed { index, q ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            q.header?.let { Text(it.lowercase(), style = AllCode.type.overline, color = AllCode.colors.clay, modifier = Modifier.padding(horizontal = 4.dp)) }
            Text(q.question, style = AllCode.type.bodyStrong, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            q.options.forEachIndexed { i, (label, description) ->
                Choice(i + 1, label, highlighted = picks[q.id] == label, description = description) { picks[q.id] = label }
            }
            var other by remember(q.id) { mutableStateOf("") }
            TextInput(
                other,
                { other = it; if (it.isNotBlank()) picks[q.id] = it },
                placeholder = "Something else…",
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Submit", Modifier.weight(1f), enabled = entry.questions.all { picks[it.id] != null }) {
                view.confirmHaptic()
                submitted = true
                model.answer(entry.requestId, picks.toMap())
            }
            QuietButton("Skip") { submitted = true; model.dismissQuestions(entry.requestId) }
        }
    }
}

@Composable
private fun PlanCard(entry: TimelineEntry.Plan, model: ThreadViewModel) {
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(AllCode.colors.surface)
            .border(1.dp, AllCode.colors.line, RoundedCornerShape(8.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.streaming) {
                Spark(12.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("plan", style = AllCode.type.overline, color = AllCode.colors.clay)
        }
        Spacer(Modifier.height(6.dp))
        ProseMarkdown(entry.markdown, compact = true)
        if (!entry.streaming) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Implement this plan", Modifier.fillMaxWidth()) {
                model.setInteractionMode("default")
                model.draft.value = "Implement the plan."
                model.send()
            }
        }
    }
}

@Composable
private fun TodoList(entry: TimelineEntry.Todo) {
    val colors = AllCode.colors
    val done = entry.steps.count { it.status == "completed" }
    Gutter(glyph = { Dot(colors.ink) }, top = 4.dp, bottom = 4.dp) {
        Text("Update todos · $done/${entry.steps.size}", style = AllCode.type.label, color = colors.inkSoft, modifier = Modifier.padding(vertical = 2.dp))
        entry.steps.forEachIndexed { index, step ->
            val (mark, tint) = when (step.status) {
                "completed" -> "[x]" to colors.sage
                "running", "in_progress" -> "[•]" to colors.clay
                else -> "[ ]" to colors.inkFaint
            }
            Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                Elbow(index == 0)
                Text(mark, style = AllCode.type.mono, color = tint, modifier = Modifier.width(32.dp))
                Text(
                    step.text,
                    style = when (step.status) {
                        "completed" -> AllCode.type.body.copy(textDecoration = TextDecoration.LineThrough)
                        "running", "in_progress" -> AllCode.type.bodyStrong
                        else -> AllCode.type.body
                    },
                    color = if (step.status == "completed") colors.inkFaint else colors.ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SubagentRow(entry: TimelineEntry.Subagent, onStop: () -> Unit, onOpen: (String) -> Unit) {
    var open by rememberSaveable(entry.key) { mutableStateOf(false) }
    val colors = AllCode.colors
    val running = entry.status == "running" || entry.status == "pending"
    Gutter(glyph = { if (running) Spark(13.dp) else Dot(if (entry.status == "failed") colors.rust else colors.sage) }, top = 4.dp, bottom = 4.dp) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).pressable(haptic = false) { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.ink)) { append("Task") }
                    withStyle(SpanStyle(color = colors.inkSoft)) { append("(${entry.title})") }
                },
                style = AllCode.type.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                Text(
                    "stop",
                    style = AllCode.type.mono,
                    color = colors.rust,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).pressable(onClick = onStop).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Text(entry.status, style = AllCode.type.monoSmall, color = colors.inkFaint)
            }
        }
        entry.provider?.let {
            Row {
                Elbow(true)
                Text("subagent · $it", style = AllCode.type.monoSmall, color = colors.inkFaint, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (open) {
            entry.result?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                ProseMarkdown(it.take(4000), compact = true)
            }
            entry.childThreadId?.let { child ->
                Text(
                    "Open subagent thread ›",
                    style = AllCode.type.label,
                    color = colors.clay,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).pressable { onOpen(child) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(entry: TimelineEntry.Notice) {
    val divider = entry.tone == TimelineEntry.Tone.Divider
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (divider) Box(Modifier.weight(1f).height(1.dp).background(AllCode.colors.line))
        Text(
            entry.text,
            style = AllCode.type.monoSmall,
            color = AllCode.colors.inkFaint,
            modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 280.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (divider) Box(Modifier.weight(1f).height(1.dp).background(AllCode.colors.line))
    }
}

@Composable
private fun FailureRow(entry: TimelineEntry.Failure) {
    Gutter(glyph = { Text("×", style = AllCode.type.monoStrong, color = AllCode.colors.rust) }) {
        Text(entry.title, style = AllCode.type.bodyStrong, color = AllCode.colors.rust)
        Text(entry.message, style = AllCode.type.body, color = AllCode.colors.inkSoft)
        entry.resetAt?.let {
            Text(
                "resets ${DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault()).format(it)}",
                style = AllCode.type.monoSmall,
                color = AllCode.colors.inkFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SecretRow(entry: TimelineEntry.Secret) {
    Gutter(glyph = { Dot(AllCode.colors.amber) }) {
        Text(entry.label, style = AllCode.type.bodyStrong, color = AllCode.colors.ink)
        entry.reason?.let { Text(it, style = AllCode.type.body, color = AllCode.colors.inkSoft) }
        Text(
            if (entry.status == "pending" || entry.status == "waiting") "Enter it on your computer; secrets never pass through your phone." else entry.status.replaceFirstChar { it.uppercase() },
            style = AllCode.type.caption,
            color = AllCode.colors.inkFaint,
        )
    }
}
