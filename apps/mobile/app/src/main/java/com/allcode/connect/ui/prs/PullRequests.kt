package com.allcode.connect.ui.prs

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.bool
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.data.Commands
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.data.ModelSelection
import com.allcode.connect.data.WorkspaceStrategy
import com.allcode.connect.data.parseInstant
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.FilterRow
import com.allcode.connect.ui.components.Spark
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.Pill
import com.allcode.connect.ui.components.PrimaryButton
import com.allcode.connect.ui.components.QuietButton
import com.allcode.connect.ui.components.SectionLabel
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.components.relativeTime
import com.allcode.connect.ui.theme.AllCode
import com.allcode.connect.ui.thread.ProseMarkdown
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private data class PrRow(val environmentId: String, val environmentLabel: String, val entry: JsonObject)

private data class PrLoad(val rows: List<PrRow>, val problems: List<String>)

private suspend fun loadPullRequests(graph: AppGraph, state: String): PrLoad = coroutineScope {
    val connections = graph.connections.connections.value.filter { it.status.value == ConnectionStatus.Connected }
    val results = connections.map { connection ->
        async {
            runCatching {
                connection.call("pullRequests.list", buildJsonObject { put("state", state); put("limit", 100) }).asObject()
            }.fold(
                onSuccess = { result ->
                    val rows = result?.objects("entries").orEmpty().map { PrRow(connection.id, connection.env.value.label, it) }
                    val errors = result?.objects("errors").orEmpty().mapNotNull { it.str("message") ?: it.obj("error")?.str("message") }
                    rows to errors.map { "${connection.env.value.label}: $it" }
                },
                onFailure = { e ->
                    val message = (e as? com.allcode.connect.net.RpcException)?.error?.str("requirement") ?: e.message ?: "Couldn't load pull requests."
                    emptyList<PrRow>() to listOf("${connection.env.value.label}: $message")
                },
            )
        }
    }.awaitAll()
    PrLoad(
        rows = results.flatMap { it.first }.sortedByDescending { it.entry.str("updatedAt") },
        problems = results.flatMap { it.second }.distinct(),
    )
}

@Composable
fun PullRequestsTab(graph: AppGraph, navigator: Navigator) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<PrLoad?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    val connected = envs.count { it.status == ConnectionStatus.Connected }
    val scope = rememberCoroutineScope()

    LaunchedEffect(filter, connected, tick) {
        if (connected == 0) return@LaunchedEffect
        load = loadPullRequests(graph, if (filter == 2) "merged" else "open")
        refreshing = false
    }
    val rows = load?.rows.orEmpty().filter { row ->
        when (filter) {
            1 -> row.entry.bool("viewerReviewRequested") == true
            else -> true
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Pull requests", subtitle = "across every project on your machines", divider = false)
        FilterRow(listOf("open", "needs my review", "merged"), filter) { filter = it }
        Divider()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; tick++; scope.launch { delay(4000); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            when {
                connected == 0 -> EmptyState("Offline", "Connect to a machine to see its pull requests.")
                load == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spark(16.dp) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    load!!.problems.forEach { problem ->
                        item(key = "p:$problem") {
                            Text(
                                problem,
                                style = AllCode.type.caption,
                                color = AllCode.colors.inkSoft,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AllCode.colors.amberSoft).padding(12.dp),
                            )
                        }
                    }
                    if (rows.isEmpty()) item {
                        // Every machine failing reads differently from an empty list.
                        if (load!!.problems.isNotEmpty() && load!!.rows.isEmpty()) {
                            EmptyState("Couldn't load pull requests", "Fix the issue above on your machine, then pull to refresh.")
                        } else {
                            EmptyState("All clear", if (filter == 1) "Nobody is waiting on your review." else "No pull requests here.")
                        }
                    }
                    items(rows, key = { "${it.environmentId}:${it.entry.str("repository")}#${it.entry.int("number")}" }) { row ->
                        PrRowView(row) {
                            navigator.pullRequest(row.environmentId, row.entry.str("projectId") ?: "", row.entry.str("repository") ?: "", row.entry.int("number") ?: 0, row.entry.str("title") ?: "")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun checksIcon(state: String?) = when (state) {
    "passing" -> Icons.Rounded.CheckCircle to AllCode.colors.sage
    "failing" -> Icons.Rounded.Cancel to AllCode.colors.rust
    "pending" -> Icons.Rounded.HourglassTop to AllCode.colors.amber
    else -> null
}

@Composable
private fun PrRowView(row: PrRow, onClick: () -> Unit) {
    val e = row.entry
    Row(Modifier.fillMaxWidth().pressable(haptic = false, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (e.bool("isDraft") == true) AllCode.colors.inset else AllCode.colors.sageSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MergeType, null, Modifier.size(17.dp), tint = if (e.bool("isDraft") == true) AllCode.colors.inkFaint else AllCode.colors.sage)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.str("title") ?: "", style = AllCode.type.bodyStrong, color = AllCode.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                checksIcon(e.str("checksState"))?.let { (icon, tint) -> Icon(icon, null, Modifier.size(16.dp), tint = tint) }
            }
            Text(
                "${e.str("repository")} #${e.int("number")} · ${e.obj("author")?.str("login") ?: "unknown"} · ${relativeTime(parseInstant(e.str("updatedAt")))}",
                style = AllCode.type.caption,
                color = AllCode.colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${e.str("headBranch")} → ${e.str("baseBranch")}", tone = AllCode.colors.inkFaint, container = AllCode.colors.paperDeep)
                when (e.str("reviewDecision")) {
                    "approved" -> Pill("Approved", tone = AllCode.colors.sage, container = AllCode.colors.sageSoft)
                    "changes-requested" -> Pill("Changes requested", tone = AllCode.colors.rust, container = AllCode.colors.rustSoft)
                }
                if (e.bool("viewerReviewRequested") == true) Pill("Your review", tone = AllCode.colors.clay, container = AllCode.colors.claySoft)
            }
        }
    }
}

@Composable
fun PullRequestScreen(graph: AppGraph, navigator: Navigator, environmentId: String, projectId: String, repository: String, number: Int, title: String) {
    val connection = graph.connections.connection(environmentId)
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(number) {
        runCatching {
            connection!!.call("pullRequests.detail", buildJsonObject { put("projectId", projectId); put("repository", repository); put("number", number) }).asObject()
        }.onSuccess { detail = it }.onFailure { error = it.message }
    }

    fun startTask(prompt: String) {
        val conn = connection ?: return
        val config = conn.config.value ?: return
        val project = conn.shell.value?.project(projectId)
        val selection = project?.defaultModelSelection
            ?: config.usableProviders.firstOrNull { it.instanceId != "devReplay" }?.let { p -> p.models.firstOrNull()?.let { ModelSelection(p.instanceId, it.slug) } }
            ?: return
        starting = true
        val threadId = UUID.randomUUID().toString()
        scope.launch {
            runCatching {
                conn.call("orchestration.launchThread", Commands.launchThread(threadId, projectId, prompt, selection, graph.prefs.state.value.defaultRuntimeMode, "default", WorkspaceStrategy.Root()))
            }.onSuccess { navigator.thread(environmentId, threadId) }.onFailure { error = it.message }
            starting = false
        }
    }

    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        TopBar("$repository #$number", onBack = navigator::back, actions = {
            detail?.str("url")?.let { url ->
                com.allcode.connect.ui.components.IconAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                })
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(detail?.str("title") ?: title, style = AllCode.type.title, color = AllCode.colors.ink)
            Spacer(Modifier.height(6.dp))
            detail?.let { d ->
                Text(
                    "${d.obj("author")?.str("login") ?: "someone"} wants to merge ${d.str("headBranch")} into ${d.str("baseBranch")}",
                    style = AllCode.type.body,
                    color = AllCode.colors.inkSoft,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("+${d.int("additions") ?: 0} −${d.int("deletions") ?: 0}")
                    Pill("${d.int("changedFiles") ?: 0} files")
                    Pill((d.str("state") ?: "").replaceFirstChar { it.uppercase() }, tone = AllCode.colors.sage, container = AllCode.colors.sageSoft)
                    if (d.str("mergeability") == "conflicting") Pill("Conflicts", tone = AllCode.colors.rust, container = AllCode.colors.rustSoft)
                }
                val checks = d.objects("checks")
                if (checks.isNotEmpty()) {
                    SectionLabel("Checks", Modifier.padding(start = 0.dp))
                    checks.forEach { check ->
                        val conclusion = check.str("conclusion") ?: check.str("status") ?: check.str("state")
                        val (icon, tint) = when (conclusion?.lowercase()) {
                            "success", "passing", "passed", "neutral", "skipped" -> Icons.Rounded.CheckCircle to AllCode.colors.sage
                            "failure", "failing", "failed", "error", "cancelled", "timed_out" -> Icons.Rounded.Cancel to AllCode.colors.rust
                            else -> Icons.Rounded.HourglassTop to AllCode.colors.amber
                        }
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(16.dp), tint = tint)
                            Spacer(Modifier.width(8.dp))
                            Text(check.str("name") ?: "Check", style = AllCode.type.body, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                SectionLabel("Description", Modifier.padding(start = 0.dp))
                val body = d.str("body").orEmpty()
                if (body.isBlank()) Text("No description.", style = AllCode.type.body, color = AllCode.colors.inkFaint) else ProseMarkdown(body, compact = true)
            }
            error?.let { Text(it, style = AllCode.type.body, color = AllCode.colors.rust, modifier = Modifier.padding(vertical = 12.dp)) }
            if (detail == null && error == null) CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp), color = AllCode.colors.clay, strokeWidth = 2.dp)
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (starting) "Starting…" else "Review with an agent", Modifier.weight(1f), enabled = !starting, icon = Icons.Rounded.AutoAwesome) {
                startTask("Review pull request #$number in $repository. Check out the branch ${detail?.str("headBranch") ?: ""}, read the diff against ${detail?.str("baseBranch") ?: "the base"}, and summarize risks, bugs, and suggested changes.")
            }
            QuietButton("Fix checks") {
                startTask("The checks on pull request #$number in $repository are failing. Check out ${detail?.str("headBranch") ?: "its branch"}, find out why, and fix them.")
            }
        }
    }
}
