package com.allcode.connect.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.data.Commands
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.data.EnvSnapshot
import com.allcode.connect.data.EnvThread
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.AllCodeMark
import com.allcode.connect.ui.components.ConnectionStrip
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.FilterOption
import com.allcode.connect.ui.components.IconAction
import com.allcode.connect.ui.components.PhaseGlyph
import com.allcode.connect.ui.components.SectionLabel
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.TextInput
import com.allcode.connect.ui.components.confirmHaptic
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.relativeTime
import com.allcode.connect.ui.components.rememberFlatIndication
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

private data class HomeSections(
    val needsYou: List<EnvThread>,
    val working: List<EnvThread>,
    val pinned: List<EnvThread>,
    val recent: List<EnvThread>,
    val earlier: List<EnvThread>,
    val snoozed: List<EnvThread>,
) {
    val isEmpty get() = needsYou.isEmpty() && working.isEmpty() && pinned.isEmpty() && recent.isEmpty() && earlier.isEmpty() && snoozed.isEmpty()
}

private fun sections(threads: List<EnvThread>, machine: String?, query: String): HomeSections {
    val now = Instant.now()
    val q = query.trim().lowercase()
    val visible = threads.filter { t ->
        !t.thread.archived && !t.thread.isSubagent &&
            (machine == null || t.environmentId == machine) &&
            (q.isEmpty() || t.thread.title.lowercase().contains(q) || t.project?.title?.lowercase()?.contains(q) == true ||
                t.thread.branch?.lowercase()?.contains(q) == true || t.thread.latestMessageText?.lowercase()?.contains(q) == true)
    }.sortedByDescending { it.thread.updatedAt }
    val snoozed = visible.filter { it.thread.snoozedUntil?.isAfter(now) == true && it.thread.phase != ThreadPhase.NeedsYou }
    val active = visible - snoozed.toSet()
    val needsYou = active.filter { it.thread.phase == ThreadPhase.NeedsYou }
    val working = active.filter { it.thread.phase == ThreadPhase.Working || it.thread.phase == ThreadPhase.Queued }
    val rest = active - needsYou.toSet() - working.toSet()
    val pinned = rest.filter { it.thread.pinned }
    val unpinned = rest - pinned.toSet()
    val cutoff = now.minus(3, ChronoUnit.DAYS)
    return HomeSections(
        needsYou = needsYou,
        working = working,
        pinned = pinned,
        recent = unpinned.filter { (it.thread.updatedAt ?: Instant.EPOCH).isAfter(cutoff) },
        earlier = unpinned.filter { !(it.thread.updatedAt ?: Instant.EPOCH).isAfter(cutoff) },
        snoozed = snoozed,
    )
}

@Composable
fun TasksTab(graph: AppGraph, navigator: Navigator) {
    val threads by graph.connections.threads.collectAsStateWithLifecycle(emptyList())
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    var machine by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    var showSnoozed by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EnvThread?>(null) }
    val data = remember(threads, machine, query) { sections(threads, machine, query) }
    val live = remember(envs) { envs.filter { it.status == ConnectionStatus.Connected }.map { it.env.environmentId }.toSet() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var refreshing by remember { mutableStateOf(false) }

    fun run(env: String, command: kotlinx.serialization.json.JsonObject) {
        scope.launch {
            runCatching { graph.connections.connection(env)?.dispatch(command) }
                .onFailure { snackbar.showSnackbar(it.message ?: "That didn't go through.") }
        }
    }

    fun archive(item: EnvThread) {
        view.confirmHaptic()
        run(item.environmentId, Commands.archive(item.thread.id))
        scope.launch {
            val result = snackbar.showSnackbar("Archived “${item.thread.title}”", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) run(item.environmentId, Commands.unarchive(item.thread.id))
        }
    }

    fun togglePin(item: EnvThread) {
        view.confirmHaptic()
        run(item.environmentId, if (item.thread.pinned) Commands.unpin(item.thread.id) else Commands.pin(item.thread.id))
    }

    fun snooze(item: EnvThread, hours: Long) {
        run(item.environmentId, Commands.snooze(item.thread.id, Instant.now().plus(hours, ChronoUnit.HOURS)))
        scope.launch { snackbar.showSnackbar("Snoozed for ${if (hours >= 24) "a day" else "$hours h"}") }
    }

    val actions = RowActions(
        open = { navigator.thread(it.environmentId, it.thread.id) },
        archive = ::archive,
        pin = ::togglePin,
        snooze = ::snooze,
        rename = { renaming = it },
        unsnooze = { run(it.environmentId, Commands.unsnooze(it.thread.id)) },
        live = live,
    )

    Column(Modifier.fillMaxSize()) {
        Header(envs, searching, query, { query = it }, onSearch = { searching = !searching; if (!searching) query = "" }, onSettings = navigator::settings)
        val phoneOnline by graph.connections.networkUp.collectAsStateWithLifecycle()
        envs.forEach { env ->
            ConnectionStrip(
                phoneOnline = phoneOnline,
                label = env.env.label,
                status = env.status,
                hadData = env.shell != null,
                onRetry = { env.connection.retryNow() },
                onRepair = { navigator.pair() },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    graph.connections.connections.value.forEach { it.retryNow() }
                    scope.launch { delay(700); refreshing = false }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    if (envs.size > 1) {
                        item(key = "machines") { MachineFilter(envs, machine) { machine = it } }
                    }
                    if (data.isEmpty) {
                        item(key = "empty") {
                            val syncing = envs.any { it.shell == null }
                            if (syncing) Syncing(envs) else EmptyState(
                                if (query.isNotEmpty()) "Nothing matches" else "A quiet desk",
                                if (query.isNotEmpty()) "Try another word, or clear the search." else "Start a task and your agents' work will show up here.",
                            )
                        }
                    }
                    section("Needs you", data.needsYou, actions, accent = true)
                    section("Working", data.working, actions)
                    section("Pinned", data.pinned, actions)
                    section("Recent", data.recent, actions)
                    if (data.earlier.isNotEmpty()) {
                        item(key = "earlier-toggle") {
                            CollapsibleLabel("Earlier", data.earlier.size, showEarlier) { showEarlier = !showEarlier }
                        }
                        if (showEarlier) rows("earlier", data.earlier, actions)
                    }
                    if (data.snoozed.isNotEmpty()) {
                        item(key = "snoozed-toggle") {
                            CollapsibleLabel("Snoozed", data.snoozed.size, showSnoozed) { showSnoozed = !showSnoozed }
                        }
                        if (showSnoozed) rows("snoozed", data.snoozed, actions)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        NewTaskBar { navigator.newTask(machine ?: graph.prefs.state.value.lastEnvironmentId) }
    }

    renaming?.let { target ->
        RenameDialog(target.thread.title, onDismiss = { renaming = null }) { title ->
            run(target.environmentId, Commands.rename(target.thread.id, title))
            renaming = null
        }
    }
}

private class RowActions(
    val open: (EnvThread) -> Unit,
    val archive: (EnvThread) -> Unit,
    val pin: (EnvThread) -> Unit,
    val snooze: (EnvThread, Long) -> Unit,
    val rename: (EnvThread) -> Unit,
    val unsnooze: (EnvThread) -> Unit,
    val live: Set<String>,
)

private fun LazyListScope.section(title: String, items: List<EnvThread>, actions: RowActions, accent: Boolean = false) {
    if (items.isEmpty()) return
    item(key = "label-$title") {
        SectionLabel(title, trailing = {
            Text("${items.size}", style = AllCode.type.overline, color = if (accent) AllCode.colors.clay else AllCode.colors.inkFaint)
        })
    }
    rows(title, items, actions)
}

private fun LazyListScope.rows(group: String, items: List<EnvThread>, actions: RowActions) {
    items(items, key = { "$group:${it.environmentId}:${it.thread.id}" }) { item ->
        SwipeRow(item, actions)
    }
}

@Composable
private fun Header(
    envs: List<EnvSnapshot>,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = AllCode.colors
    val online = envs.count { it.status == ConnectionStatus.Connected }
    val working = envs.sumOf { e -> e.shell?.threads?.values?.count { it.phase == ThreadPhase.Working && !it.isSubagent } ?: 0 }
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AllCodeMark(30.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("AllCode", style = AllCode.type.heading, color = colors.ink)
                Text(
                    buildString {
                        append(
                            when {
                                envs.isEmpty() -> "no machines yet"
                                envs.size == 1 -> "${envs[0].env.label} · ${statusWord(envs[0].status)}"
                                else -> "$online/${envs.size} machines online"
                            },
                        )
                        if (working > 0) append(" · $working working")
                    },
                    style = AllCode.type.monoSmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search", onSearch)
            IconAction(Icons.Rounded.Settings, "Settings", onSettings)
        }
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextInput(query, onQuery, "Search title, project, branch, or message", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).focusRequester(focus))
        }
        Divider()
    }
}

fun statusWord(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Connected -> "online"
    ConnectionStatus.Syncing -> "syncing"
    is ConnectionStatus.Connecting, ConnectionStatus.Offline -> "connecting"
    is ConnectionStatus.Retrying -> "unreachable"
    is ConnectionStatus.Blocked -> "needs attention"
}

@Composable
private fun MachineFilter(envs: List<EnvSnapshot>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip("all", selected == null) { onSelect(null) }
        envs.forEach { env -> FilterChip(env.env.label, selected == env.env.environmentId, env.status) { onSelect(env.env.environmentId) } }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, status: ConnectionStatus? = null, onClick: () -> Unit) {
    val dot = status?.let { @Composable { Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor(it))) } }
    FilterOption(text, selected, dot, onClick)
}

@Composable
fun statusColor(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Connected -> AllCode.colors.sage
    is ConnectionStatus.Blocked -> AllCode.colors.rust
    is ConnectionStatus.Retrying -> AllCode.colors.amber
    ConnectionStatus.Offline -> AllCode.colors.lineStrong
    else -> AllCode.colors.amber
}

@Composable
private fun Syncing(envs: List<EnvSnapshot>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Spark(14.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "catching up with ${envs.firstOrNull { it.shell == null }?.env?.label ?: "your machine"}…",
            style = AllCode.type.mono,
            color = AllCode.colors.inkSoft,
        )
    }
}

@Composable
private fun CollapsibleLabel(text: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(haptic = false, onClick = onToggle).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${if (open) "▾" else "▸"} ${text.lowercase()}", style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.weight(1f))
        Text("$count", style = AllCode.type.overline, color = AllCode.colors.inkFaint)
    }
}

@Composable
private fun SwipeRow(item: EnvThread, actions: RowActions, modifier: Modifier = Modifier) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> { actions.archive(item); state.snapTo(SwipeToDismissBoxValue.Settled) }
            SwipeToDismissBoxValue.StartToEnd -> { actions.pin(item); state.snapTo(SwipeToDismissBoxValue.Settled) }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toArchive = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(if (toArchive) AllCode.colors.inset else AllCode.colors.claySoft).padding(horizontal = 20.dp),
                horizontalArrangement = if (toArchive) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (toArchive) "archive" else if (item.thread.pinned) "unpin" else "pin",
                    style = AllCode.type.mono,
                    color = if (toArchive) AllCode.colors.inkSoft else AllCode.colors.clay,
                )
            }
        },
    ) {
        ThreadRow(item, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(item: EnvThread, actions: RowActions) {
    val thread = item.thread
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    val colors = AllCode.colors
    Box {
        Row(
            Modifier.fillMaxWidth().background(colors.paper)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberFlatIndication(),
                    onLongClick = { if (com.allcode.connect.ui.components.Haptics.enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); menu = true },
                    onClick = { actions.open(item) },
                )
                .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(18.dp).height(22.dp), contentAlignment = Alignment.Center) {
                PhaseGlyph(thread.phase, unread = thread.unread, spinning = item.environmentId in actions.live)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.title,
                        style = if (thread.unread) AllCode.type.bodyStrong else AllCode.type.body,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(thread.updatedAt), style = AllCode.type.monoSmall, color = colors.inkFaint)
                }
                val preview = when (thread.phase) {
                    ThreadPhase.NeedsYou -> when (thread.pendingRequestKind) {
                        "user_input" -> "Has a question for you"
                        null -> "A plan is ready for your review"
                        else -> "Wants your approval to continue"
                    }
                    ThreadPhase.Failed -> thread.lastError ?: "Stopped with an error"
                    ThreadPhase.Queued -> "Queued, waiting for the current run"
                    else -> thread.latestMessageText?.replace('\n', ' ')
                }
                if (!preview.isNullOrBlank()) {
                    Text(
                        preview,
                        style = AllCode.type.caption,
                        color = when (thread.phase) {
                            ThreadPhase.NeedsYou -> colors.clay
                            ThreadPhase.Failed -> colors.rust
                            else -> colors.inkSoft
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                val meta = listOfNotNull(
                    if (thread.pinned) "pinned" else null,
                    item.project?.title,
                    thread.branch,
                    if (thread.pullRequestCount > 0) "pr" else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = AllCode.type.monoSmall, color = colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
            DropdownMenuItem({ Text(if (thread.pinned) "Unpin" else "Pin") }, { menu = false; actions.pin(item) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
            DropdownMenuItem({ Text("Rename") }, { menu = false; actions.rename(item) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            if (thread.snoozedUntil?.isAfter(Instant.now()) == true) {
                DropdownMenuItem({ Text("Unsnooze") }, { menu = false; actions.unsnooze(item) }, leadingIcon = { Icon(Icons.Rounded.Snooze, null) })
            } else {
                DropdownMenuItem({ Text("Snooze 1 hour") }, { menu = false; actions.snooze(item, 1) }, leadingIcon = { Icon(Icons.Rounded.Snooze, null) })
                DropdownMenuItem({ Text("Snooze until tomorrow") }, { menu = false; actions.snooze(item, 24) }, leadingIcon = { Icon(Icons.Rounded.Snooze, null) })
            }
            DropdownMenuItem({ Text("Archive") }, { menu = false; actions.archive(item) }, leadingIcon = { Icon(Icons.Rounded.Archive, null) })
        }
    }
}

@Composable
private fun NewTaskBar(onClick: () -> Unit) {
    val colors = AllCode.colors
    Column(Modifier.fillMaxWidth().background(colors.paper)) {
        Divider()
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(colors.surface)
                .border(1.dp, colors.lineStrong, RoundedCornerShape(10.dp))
                .pressable(onClick = onClick)
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp).height(32.dp), contentAlignment = Alignment.Center) {
                Text("❯", style = AllCode.type.monoStrong, color = colors.clay)
            }
            Text("What should we work on?", style = AllCode.type.body, color = colors.inkFaint, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(colors.clay), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "New task", Modifier.size(18.dp), tint = colors.onClay)
            }
        }
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AllCode.colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Rename", style = AllCode.type.title) },
        text = { TextInput(title, { title = it }, "Title", ime = ImeAction.Done, onIme = { if (title.isNotBlank()) onSave(title) }) },
        confirmButton = { TextButton({ onSave(title) }, enabled = title.isNotBlank()) { Text("Save", color = AllCode.colors.clay) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = AllCode.colors.inkSoft) } },
    )
}
