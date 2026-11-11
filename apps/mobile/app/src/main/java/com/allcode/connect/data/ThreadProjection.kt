package com.allcode.connect.data

import com.allcode.connect.core.bool
import com.allcode.connect.core.long
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import kotlinx.serialization.json.JsonObject

/** One row of the transcript, in display order. Inherited rows come from a forked parent. */
data class VisibleRow(
    val sourceItemId: String,
    val visibility: String,
    val sourceThreadId: String,
    val item: JsonObject,
) {
    val ordinal: Long get() = item.long("ordinal") ?: 0
}

/**
 * The client copy of `OrchestrationV2ThreadProjection`, kept as JSON records so newer servers'
 * fields survive. Ported from packages/client-runtime/src/state/orchestrationV2Projection.ts.
 */
data class ThreadProjection(
    val thread: JsonObject,
    val runs: List<JsonObject>,
    val attempts: List<JsonObject>,
    val messages: List<JsonObject>,
    val runtimeRequests: List<JsonObject>,
    val plans: List<JsonObject>,
    val subagents: List<JsonObject>,
    val checkpoints: List<JsonObject>,
    val providerTurns: List<JsonObject>,
    val turnItems: List<JsonObject>,
    val visible: List<VisibleRow>,
    val sequence: Long,
    val hasMoreHistory: Boolean,
    val historyCursor: String?,
    val latestLocalTurnOrdinal: Long?,
) {
    val id: String get() = thread.str("id") ?: ""

    val activeRun: JsonObject?
        get() = runs.lastOrNull { it.str("status") in ACTIVE_RUN_STATUSES }

    val queuedRuns: List<JsonObject> get() = runs.filter { it.str("status") == "queued" }

    val pendingRequests: List<JsonObject>
        get() = runtimeRequests.filter { it.str("status") == "pending" }

    companion object {
        fun fromSnapshot(item: JsonObject): ThreadProjection? {
            val projection = item.obj("projection") ?: return null
            val thread = projection.obj("thread") ?: return null
            return ThreadProjection(
                thread = thread,
                runs = projection.objects("runs"),
                attempts = projection.objects("attempts"),
                messages = projection.objects("messages"),
                runtimeRequests = projection.objects("runtimeRequests"),
                plans = projection.objects("plans"),
                subagents = projection.objects("subagents"),
                checkpoints = projection.objects("checkpoints"),
                providerTurns = projection.objects("providerTurns"),
                turnItems = projection.objects("turnItems"),
                visible = projection.objects("visibleTurnItems").mapNotNull(::visibleRow),
                sequence = item.long("snapshotSequence") ?: 0,
                hasMoreHistory = item.bool("hasMoreHistory") ?: false,
                historyCursor = item.str("historyCursor"),
                latestLocalTurnOrdinal = item.long("latestLocalTurnOrdinal"),
            )
        }

        fun visibleRow(json: JsonObject): VisibleRow? {
            val item = json.obj("item") ?: return null
            return VisibleRow(
                sourceItemId = json.str("sourceItemId") ?: item.str("id") ?: return null,
                visibility = json.str("visibility") ?: "local",
                sourceThreadId = json.str("sourceThreadId") ?: item.str("threadId") ?: "",
                item = item,
            )
        }
    }
}

private fun List<JsonObject>.upsert(record: JsonObject): List<JsonObject> {
    val id = record.str("id")
    val index = indexOfLast { it.str("id") == id }
    if (index == -1) return this + record
    return toMutableList().also { it[index] = record }
}

/** Applies one `orchestration.subscribeThread` event. Events at or below the snapshot are skipped. */
fun ThreadProjection.apply(item: JsonObject): ThreadProjection {
    val sequence = item.long("sequence") ?: return this
    if (sequence <= this.sequence) return this
    val event = item.obj("event") ?: return copy(sequence = sequence)
    val payload = event.obj("payload")
    val next = copy(sequence = sequence)
    if (payload == null) return next
    return when (event.str("type")) {
        "thread.created", "thread.archived", "thread.unarchived", "thread.deleted", "thread.settled",
        "thread.unsettled", "thread.snoozed", "thread.unsnoozed", "thread.auto-settle-set", "thread.pinned",
        "thread.unpinned", "thread.pin-reordered", "thread.active-reordered", "thread.metadata-updated",
        "thread.pull-request-synced", "thread.runtime-mode-updated", "thread.interaction-mode-updated",
        "thread.model-selection-updated", "thread.provider-switched", "thread.guest-share-updated",
        "thread.visited", "thread.tags-updated", "thread.marked-unread" -> next.copy(thread = payload)
        "run.created", "run.updated" -> next.copy(runs = runs.upsert(payload)).refilterVisible()
        "run-attempt.created", "run-attempt.updated" -> next.copy(attempts = attempts.upsert(payload)).refilterVisible()
        "subagent.updated" -> next.copy(subagents = subagents.upsert(payload))
        "provider-turn.updated" -> {
            val previous = providerTurns.lastOrNull { it.str("id") == payload.str("id") }
            val merged = if (payload["tokenUsage"] == null && previous?.get("tokenUsage") != null) {
                JsonObject(payload + ("tokenUsage" to previous["tokenUsage"]!!))
            } else payload
            next.copy(providerTurns = providerTurns.upsert(merged))
        }
        "runtime-request.updated" -> next.copy(runtimeRequests = runtimeRequests.upsert(payload))
        "message.updated" -> next.copy(messages = messages.upsert(payload))
        "plan.updated" -> next.copy(plans = plans.upsert(payload))
        "checkpoint.captured" -> next.copy(checkpoints = checkpoints.upsert(payload))
        "turn-item.updated" -> next.applyTurnItem(payload)
        else -> next
    }
}

private fun ThreadProjection.applyTurnItem(payload: JsonObject): ThreadProjection {
    val id = payload.str("id") ?: return this
    val index = turnItems.indexOfLast { it.str("id") == id }
    if (hasMoreHistory && index == -1 && shouldDropOutsideWindow(payload)) return this
    val items = if (index == -1) turnItems + payload else turnItems.toMutableList().also { it[index] = payload }
    val updated = copy(turnItems = items)
    val previousType = if (index == -1) null else turnItems[index].str("type")
    val base = if (payload.str("type") == "run_interrupt_request" || previousType == "run_interrupt_request") {
        updated.refilterVisible()
    } else updated
    return if (base.isVisible(payload)) base.upsertVisible(payload) else base.copy(visible = base.visible.filterNot { it.sourceItemId == id })
}

private val UNFINISHED_RUN_STATUSES = setOf("preparing", "queued", "starting", "running", "waiting")

/** A bounded window only grows at its live edge; older items arrive through history pages. */
private fun ThreadProjection.shouldDropOutsideWindow(item: JsonObject): Boolean {
    if (visible.any { it.sourceItemId == item.str("id") }) return false
    val runId = item.str("runId")
    if (runId != null && runs.any { it.str("id") == runId && it.str("status") in UNFINISHED_RUN_STATUSES }) return false
    val ordinal = item.long("ordinal") ?: return false
    if (runId != null && visible.any { it.visibility == "local" && it.item.str("runId") == runId && it.ordinal <= ordinal }) {
        return false
    }
    latestLocalTurnOrdinal?.let { if (ordinal <= it) return true }
    val oldest = visible.filter { it.visibility == "local" }.minOfOrNull { it.ordinal } ?: return false
    return ordinal < oldest
}

/** Rolled-back runs, cancelled queued input, and superseded interrupt results stay hidden. */
fun ThreadProjection.isVisible(item: JsonObject): Boolean {
    val runId = item.str("runId")
    val status = runId?.let { id -> runs.lastOrNull { it.str("id") == id }?.str("status") }
    if (status == "rolled_back") return false
    if (status == "cancelled" && item.str("type") == "user_message" && item.str("inputIntent") == "queued_turn") return false
    if (item.str("type") == "run_interrupt_result" && runId != null) {
        val superseded = attempts.any { it.str("runId") == runId && it.str("status") == "superseded" && it.str("rootNodeId") == item.str("nodeId") }
        if (superseded) return false
    }
    return true
}

private fun ThreadProjection.refilterVisible(): ThreadProjection {
    val kept = visible.filter { it.visibility != "local" || isVisible(it.item) }
    return if (kept.size == visible.size) this else copy(visible = kept)
}

private fun ThreadProjection.upsertVisible(item: JsonObject): ThreadProjection {
    val id = item.str("id") ?: return this
    val row = VisibleRow(id, "local", item.str("threadId") ?: "", item)
    val index = visible.indexOfLast { it.sourceItemId == id }
    if (index != -1 && visible[index].visibility == "local" && visible[index].ordinal == row.ordinal) {
        return copy(visible = visible.toMutableList().also { it[index] = row })
    }
    val without = if (index == -1) visible else visible.filterIndexed { i, _ -> i != index }
    val insertion = without.indexOfFirst {
        it.visibility == "local" && (it.ordinal > row.ordinal || (it.ordinal == row.ordinal && it.sourceItemId > id))
    }
    val rows = without.toMutableList()
    if (insertion == -1) rows.add(row) else rows.add(insertion, row)
    return copy(visible = rows)
}

/** Prepends an older history page to a bounded window. */
fun ThreadProjection.prependHistory(page: JsonObject): ThreadProjection {
    val rows = page.objects("items").mapNotNull(ThreadProjection::visibleRow)
    val known = visible.mapTo(HashSet()) { it.sourceItemId }
    val older = rows.filterNot { it.sourceItemId in known }
    val knownItems = turnItems.mapTo(HashSet()) { it.str("id") }
    return copy(
        visible = older + visible,
        turnItems = older.map { it.item }.filterNot { it.str("id") in knownItems } + turnItems,
        hasMoreHistory = page.bool("hasMoreHistory") ?: false,
        historyCursor = page.str("nextCursor"),
    )
}
