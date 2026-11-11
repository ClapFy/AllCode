package com.allcode.connect.notify

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.allcode.connect.AppGraph
import com.allcode.connect.R
import com.allcode.connect.data.EnvThread
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.core.asObject
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.widget.UsageWidget
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches every machine's thread list and turns phase changes into notifications:
 * a thread that starts waiting on the user, and one that finishes or fails.
 */
class ActivityWatcher(private val context: Context, private val graph: AppGraph) {
    private val phases = HashMap<String, ThreadPhase>()
    private var primed = false
    /** Requests already announced, so a request seen on screen is still announced once you leave. */
    private val announced = HashSet<String>()
    @Volatile private var latest: List<EnvThread> = emptyList()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        graph.scope.launch {
            graph.connections.threads.collect { threads -> onThreads(threads) }
        }
        graph.scope.launch {
            graph.foreground.collect { foreground -> if (!foreground) announceWaiting() }
        }
        // Android only lets a foreground service start while the app is visible, so it starts as
        // soon as work goes live and then outlives the app until everything is idle.
        graph.scope.launch {
            combine(graph.connections.hasLiveWork, graph.foreground, graph.prefs.state.map { it.stayConnected }) { live, fg, stay -> live && fg && stay }
                .distinctUntilChanged()
                .collectLatest { shouldRun -> if (shouldRun) startService() }
        }
        graph.scope.launch {
            graph.connections.snapshots.map { snaps -> snaps.map { it.config?.providers } }.distinctUntilChanged().debounce(2_000).collect {
                UsageWidget.refresh(context)
            }
        }
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveConnectionService::class.java)) }
    }

    private fun requestKey(item: EnvThread) =
        "${item.environmentId}/${item.thread.id}/${item.thread.pendingRequestId ?: "plan"}"

    @Synchronized
    private fun announceWaiting() {
        if (!graph.prefs.state.value.notifyAttention) return
        val recent = java.time.Instant.now().minus(java.time.Duration.ofHours(6))
        latest.filter { it.thread.phase == ThreadPhase.NeedsYou && !it.thread.isSubagent && !it.thread.archived }
            .filter { it.thread.updatedAt?.isAfter(recent) == true }
            .filterNot { requestKey(it) in announced }
            .forEach(::notifyAttention)
    }

    @Synchronized
    private fun onThreads(threads: List<EnvThread>) {
        latest = threads
        val prefs = graph.prefs.state.value
        threads.filterNot { it.thread.isSubagent || it.thread.archived }.forEach { item ->
            val key = "${item.environmentId}/${item.thread.id}"
            val now = item.thread.phase
            val before = phases.put(key, now)
            if (!primed || before == null || before == now) return@forEach
            val open = graph.openThread.value == (item.environmentId to item.thread.id) && graph.foreground.value
            if (open) return@forEach
            when {
                now == ThreadPhase.NeedsYou && prefs.notifyAttention -> notifyAttention(item)
                (before == ThreadPhase.Working || before == ThreadPhase.NeedsYou) && now == ThreadPhase.Idle && prefs.notifyFinished -> notifyFinished(item, failed = false)
                now == ThreadPhase.Failed && prefs.notifyFinished -> notifyFinished(item, failed = true)
            }
            if (before == ThreadPhase.NeedsYou && now != ThreadPhase.NeedsYou) {
                Notifications.cancel(context, Notifications.notificationId(item.environmentId, item.thread.id) + 1)
            }
        }
        primed = true
    }

    private fun notifyAttention(item: EnvThread) {
        val thread = item.thread
        announced += requestKey(item)
        val question = thread.pendingRequestKind == "user_input"
        val builder = NotificationCompat.Builder(context, Notifications.ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFD97757.toInt())
            .setContentTitle(thread.title)
            .setContentText(
                when {
                    question -> "Has a question for you"
                    thread.pendingRequestKind == null -> "A plan is ready for your review"
                    else -> "Wants your approval to ${requestVerb(thread.pendingRequestKind)}"
                },
            )
            .setSubText(listOfNotNull(item.project?.title, item.environmentLabel).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(Notifications.openThread(context, item.environmentId, thread.id))
            .setAutoCancel(true)
        thread.pendingRequestId?.takeIf { !question }?.let { id ->
            builder.addAction(Notifications.approvalAction(context, item.environmentId, thread.id, id, "accept", "Allow"))
            builder.addAction(Notifications.approvalAction(context, item.environmentId, thread.id, id, "decline", "Deny"))
        }
        Notifications.post(context, Notifications.notificationId(item.environmentId, thread.id) + 1, builder)
    }

    private fun requestVerb(kind: String?) = when (kind) {
        "command" -> "run a command"
        "file-change", "file_change" -> "edit files"
        "file-read", "file_read" -> "read files"
        else -> "continue"
    }

    private fun notifyFinished(item: EnvThread, failed: Boolean) {
        val thread = item.thread
        graph.scope.launch {
            // Thread summaries often omit the last message, so read the reply itself.
            val reply = if (failed) null else thread.latestMessageText ?: lastReply(item)
            val text = if (failed) thread.lastError ?: "The agent hit an error." else reply ?: "Finished"
            val builder = NotificationCompat.Builder(context, Notifications.FINISHED)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFD97757.toInt())
                .setContentTitle(if (failed) "Stopped: ${thread.title}" else thread.title)
                .setContentText(text.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setSubText(listOfNotNull(item.project?.title, item.environmentLabel).joinToString(" · "))
                .setContentIntent(Notifications.openThread(context, item.environmentId, thread.id))
                .setAutoCancel(true)
            Notifications.post(context, Notifications.notificationId(item.environmentId, thread.id), builder)
        }
    }

    private suspend fun lastReply(item: EnvThread): String? = runCatching {
        val connection = graph.connections.connection(item.environmentId) ?: return null
        val snapshot = connection.http.getJson(
            "/api/orchestration/threads/${java.net.URLEncoder.encode(item.thread.id, Charsets.UTF_8)}/bounded",
        ).asObject() ?: return null
        snapshot.obj("projection")?.objects("visibleTurnItems")?.mapNotNull { it.obj("item") }
            ?.lastOrNull { it.str("type") == "assistant_message" }?.str("text")
            ?.replace(Regex("[`*_#>]"), "")?.trim()?.ifEmpty { null }
    }.getOrNull()
}
