package com.allcode.connect.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.allcode.connect.AppGraph
import com.allcode.connect.core.str
import com.allcode.connect.data.ThreadSummary
import com.allcode.connect.data.TimelineEntry
import com.allcode.connect.ui.Navigator
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.ui.components.ConnectionStrip
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.IconAction
import com.allcode.connect.ui.components.LocalLive
import com.allcode.connect.ui.components.PhaseGlyph
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.home.RenameDialog
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(graph: AppGraph, navigator: Navigator, environmentId: String, threadId: String) {
    val model: ThreadViewModel = viewModel(
        key = "$environmentId/$threadId",
        factory = viewModelFactory { initializer { ThreadViewModel(graph, environmentId, threadId) } },
    )
    val projection by model.projection.collectAsStateWithLifecycle()
    val timeline by model.timeline.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val loadingOlder by model.loadingOlder.collectAsStateWithLifecycle()
    val shell by (model.connection?.shell ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val status by (model.connection?.status ?: kotlinx.coroutines.flow.MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected)).collectAsStateWithLifecycle()
    val machine = model.connection?.env?.collectAsStateWithLifecycle()?.value?.label ?: "your machine"
    val summary: ThreadSummary? = shell?.threads?.get(threadId) ?: shell?.archived?.get(threadId)
    val project = shell?.project(summary?.projectId ?: projection?.thread?.str("projectId"))
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); model.notice.value = null }
    }

    // The list is reversed: index 0 sits on the composer, so streaming text stays anchored.
    val reversed = remember(timeline) { timeline.asReversed() }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - 3 }
            .collect { model.loadOlder() }
    }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    // An insert at index 0 shifts what was at the bottom, so "follow" is decided by the user's last scroll.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to atBottom }.collect { (scrolling, bottom) ->
            if (scrolling || wasScrolling) follow = bottom
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(timeline.size) {
        if (follow && timeline.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val title = summary?.title ?: projection?.thread?.str("title") ?: "Thread"
    val subtitle = listOfNotNull(project?.title, summary?.branch ?: projection?.thread?.str("branch")).joinToString(" · ").ifEmpty { null }
    val running = projection?.activeRun != null

    Box(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        CompositionLocalProvider(LocalLive provides (status == ConnectionStatus.Connected)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                title = title,
                subtitle = subtitle,
                onBack = navigator::back,
                divider = false,
                actions = {
                    summary?.let { Box(Modifier.padding(end = 4.dp)) { PhaseGlyph(it.phase, spinning = true) } }
                    IconAction(Icons.Rounded.Difference, "Changes", { navigator.diff(environmentId, threadId) })
                    Box {
                        IconAction(Icons.Rounded.MoreVert, "More", { menu = true })
                        DropdownMenu(menu, { menu = false }, containerColor = AllCode.colors.surface) {
                            DropdownMenuItem({ Text("Terminal") }, { menu = false; navigator.terminal(environmentId, threadId) }, leadingIcon = { Icon(Icons.Rounded.Terminal, null) })
                            DropdownMenuItem({ Text("Preview") }, { menu = false; navigator.preview(environmentId, threadId) }, leadingIcon = { Icon(Icons.Rounded.Web, null) })
                            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text(if (summary?.pinned == true) "Unpin" else "Pin") }, {
                                menu = false; model.togglePin(summary?.pinned == true)
                            }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                            DropdownMenuItem({ Text("Copy last reply") }, {
                                menu = false
                                timeline.lastOrNull { it is TimelineEntry.Assistant }?.let { copy(context, (it as TimelineEntry.Assistant).text) }
                            }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                            DropdownMenuItem({ Text("Archive") }, { menu = false; model.archive { scope.launch { navigator.back() } } }, leadingIcon = { Icon(Icons.Rounded.Archive, null) })
                        }
                    }
                },
            )
            Divider()
            ConnectionStrip(
                label = machine,
                status = status,
                hadData = projection != null,
                onRetry = { model.connection?.retryNow() },
                onRepair = { navigator.pair() },
                phoneOnline = graph.connections.networkUp.collectAsStateWithLifecycle().value,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    projection == null && failure != null -> EmptyState("Couldn't open this thread", failure ?: "")
                    projection == null -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Spark(14.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("opening thread…", style = AllCode.type.mono, color = AllCode.colors.inkSoft)
                    }
                    timeline.isEmpty() -> EmptyState("Fresh page", "Send the first message to get the agent started.")
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                    ) {
                        itemsIndexed(reversed, key = { _, entry -> entry.key }, contentType = { _, entry -> entry::class }) { _, entry ->
                            TimelineItem(entry, model, navigator, environmentId)
                        }
                        if (projection?.hasMoreHistory == true) {
                            item(key = "older") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (loadingOlder) Spark(14.dp)
                                    else Text("scroll up for earlier messages", style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint)
                                }
                            }
                        }
                    }
                }

                if (!atBottom && timeline.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp).size(36.dp)
                            .clip(RoundedCornerShape(8.dp)).background(AllCode.colors.surface)
                            .border(1.dp, AllCode.colors.lineStrong, RoundedCornerShape(8.dp))
                            .pressable { scope.launch { listState.animateScrollToItem(0) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest", Modifier.size(20.dp), tint = AllCode.colors.ink) }
                }
            }

            QueuedStrip(projection?.queuedRuns.orEmpty().mapNotNull { run ->
                val id = run.str("id") ?: return@mapNotNull null
                val text = projection?.messages?.firstOrNull { it.str("id") == run.str("userMessageId") }?.str("text") ?: "Queued message"
                id to text
            }, running, onCancel = model::cancelQueued, onSteer = model::steerQueued)

            Composer(
                model = model,
                summary = summary,
                projectionThread = projection?.thread,
                config = model.connection?.config,
                running = running,
                connected = status == ConnectionStatus.Connected,
                cwd = summary?.worktreePath ?: project?.workspaceRoot,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
    }

    if (renaming) {
        RenameDialog(title, onDismiss = { renaming = false }) { model.rename(it); renaming = false }
    }
}

@Composable
private fun QueuedStrip(queued: List<Pair<String, String>>, running: Boolean, onCancel: (String) -> Unit, onSteer: (String) -> Unit) {
    if (queued.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Divider()
        queued.forEach { (id, text) ->
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("queued", style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.width(58.dp))
                Text(text, style = AllCode.type.body, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (running) {
                    Text("steer", style = AllCode.type.mono, color = AllCode.colors.clay, modifier = Modifier.clip(RoundedCornerShape(6.dp)).pressable { onSteer(id) }.padding(8.dp))
                }
                Text("cancel", style = AllCode.type.mono, color = AllCode.colors.inkSoft, modifier = Modifier.clip(RoundedCornerShape(6.dp)).pressable { onCancel(id) }.padding(8.dp))
            }
        }
    }
}

fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AllCode", text))
}
