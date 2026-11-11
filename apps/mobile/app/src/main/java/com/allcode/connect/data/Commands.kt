package com.allcode.connect.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** How a new message reaches a thread that may already be running. */
enum class DeliveryMode { Auto, Queue, Steer }

/** Where a new task's work happens. */
sealed interface WorkspaceStrategy {
    data class Root(val branch: String? = null) : WorkspaceStrategy
    data class NewWorktree(val baseRef: String, val branch: String? = null) : WorkspaceStrategy
    data class ExistingWorktree(val worktreePath: String, val branch: String? = null) : WorkspaceStrategy

    fun toJson(): JsonObject = buildJsonObject {
        when (this@WorkspaceStrategy) {
            is Root -> { put("type", "root"); branch?.let { put("branch", it) } }
            is NewWorktree -> { put("type", "worktree"); put("baseRef", baseRef); branch?.let { put("branch", it) } }
            is ExistingWorktree -> { put("type", "existing_worktree"); put("worktreePath", worktreePath); branch?.let { put("branch", it) } }
        }
    }
}

/** Builders for `orchestration.dispatchCommand` payloads. Every command gets a fresh id. */
object Commands {
    fun newId(): String = UUID.randomUUID().toString()

    private fun command(type: String, threadId: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        put("commandId", newId())
        put("threadId", threadId)
        extra()
    }

    fun sendMessage(
        threadId: String,
        text: String,
        attachments: List<JsonObject> = emptyList(),
        modelSelection: ModelSelection? = null,
        mode: DeliveryMode = DeliveryMode.Auto,
    ): JsonObject {
        val commandId = newId()
        return buildJsonObject {
            put("type", "message.dispatch")
            put("commandId", commandId)
            put("createdBy", "user")
            put("creationSource", "mobile")
            put("threadId", threadId)
            put("messageId", commandId)
            put("text", text)
            put("attachments", JsonArray(attachments))
            modelSelection?.let { put("modelSelection", it.toJson()) }
            when (mode) {
                DeliveryMode.Queue -> put("dispatchMode", buildJsonObject { put("type", "queue_after_active") })
                DeliveryMode.Steer -> {
                    put("deliveryIntent", "steer")
                    put("dispatchMode", buildJsonObject { put("type", "start_immediately") })
                }
                DeliveryMode.Auto -> {
                    put("deliveryIntent", "auto")
                    put("dispatchMode", buildJsonObject { put("type", "start_immediately") })
                }
            }
        }
    }

    fun launchThread(
        threadId: String,
        projectId: String,
        text: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        interactionMode: String,
        workspace: WorkspaceStrategy,
        attachments: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        val commandId = newId()
        put("commandId", commandId)
        put("creationSource", "mobile")
        put("threadId", threadId)
        put("projectId", projectId)
        put("title", text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)?.trim()?.ifEmpty { null } ?: "New thread")
        put("generateTitle", true)
        put("modelSelection", modelSelection.toJson())
        put("runtimeMode", runtimeMode)
        put("interactionMode", interactionMode)
        put("workspaceStrategy", workspace.toJson())
        put("initialMessage", buildJsonObject {
            put("messageId", commandId)
            put("text", text)
            put("attachments", JsonArray(attachments))
        })
    }

    fun respondApproval(threadId: String, requestId: String, decision: String, feedback: String? = null) =
        command("runtime-request.respond", threadId) {
            put("requestId", requestId)
            put("decision", decision)
            feedback?.takeIf { it.isNotBlank() }?.let { put("feedback", it) }
        }

    fun answerQuestions(threadId: String, requestId: String, answers: Map<String, JsonElement>) =
        command("runtime-request.respond", threadId) {
            put("requestId", requestId)
            put("answers", JsonObject(answers))
        }

    fun dismissQuestions(threadId: String, requestId: String) =
        command("thread.user-input.dismiss", threadId) { put("requestId", requestId) }

    fun interrupt(threadId: String, runId: String) =
        command("run.interrupt", threadId) { put("runId", runId) }

    fun stop(threadId: String) = command("thread.stop", threadId)

    fun stopSubagent(threadId: String, runId: String, subagentId: String) =
        command("subagent.stop", threadId) {
            put("runId", runId)
            put("subagentId", subagentId)
        }

    fun cancelQueued(threadId: String, runId: String) =
        command("queued-run.cancel", threadId) { put("runId", runId) }

    fun steerQueued(threadId: String, queuedRunId: String, targetRunId: String) =
        command("queued-message.promote-to-steer", threadId) {
            put("queuedRunId", queuedRunId)
            put("targetRunId", targetRunId)
        }

    fun setRuntimeMode(threadId: String, mode: String) =
        command("thread.runtime-mode.set", threadId) { put("runtimeMode", mode) }

    fun setInteractionMode(threadId: String, mode: String) =
        command("thread.interaction-mode.set", threadId) { put("interactionMode", mode) }

    fun setModel(threadId: String, selection: ModelSelection) =
        command("thread.model-selection.set", threadId) { put("modelSelection", selection.toJson()) }

    fun visit(threadId: String) = command("thread.visit", threadId) {
        put("visitedAt", Instant.now().toString())
        put("opened", true)
    }

    fun rename(threadId: String, title: String) = command("thread.metadata.update", threadId) { put("title", title) }

    fun archive(threadId: String) = command("thread.archive", threadId)
    fun unarchive(threadId: String) = command("thread.unarchive", threadId)
    fun pin(threadId: String) = command("thread.pin", threadId)
    fun unpin(threadId: String) = command("thread.unpin", threadId)
    fun settle(threadId: String) = command("thread.settle", threadId)
    fun unsettle(threadId: String) = command("thread.unsettle", threadId)
    fun snooze(threadId: String, until: Instant) = command("thread.snooze", threadId) { put("snoozedUntil", until.toString()) }
    fun unsnooze(threadId: String) = command("thread.unsnooze", threadId)
    fun delete(threadId: String) = command("thread.delete", threadId)

    fun answer(value: String): JsonElement = JsonPrimitive(value)
}
