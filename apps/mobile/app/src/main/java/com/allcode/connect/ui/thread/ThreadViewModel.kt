package com.allcode.connect.ui.thread

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.str
import com.allcode.connect.data.Commands
import com.allcode.connect.data.DeliveryMode
import com.allcode.connect.data.ModelSelection
import com.allcode.connect.data.ThreadFeed
import com.allcode.connect.data.ThreadProjection
import com.allcode.connect.data.TimelineEntry
import com.allcode.connect.data.VisibleRow
import com.allcode.connect.data.prependHistory
import com.allcode.connect.data.timeline
import com.allcode.connect.data.describeUri
import com.allcode.connect.data.uploadAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PendingAttachment(
    val localId: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploaded: JsonObject? = null,
    val failed: String? = null,
) {
    val isImage get() = mimeType.startsWith("image/")
}

class ThreadViewModel(
    private val graph: AppGraph,
    val environmentId: String,
    val threadId: String,
) : ViewModel() {
    val connection = graph.connections.connection(environmentId)
    private val live = MutableStateFlow<ThreadProjection?>(null)
    private val older = MutableStateFlow<List<VisibleRow>>(emptyList())
    private val olderCursor = MutableStateFlow<Pair<Boolean, String?>?>(null)
    val failure = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val loadingOlder = MutableStateFlow(false)
    val draft = MutableStateFlow("")
    val attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val sending = MutableStateFlow(false)

    /** The live window plus any older pages the user scrolled back into. */
    val projection: StateFlow<ThreadProjection?> = combine(live, older, olderCursor) { current, history, cursor ->
        current ?: return@combine null
        if (history.isEmpty()) return@combine current
        val known = current.visible.mapTo(HashSet()) { it.sourceItemId }
        current.copy(
            visible = history.filterNot { it.sourceItemId in known } + current.visible,
            hasMoreHistory = cursor?.first ?: current.hasMoreHistory,
            historyCursor = cursor?.second ?: current.historyCursor,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val timeline: StateFlow<List<TimelineEntry>> = projection
        .map { it?.timeline() ?: emptyList() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        graph.openThread.value = environmentId to threadId
        val connection = connection
        if (connection == null) {
            failure.value = "This machine isn't paired anymore."
        } else {
            viewModelScope.launch {
                connection.thread(threadId).collect { feed ->
                    when (feed) {
                        ThreadFeed.Loading -> Unit
                        is ThreadFeed.Ready -> { live.value = feed.projection; failure.value = null }
                        is ThreadFeed.Failed -> failure.value = feed.message
                    }
                }
            }
            markVisited()
        }
    }

    internal fun showForPreview(projection: ThreadProjection) {
        live.value = projection
    }

    fun markVisited() {
        viewModelScope.launch { runCatching { connection?.dispatch(Commands.visit(threadId)) } }
    }

    override fun onCleared() {
        if (graph.openThread.value == environmentId to threadId) graph.openThread.value = null
    }

    fun loadOlder() {
        val current = projection.value ?: return
        val cursor = current.historyCursor ?: return
        if (!current.hasMoreHistory || loadingOlder.value) return
        loadingOlder.value = true
        viewModelScope.launch {
            try {
                val page = connection!!.http.getJson(
                    "/api/orchestration/threads/${java.net.URLEncoder.encode(threadId, Charsets.UTF_8)}/history",
                    mapOf("cursor" to cursor),
                ).asObject() ?: return@launch
                val merged = current.copy(visible = emptyList(), turnItems = emptyList()).prependHistory(page)
                older.value = merged.visible + older.value
                olderCursor.value = merged.hasMoreHistory to merged.historyCursor
            } catch (e: Exception) {
                notice.value = e.message ?: "Couldn't load earlier messages."
            } finally {
                loadingOlder.value = false
            }
        }
    }

    private fun run(command: JsonObject, onDone: () -> Unit = {}) {
        val connection = connection ?: return
        viewModelScope.launch {
            try {
                connection.dispatch(command)
                onDone()
            } catch (e: Exception) {
                notice.value = e.message ?: "That didn't go through."
            }
        }
    }

    fun send(mode: DeliveryMode = DeliveryMode.Auto) {
        val text = draft.value.trim()
        val files = attachments.value
        if ((text.isEmpty() && files.isEmpty()) || sending.value) return
        if (files.any { it.uploaded == null }) {
            notice.value = if (files.any { it.failed != null }) "Remove the attachment that failed to upload." else "Attachments are still uploading."
            return
        }
        sending.value = true
        val previousDraft = draft.value
        draft.value = ""
        attachments.value = emptyList()
        viewModelScope.launch {
            try {
                connection!!.dispatch(Commands.sendMessage(threadId, text, files.mapNotNull { it.uploaded }, mode = mode))
            } catch (e: Exception) {
                draft.value = previousDraft
                attachments.value = files
                notice.value = e.message ?: "Your message didn't send."
            } finally {
                sending.value = false
            }
        }
    }

    fun respond(requestId: String, decision: String) = run(Commands.respondApproval(threadId, requestId, decision))

    fun answer(requestId: String, answers: Map<String, String>) =
        run(Commands.answerQuestions(threadId, requestId, answers.mapValues { (_, v) -> JsonPrimitive(v) as JsonElement }))

    fun dismissQuestions(requestId: String) = run(Commands.dismissQuestions(threadId, requestId))

    fun stop() {
        val runId = projection.value?.activeRun?.str("id")
        run(if (runId != null) Commands.interrupt(threadId, runId) else Commands.stop(threadId))
    }

    /** App-owned tasks also stop their child thread, which may have lost track of the parent. */
    fun stopSubagent(subagentId: String) {
        val connection = connection ?: return
        val subagent = projection.value?.subagents?.lastOrNull { it.str("id") == subagentId } ?: return
        viewModelScope.launch {
            try {
                subagent.str("runId")?.let { connection.dispatch(Commands.stopSubagent(threadId, it, subagentId)) }
                val child = subagent.str("childThreadId")
                if (subagent.str("origin") == "app_owned" && child != null) connection.dispatch(Commands.stop(child))
            } catch (e: Exception) {
                notice.value = e.message ?: "The subagent didn't stop."
            }
        }
    }

    fun cancelQueued(runId: String) = run(Commands.cancelQueued(threadId, runId))

    fun steerQueued(runId: String) {
        val active = projection.value?.activeRun?.str("id") ?: return
        run(Commands.steerQueued(threadId, runId, active))
    }

    fun setModel(selection: ModelSelection) = run(Commands.setModel(threadId, selection))
    fun setRuntimeMode(mode: String) = run(Commands.setRuntimeMode(threadId, mode))
    fun setInteractionMode(mode: String) = run(Commands.setInteractionMode(threadId, mode))
    fun rename(title: String) = run(Commands.rename(threadId, title))
    fun archive(onDone: () -> Unit) = run(Commands.archive(threadId), onDone)
    fun togglePin(pinned: Boolean) = run(if (pinned) Commands.unpin(threadId) else Commands.pin(threadId))

    fun attach(context: Context, uris: List<Uri>) {
        val connection = connection ?: return
        uris.take(8).forEach { uri ->
            val (name, size) = describeUri(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val localId = java.util.UUID.randomUUID().toString()
            attachments.value = attachments.value + PendingAttachment(localId, uri, name, mime, size)
            viewModelScope.launch {
                try {
                    val record = connection.uploadAttachment(context, uri, name, mime)
                    attachments.value = attachments.value.map { if (it.localId == localId) it.copy(uploaded = record) else it }
                } catch (e: Exception) {
                    attachments.value = attachments.value.map { if (it.localId == localId) it.copy(failed = e.message ?: "Upload failed") else it }
                }
            }
        }
    }

    fun removeAttachment(localId: String) {
        attachments.value = attachments.value.filterNot { it.localId == localId }
    }

    /** A short-lived URL for an attachment image, resolved against this machine. */
    suspend fun attachmentUrl(attachmentId: String): String? = runCatching {
        val result = connection!!.call("assets.createUrl", buildJsonObject {
            put("resource", buildJsonObject {
                put("_tag", "attachment")
                put("attachmentId", attachmentId)
                put("disposition", "inline")
            })
        }).asObject()
        result?.str("relativeUrl")?.let { connection.http.url(it) }
    }.getOrNull()
}
