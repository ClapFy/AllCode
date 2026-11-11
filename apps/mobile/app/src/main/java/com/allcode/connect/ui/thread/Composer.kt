package com.allcode.connect.ui.thread

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.allcode.connect.core.obj
import com.allcode.connect.core.str
import com.allcode.connect.data.DeliveryMode
import com.allcode.connect.data.ModelSelection
import com.allcode.connect.data.ProviderInfo
import com.allcode.connect.data.ServerConfigState
import com.allcode.connect.data.ThreadSummary
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.confirmHaptic
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.rememberFlatIndication
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

val RUNTIME_MODES = listOf(
    Triple("approval-required", "Ask first", "Approve every command and edit."),
    Triple("auto-accept-edits", "Auto edits", "Edits apply on their own; commands still ask."),
    Triple("auto", "Auto", "The agent decides what needs a check-in."),
    Triple("full-access", "Full access", "No approvals. Use on machines you trust."),
)

fun runtimeLabel(mode: String?) = RUNTIME_MODES.firstOrNull { it.first == mode }?.second ?: "Full access"

fun modelLabel(selection: ModelSelection?, config: ServerConfigState?): String {
    selection ?: return "Choose model"
    val provider = config?.provider(selection.instanceId)
    val model = provider?.models?.firstOrNull { it.slug == selection.model }
    val effort = selection.option("reasoningEffort") ?: selection.option("effort")
    val name = model?.name ?: selection.model
    return if (effort != null) "$name · ${effort.replaceFirstChar { it.uppercase() }}" else name
}

data class ComposerSuggestion(val label: String, val detail: String?, val insert: String)

/** The token being typed: a leading "/command", or an "@path" at the end of the text. */
fun activeToken(text: String): Pair<Char, String>? {
    if (text.startsWith("/") && text.none { it == ' ' || it == '\n' }) return '/' to text.drop(1)
    val last = text.substringAfterLast(' ').substringAfterLast('\n')
    return if (last.startsWith("@")) '@' to last.drop(1) else null
}

fun applySuggestion(text: String, suggestion: ComposerSuggestion): String {
    val token = activeToken(text) ?: return text
    return if (token.first == '/') suggestion.insert else text.dropLast(token.second.length + 1) + suggestion.insert
}

/** Slash commands from the provider, and project files for "@" mentions. */
@Composable
fun rememberSuggestions(
    text: String,
    connection: com.allcode.connect.data.EnvironmentConnection?,
    cwd: String?,
    commands: List<com.allcode.connect.data.SlashCommand>,
): List<ComposerSuggestion> {
    val token = activeToken(text)
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(token, cwd) {
        if (token?.first != '@' || connection == null || cwd == null) {
            files = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(150)
        files = runCatching {
            connection.call("projects.searchEntries", kotlinx.serialization.json.buildJsonObject {
                put("cwd", cwd)
                put("query", token.second)
                put("limit", 8)
            }).let { it as? kotlinx.serialization.json.JsonObject }?.let { result ->
                (result["entries"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonObject)?.str("path") }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    return when (token?.first) {
        '/' -> commands.filter { it.name.startsWith(token.second, ignoreCase = true) }.take(8)
            .map { ComposerSuggestion("/${it.name}", it.description, "/${it.name} ") }
        '@' -> files.map { ComposerSuggestion(it.substringAfterLast('/'), it, "@$it ") }
        else -> emptyList()
    }
}

@Composable
fun Composer(
    model: ThreadViewModel,
    summary: ThreadSummary?,
    projectionThread: JsonObject?,
    config: StateFlow<ServerConfigState?>?,
    running: Boolean,
    connected: Boolean,
    cwd: String?,
    modifier: Modifier = Modifier,
) {
    val draft by model.draft.collectAsStateWithLifecycle()
    val attachments by model.attachments.collectAsStateWithLifecycle()
    val sending by model.sending.collectAsStateWithLifecycle()
    val serverConfig by (config ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val selection = summary?.modelSelection ?: ModelSelection.from(projectionThread?.obj("modelSelection"))
    val runtimeMode = summary?.runtimeMode ?: projectionThread?.str("runtimeMode")
    val planMode = (summary?.interactionMode ?: projectionThread?.str("interactionMode")) == "plan"
    var modelSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris -> model.attach(context, uris) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.attach(context, uris) }

    val suggestions = rememberSuggestions(draft, model.connection, cwd, serverConfig?.provider(selection?.instanceId)?.slashCommands.orEmpty())
    ComposerShell(
        text = draft,
        suggestions = suggestions,
        onText = { model.draft.value = it },
        placeholder = when {
            !connected -> "Offline. Keep typing; it sends once you're back."
            running -> "Steer the agent, or queue a follow-up…"
            else -> "Reply to the agent…"
        },
        attachments = attachments,
        onRemoveAttachment = model::removeAttachment,
        running = running,
        sending = sending,
        onSend = model::send,
        onStop = model::stop,
        modifier = modifier,
        leading = {
            Box {
                ComposerIcon(Icons.Rounded.Add, "Attach") { attachMenu = true }
                DropdownMenu(attachMenu, { attachMenu = false }, containerColor = AllCode.colors.surface) {
                    DropdownMenuItem({ Text("Photos") }, {
                        attachMenu = false
                        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, leadingIcon = { Icon(Icons.Rounded.Image, null) })
                    DropdownMenuItem({ Text("Files") }, { attachMenu = false; files.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null) })
                }
            }
        },
        chips = {
            ComposerChip("» ${runtimeLabel(runtimeMode).lowercase()}") { modeSheet = true }
            ComposerChip(modelLabel(selection, serverConfig).lowercase(), Modifier.weight(1f, fill = false)) { modelSheet = true }
            if (planMode) ComposerChip("plan", active = true) { model.setInteractionMode("default") }
        },
    )

    if (modelSheet) {
        ModelSheet(serverConfig, selection, onDismiss = { modelSheet = false }) { model.setModel(it) }
    }
    if (modeSheet) {
        ModeSheet(
            runtimeMode,
            planMode,
            showPlan = serverConfig?.provider(selection?.instanceId)?.showInteractionModeToggle != false,
            onDismiss = { modeSheet = false },
            onMode = model::setRuntimeMode,
            onPlan = { model.setInteractionMode(if (it) "plan" else "default") },
        )
    }
}

/**
 * The input shared by the thread and new-task screens: a prompt line in a hairline box, and under it
 * a status line (attach, permissions, model) that truncates instead of scrolling sideways.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerShell(
    text: String,
    onText: (String) -> Unit,
    suggestions: List<ComposerSuggestion> = emptyList(),
    placeholder: String,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (String) -> Unit,
    running: Boolean,
    sending: Boolean,
    onSend: (DeliveryMode) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    chips: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    var sendMenu by remember { mutableStateOf(false) }
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !sending
    val colors = AllCode.colors
    Column(modifier.fillMaxWidth().background(colors.paper)) {
        Divider()
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                suggestions.forEach { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).pressable(haptic = false) { onText(applySuggestion(text, suggestion)) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(suggestion.label, style = AllCode.type.mono, color = colors.ink, maxLines = 1)
                        suggestion.detail?.takeIf { it != suggestion.label }?.let {
                            Spacer(Modifier.width(10.dp))
                            Text(it, style = AllCode.type.monoSmall, color = colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Divider()
        }
        if (attachments.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { AttachmentChip(it) { onRemoveAttachment(it.localId) } }
            }
        }
        // Text set from outside (a picked suggestion, a cleared draft) puts the caret at the end.
        var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
        if (field.text != text) field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(colors.surface)
                .border(1.dp, if (focused) colors.clay.copy(alpha = 0.7f) else colors.lineStrong, RoundedCornerShape(10.dp))
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.width(22.dp).height(32.dp), contentAlignment = Alignment.Center) {
                Text("❯", style = AllCode.type.monoStrong, color = colors.clay)
            }
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    if (next.text != text) onText(next.text)
                },
                textStyle = AllCode.type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.clay),
                interactionSource = source,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp, max = 168.dp).padding(start = 4.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(placeholder, style = AllCode.type.body, color = colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            Box {
                val stop = running && !canSend
                val enabled = stop || canSend
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                stop -> colors.ink
                                enabled -> colors.clay
                                else -> colors.inset
                            },
                        )
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberFlatIndication(),
                            enabled = enabled,
                            onLongClick = { if (running && canSend) { view.confirmHaptic(); sendMenu = true } },
                            onClick = { view.confirmHaptic(); if (stop) onStop() else onSend(DeliveryMode.Auto) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        stop -> Box(Modifier.size(10.dp).background(colors.paper))
                        sending -> Spark(13.dp, colors.onClay)
                        else -> Icon(Icons.Rounded.ArrowUpward, "Send", Modifier.size(18.dp), tint = if (enabled) colors.onClay else colors.inkFaint)
                    }
                }
                DropdownMenu(sendMenu, { sendMenu = false }, containerColor = colors.surface) {
                    DropdownMenuItem({ Text("Steer now") }, { sendMenu = false; onSend(DeliveryMode.Steer) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) })
                    DropdownMenuItem({ Text("Queue for after this run") }, { sendMenu = false; onSend(DeliveryMode.Queue) }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            chips()
        }
    }
}

@Composable
fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = AllCode.colors.inkSoft)
    }
}

/** One tappable segment of the composer's status line. */
@Composable
fun ComposerChip(text: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = AllCode.type.monoSmall,
        color = if (active) AllCode.colors.clay else AllCode.colors.inkSoft,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).pressable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
    )
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(AllCode.colors.inset)) {
        if (attachment.isImage) {
            AsyncImage(attachment.uri, attachment.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
        } else {
            Text(attachment.name, style = AllCode.type.monoSmall, color = AllCode.colors.inkSoft, maxLines = 3, modifier = Modifier.padding(6.dp))
        }
        when {
            attachment.failed != null -> Box(Modifier.size(64.dp).background(AllCode.colors.rustSoft.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, attachment.failed, tint = AllCode.colors.rust)
            }
            attachment.uploaded == null -> Box(Modifier.size(64.dp).background(AllCode.colors.paper.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Spark(14.dp)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(RoundedCornerShape(4.dp)).background(AllCode.colors.ink.copy(alpha = 0.7f)).pressable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(13.dp), tint = AllCode.colors.paper) }
    }
}

@Composable
fun ModelSheet(config: ServerConfigState?, selection: ModelSelection?, onDismiss: () -> Unit, onSelect: (ModelSelection) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var current by remember { mutableStateOf(selection) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = AllCode.colors.paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Model", style = AllCode.type.title, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
            Text("applies to the next turn", style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint, modifier = Modifier.padding(horizontal = 4.dp))
            val providers = config?.usableProviders.orEmpty()
            if (providers.isEmpty()) {
                Text("No providers are ready on this machine.", style = AllCode.type.body, color = AllCode.colors.inkSoft, modifier = Modifier.padding(vertical = 16.dp))
            }
            providers.forEach { provider ->
                ProviderModels(provider, current) { picked ->
                    current = picked
                    onSelect(picked)
                }
            }
            val provider = config?.provider(current?.instanceId)
            val modelInfo = provider?.models?.firstOrNull { it.slug == current?.model }
            modelInfo?.options?.filter { it.choices.isNotEmpty() }?.forEach { option ->
                Text(option.label.lowercase(), style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    option.choices.forEach { choice ->
                        val active = (current?.option(option.id) ?: option.currentValue ?: option.choices.firstOrNull { it.isDefault }?.id) == choice.id
                        Text(
                            choice.label,
                            style = AllCode.type.mono,
                            color = if (active) AllCode.colors.clay else AllCode.colors.ink,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (active) AllCode.colors.claySoft else Color.Transparent)
                                .border(1.dp, if (active) AllCode.colors.clay.copy(alpha = 0.5f) else AllCode.colors.lineStrong, RoundedCornerShape(6.dp))
                                .pressable {
                                    val next = current?.withOption(option.id, choice.id) ?: return@pressable
                                    current = next
                                    onSelect(next)
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderModels(provider: ProviderInfo, current: ModelSelection?, onPick: (ModelSelection) -> Unit) {
    Text(provider.displayName.lowercase(), style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp))
    provider.models.forEach { model ->
        val selected = current?.instanceId == provider.instanceId && current.model == model.slug
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (selected) AllCode.colors.claySoft else Color.Transparent)
                .pressable {
                    val options = if (current?.instanceId == provider.instanceId) current.options else emptyList()
                    onPick(ModelSelection(provider.instanceId, model.slug, options))
                }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (selected) "❯" else "", style = AllCode.type.monoStrong, color = AllCode.colors.clay, modifier = Modifier.width(18.dp))
            Text(model.name, style = AllCode.type.body, color = if (selected) AllCode.colors.clay else AllCode.colors.ink, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ModeSheet(
    runtimeMode: String?,
    planMode: Boolean,
    showPlan: Boolean,
    onDismiss: () -> Unit,
    onMode: (String) -> Unit,
    onPlan: (Boolean) -> Unit,
) {
    val view = LocalView.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = AllCode.colors.paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Permissions", style = AllCode.type.title, color = AllCode.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.size(6.dp))
            RUNTIME_MODES.forEachIndexed { index, (id, label, body) ->
                val selected = runtimeMode == id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (selected) AllCode.colors.claySoft else Color.Transparent)
                        .pressable { view.confirmHaptic(); onMode(id); onDismiss() }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(if (selected) "❯" else "", style = AllCode.type.monoStrong, color = AllCode.colors.clay, modifier = Modifier.width(18.dp))
                    Text("${index + 1}.", style = AllCode.type.mono, color = AllCode.colors.inkFaint, modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = AllCode.type.bodyStrong, color = if (selected) AllCode.colors.clay else AllCode.colors.ink)
                        Text(body, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
                    }
                }
            }
            if (showPlan) {
                Spacer(Modifier.size(8.dp))
                Divider()
                Spacer(Modifier.size(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (planMode) AllCode.colors.claySoft else Color.Transparent)
                        .pressable { onPlan(!planMode); onDismiss() }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("»", style = AllCode.type.monoStrong, color = if (planMode) AllCode.colors.clay else AllCode.colors.inkSoft, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (planMode) "Leave plan mode" else "Plan first", style = AllCode.type.bodyStrong, color = AllCode.colors.ink)
                        Text("The agent proposes a plan before changing anything.", style = AllCode.type.caption, color = AllCode.colors.inkSoft)
                    }
                }
            }
        }
    }
}
