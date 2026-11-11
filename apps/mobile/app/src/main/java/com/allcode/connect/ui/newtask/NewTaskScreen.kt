package com.allcode.connect.ui.newtask

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.bool
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.data.Commands
import com.allcode.connect.data.DeliveryMode
import com.allcode.connect.data.ModelSelection
import com.allcode.connect.data.Project
import com.allcode.connect.data.ServerConfigState
import com.allcode.connect.data.WorkspaceStrategy
import com.allcode.connect.data.describeUri
import com.allcode.connect.data.uploadAttachment
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.confirmHaptic
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.rejectHaptic
import com.allcode.connect.ui.theme.AllCode
import com.allcode.connect.ui.thread.ComposerChip
import com.allcode.connect.ui.thread.ComposerIcon
import com.allcode.connect.ui.thread.ComposerShell
import com.allcode.connect.ui.thread.ModeSheet
import com.allcode.connect.ui.thread.ModelSheet
import com.allcode.connect.ui.thread.PendingAttachment
import com.allcode.connect.ui.thread.modelLabel
import com.allcode.connect.ui.thread.runtimeLabel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private enum class Where { Checkout, Worktree }

@Composable
fun NewTaskScreen(graph: AppGraph, navigator: Navigator, environmentId: String?, projectId: String?) {
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    val prefs by graph.prefs.state.collectAsStateWithLifecycle()
    var envId by rememberSaveable { mutableStateOf(environmentId ?: prefs.lastEnvironmentId) }
    val env = envs.firstOrNull { it.env.environmentId == envId } ?: envs.firstOrNull()
    val projects = env?.shell?.projects.orEmpty().filterNot { it.archived }.sortedByDescending { it.updatedAt }
    var projectSel by rememberSaveable { mutableStateOf(projectId ?: prefs.lastProjectId) }
    val project = projects.firstOrNull { it.id == projectSel } ?: projects.firstOrNull()
    val config = env?.config
    var selection by remember(env?.env?.environmentId, project?.id) { mutableStateOf(defaultSelection(project, config)) }
    var runtimeMode by rememberSaveable { mutableStateOf(prefs.defaultRuntimeMode) }
    var plan by rememberSaveable { mutableStateOf(false) }
    var where by rememberSaveable { mutableStateOf(Where.Checkout) }
    var baseRef by remember(project?.id) { mutableStateOf<String?>(null) }
    var refs by remember(project?.id) { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var text by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    // Branches load per project so worktrees start from a real ref.
    LaunchedEffect(env?.env?.environmentId, project?.workspaceRoot) {
        val connection = env?.connection ?: return@LaunchedEffect
        val root = project?.workspaceRoot ?: return@LaunchedEffect
        runCatching {
            connection.call("vcs.listRefs", buildJsonObject { put("cwd", root); put("refKind", "local"); put("limit", 50) }).asObject()
        }.getOrNull()?.let { result ->
            val list = result.objects("refs").mapNotNull { r -> r.str("name")?.let { it to ((r.bool("current") ?: false) || (r.bool("isDefault") ?: false)) } }
            refs = list
            baseRef = result.objects("refs").firstOrNull { it.bool("current") == true }?.str("name") ?: list.firstOrNull()?.first
        }
    }

    fun launch() {
        val connection = env?.connection ?: return
        val target = project ?: return
        val model = selection ?: return
        if (text.isBlank()) return
        if (attachments.any { it.uploaded == null }) {
            scope.launch { snackbar.showSnackbar("Attachments are still uploading.") }
            return
        }
        sending = true
        val threadId = UUID.randomUUID().toString()
        scope.launch {
            try {
                connection.call(
                    "orchestration.launchThread",
                    Commands.launchThread(
                        threadId = threadId,
                        projectId = target.id,
                        text = text.trim(),
                        modelSelection = model,
                        runtimeMode = runtimeMode,
                        interactionMode = if (plan) "plan" else "default",
                        workspace = if (where == Where.Worktree && baseRef != null) WorkspaceStrategy.NewWorktree(baseRef!!) else WorkspaceStrategy.Root(),
                        attachments = attachments.mapNotNull { it.uploaded },
                    ),
                )
                view.confirmHaptic()
                graph.prefs.update { it.copy(lastEnvironmentId = connection.id, lastProjectId = target.id, defaultRuntimeMode = runtimeMode) }
                navigator.thread(connection.id, threadId, replace = true)
            } catch (e: Exception) {
                view.rejectHaptic()
                snackbar.showSnackbar(e.message ?: "The task didn't start.")
            } finally {
                sending = false
            }
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        val connection = env?.connection ?: return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            val (name, size) = describeUri(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val localId = UUID.randomUUID().toString()
            attachments = attachments + PendingAttachment(localId, uri, name, mime, size)
            scope.launch {
                val result = runCatching { connection.uploadAttachment(context, uri, name, mime) }
                attachments = attachments.map {
                    if (it.localId != localId) it
                    else result.fold({ record -> it.copy(uploaded = record) }, { e -> it.copy(failed = e.message ?: "Upload failed") })
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar("New task", subtitle = env?.env?.label, onBack = navigator::back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(4.dp))
                if (envs.size > 1) {
                    Label("Machine")
                    ChoiceRow(envs.map { it.env.environmentId to it.env.label }, env?.env?.environmentId, Icons.Rounded.Computer) { envId = it; projectSel = null }
                }
                Label("Project")
                if (projects.isEmpty()) {
                    Text(
                        if (env?.shell == null) "Loading projects…" else "No projects on this machine yet. Add one in T3 Code first.",
                        style = AllCode.type.body,
                        color = AllCode.colors.inkSoft,
                    )
                } else {
                    ChoiceRow(projects.map { it.id to it.title }, project?.id, Icons.Rounded.Folder) { projectSel = it }
                }
                Label("Where")
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhereCard("This checkout", "Work in ${project?.workspaceRoot?.substringAfterLast('/') ?: "the project"} directly.", where == Where.Checkout, Modifier.weight(1f).fillMaxHeight()) { where = Where.Checkout }
                    WhereCard("New worktree", "An isolated branch so parallel tasks don't collide.", where == Where.Worktree, Modifier.weight(1f).fillMaxHeight()) { where = Where.Worktree }
                }
                if (where == Where.Worktree && refs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("start from", style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.padding(bottom = 6.dp))
                    ChoiceRow(refs.map { it.first to it.first }, baseRef, Icons.Rounded.AccountTree) { baseRef = it }
                }
                Spacer(Modifier.height(24.dp))
            }
            ComposerShell(
                text = text,
                onText = { text = it },
                suggestions = com.allcode.connect.ui.thread.rememberSuggestions(
                    text,
                    env?.connection,
                    project?.workspaceRoot,
                    config?.provider(selection?.instanceId)?.slashCommands.orEmpty(),
                ),
                placeholder = "Describe the task…",
                attachments = attachments,
                onRemoveAttachment = { id -> attachments = attachments.filterNot { it.localId == id } },
                running = false,
                sending = sending,
                onSend = { _: DeliveryMode -> launch() },
                onStop = {},
                modifier = Modifier.navigationBarsPadding(),
                leading = {
                    ComposerIcon(Icons.Rounded.Add, "Attach") { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                },
                chips = {
                    ComposerChip("» ${runtimeLabel(runtimeMode).lowercase()}") { sheet = "mode" }
                    ComposerChip(modelLabel(selection, config).lowercase(), Modifier.weight(1f, fill = false)) { sheet = "model" }
                    if (plan) ComposerChip("plan", active = true) { plan = false }
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
    }

    when (sheet) {
        "model" -> ModelSheet(config, selection, onDismiss = { sheet = null }) { selection = it }
        "mode" -> ModeSheet(
            runtimeMode, plan,
            showPlan = config?.provider(selection?.instanceId)?.showInteractionModeToggle != false,
            onDismiss = { sheet = null },
            onMode = { runtimeMode = it },
            onPlan = { plan = it },
        )
    }
}

private fun defaultSelection(project: Project?, config: ServerConfigState?): ModelSelection? {
    project?.defaultModelSelection?.let { return it }
    val provider = config?.usableProviders?.firstOrNull { it.instanceId != "devReplay" } ?: config?.usableProviders?.firstOrNull() ?: return null
    val model = provider.models.firstOrNull() ?: return null
    return ModelSelection(provider.instanceId, model.slug)
}

@Composable
private fun Label(text: String) {
    Text(text.lowercase(), style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun ChoiceRow(items: List<Pair<String, String>>, selected: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (id, label) ->
            val active = id == selected
            Row(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (active) AllCode.colors.claySoft else AllCode.colors.surface)
                    .border(1.dp, if (active) AllCode.colors.clay.copy(alpha = 0.6f) else AllCode.colors.lineStrong, RoundedCornerShape(6.dp))
                    .pressable { onSelect(id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = if (active) AllCode.colors.clay else AllCode.colors.inkFaint)
                Spacer(Modifier.width(6.dp))
                Text(label, style = AllCode.type.monoSmall, color = if (active) AllCode.colors.clay else AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WhereCard(title: String, body: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) AllCode.colors.claySoft else AllCode.colors.surface)
            .border(1.dp, if (active) AllCode.colors.clay.copy(alpha = 0.6f) else AllCode.colors.lineStrong, RoundedCornerShape(8.dp))
            .pressable(onClick = onClick).padding(12.dp),
    ) {
        Text(title, style = AllCode.type.bodyStrong, color = if (active) AllCode.colors.clay else AllCode.colors.ink)
        Text(body, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
    }
}
