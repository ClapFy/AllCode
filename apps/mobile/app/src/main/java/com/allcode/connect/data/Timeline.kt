package com.allcode.connect.data

import com.allcode.connect.core.arr
import com.allcode.connect.core.bool
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.core.strings
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/** A rendered step inside a work group. */
data class WorkStep(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String?,
    val status: String,
    val failed: Boolean,
    val item: JsonObject,
)

data class FileTouch(val path: String, val additions: Int?, val deletions: Int?, val operation: String?)

data class Question(val id: String, val header: String?, val question: String, val options: List<Pair<String, String?>>, val multiSelect: Boolean)

data class TodoStep(val text: String, val status: String)

/** What the transcript shows. Tool calls between messages fold into one [Work] group. */
sealed interface TimelineEntry {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<JsonObject>, val at: Instant?, val queued: Boolean) : TimelineEntry
    data class Assistant(override val key: String, val text: String, val streaming: Boolean) : TimelineEntry
    data class Work(
        override val key: String,
        val steps: List<WorkStep>,
        val files: List<FileTouch>,
        val startedAt: Instant?,
        val endedAt: Instant?,
        val running: Boolean,
        val latestThought: String?,
    ) : TimelineEntry
    data class Approval(
        override val key: String,
        val requestId: String,
        val requestKind: String,
        val prompt: String,
        val reason: String?,
        val pending: Boolean,
        val decision: String?,
    ) : TimelineEntry
    data class Questions(
        override val key: String,
        val requestId: String,
        val questions: List<Question>,
        val pending: Boolean,
        val answers: Map<String, String>,
    ) : TimelineEntry
    data class Plan(override val key: String, val markdown: String, val streaming: Boolean, val planId: String?) : TimelineEntry
    data class Todo(override val key: String, val steps: List<TodoStep>, val explanation: String?) : TimelineEntry
    data class Notice(override val key: String, val text: String, val tone: Tone) : TimelineEntry
    data class Failure(override val key: String, val title: String, val message: String, val resetAt: Instant?) : TimelineEntry
    data class Subagent(override val key: String, val subagentId: String?, val title: String, val prompt: String?, val result: String?, val status: String, val childThreadId: String?, val provider: String?) : TimelineEntry
    data class Secret(override val key: String, val label: String, val reason: String?, val status: String) : TimelineEntry

    enum class Tone { Neutral, Divider }
}

private val WORK_TYPES = setOf(
    "reasoning", "command_execution", "dynamic_tool", "file_change", "file_search", "web_search", "checkpoint",
    "run_interrupt_request", "run_interrupt_result", "provider_thread", "thread_created", "handoff", "fork",
)

fun ThreadProjection.timeline(): List<TimelineEntry> {
    val entries = ArrayList<TimelineEntry>()
    var work = ArrayList<VisibleRow>()
    val requestsById = runtimeRequests.associateBy { it.str("id") }
    val runningRunIds = runs.filter { it.str("status") in ACTIVE_RUN_STATUSES }.mapNotNull { it.str("id") }.toSet()

    // A group ends when the agent's next item starts; a user message after it is idle time.
    fun flushWork(nextStart: Instant? = null) {
        if (work.isEmpty()) return
        val group = workGroup(work, runningRunIds, nextStart)
        if (group.steps.isNotEmpty() || group.files.isNotEmpty() || group.running) entries += group
        work = ArrayList()
    }

    for (row in visible) {
        val item = row.item
        val id = row.sourceItemId
        val rowStart = parseInstant(item.str("startedAt"))
        when (val type = item.str("type")) {
            in WORK_TYPES -> work += row
            "user_message" -> {
                flushWork()
                val runStatus = item.str("runId")?.let { rid -> runs.lastOrNull { it.str("id") == rid }?.str("status") }
                entries += TimelineEntry.User(
                    key = id,
                    text = item.str("text") ?: "",
                    attachments = item.objects("attachments"),
                    at = parseInstant(item.str("startedAt") ?: item.str("updatedAt")),
                    queued = runStatus == "queued",
                )
            }
            "assistant_message" -> {
                flushWork(rowStart)
                entries += TimelineEntry.Assistant(id, item.str("text") ?: "", item.bool("streaming") ?: false)
            }
            "approval_request" -> {
                flushWork(rowStart)
                val request = requestsById[item.str("requestId")]
                entries += TimelineEntry.Approval(
                    key = id,
                    requestId = item.str("requestId") ?: "",
                    requestKind = item.str("requestKind") ?: request?.str("kind") ?: "permission",
                    prompt = item.str("prompt") ?: "",
                    reason = item.str("reason"),
                    pending = request?.str("status") == "pending" || (request == null && item.str("status") == "waiting"),
                    decision = request?.str("decision"),
                )
            }
            "user_input_request" -> {
                flushWork(rowStart)
                val request = requestsById[item.str("requestId")]
                val answers = request?.obj("answers")?.mapValues { (_, v) -> v.primitiveContent() ?: v.toString() }.orEmpty()
                entries += TimelineEntry.Questions(
                    key = id,
                    requestId = item.str("requestId") ?: "",
                    questions = item.objects("questions").map { q ->
                        Question(
                            id = q.str("id") ?: "",
                            header = q.str("header"),
                            question = q.str("question") ?: "",
                            options = q.objects("options").map { o -> (o.str("label") ?: "") to o.str("description") },
                            multiSelect = q.bool("multiSelect") ?: false,
                        )
                    },
                    pending = request?.str("status") == "pending" || (request == null && item.str("status") == "waiting"),
                    answers = answers,
                )
            }
            "proposed_plan" -> {
                flushWork(rowStart)
                entries += TimelineEntry.Plan(id, item.str("markdown") ?: "", item.bool("streaming") ?: false, item.str("planId"))
            }
            "todo_list" -> {
                flushWork(rowStart)
                val steps = item.objects("steps").map { TodoStep(it.str("text") ?: "", it.str("status") ?: "pending") }
                // Only the latest list for a plan is worth showing; earlier ones are history.
                val planId = item.str("planId")
                entries.removeAll { it is TimelineEntry.Todo && planId != null && it.key.startsWith("todo:$planId:") }
                entries += TimelineEntry.Todo("todo:$planId:$id", steps, item.str("explanation"))
            }
            "subagent" -> {
                flushWork(rowStart)
                entries += TimelineEntry.Subagent(
                    key = id,
                    subagentId = item.str("subagentId"),
                    title = item.str("title") ?: "Subagent",
                    prompt = item.str("prompt"),
                    result = item.str("result"),
                    status = item.str("status") ?: "running",
                    childThreadId = item.str("childThreadId"),
                    provider = item.str("providerInstanceId") ?: item.str("driver"),
                )
            }
            "compaction" -> {
                flushWork(rowStart)
                entries += TimelineEntry.Notice(id, item.str("title") ?: "Context compacted", TimelineEntry.Tone.Divider)
            }
            "notification", "system_notice" -> {
                flushWork(rowStart)
                val text = item.str("message") ?: item.str("summary") ?: item.str("title") ?: continue
                entries += TimelineEntry.Notice(id, text, TimelineEntry.Tone.Neutral)
            }
            "error" -> {
                flushWork(rowStart)
                val failure = item.obj("failure")
                entries += TimelineEntry.Failure(
                    key = id,
                    title = item.str("title") ?: "Something went wrong",
                    message = failure?.str("message") ?: "The agent stopped with an error.",
                    resetAt = parseInstant(failure?.str("resetAt")),
                )
            }
            "secret_request" -> {
                flushWork(rowStart)
                entries += TimelineEntry.Secret(id, item.str("label") ?: "Secret", item.str("reason"), item.str("secretStatus") ?: item.str("status") ?: "")
            }
            else -> if (type != null) work += row
        }
    }
    flushWork()
    return entries
}

private fun workGroup(rows: List<VisibleRow>, runningRunIds: Set<String>, nextStart: Instant?): TimelineEntry.Work {
    val steps = rows.mapNotNull { workStep(it.item) }
    val files = LinkedHashMap<String, FileTouch>()
    rows.forEach { row ->
        val item = row.item
        if (item.str("type") != "file_change") return@forEach
        val changes = item.objects("changes")
        if (changes.isEmpty()) {
            item.str("fileName")?.let { files[it] = FileTouch(it, item.int("additions"), item.int("deletions"), null) }
        } else changes.forEach { c ->
            val path = c.str("path") ?: return@forEach
            val previous = files[path]
            files[path] = FileTouch(
                path,
                sum(previous?.additions, c.int("additions") ?: item.int("additions").takeIf { changes.size == 1 }),
                sum(previous?.deletions, c.int("deletions") ?: item.int("deletions").takeIf { changes.size == 1 }),
                c.str("operation"),
            )
        }
    }
    val running = rows.any { row ->
        val status = row.item.str("status")
        (status == "running" || status == "pending" || status == "waiting" || row.item.bool("streaming") == true) &&
            row.item.str("runId") in runningRunIds
    } || rows.lastOrNull()?.item?.str("runId") in runningRunIds
    val thought = rows.lastOrNull { it.item.str("type") == "reasoning" }?.item?.str("text")
        ?.lineSequence()?.map { it.trim().trim('*') }?.firstOrNull { it.isNotBlank() }
    return TimelineEntry.Work(
        key = "work:" + rows.first().sourceItemId,
        steps = steps,
        files = files.values.toList(),
        startedAt = parseInstant(rows.first().item.str("startedAt")),
        endedAt = (rows.mapNotNull { parseInstant(it.item.str("completedAt") ?: it.item.str("updatedAt")) } + listOfNotNull(nextStart)).maxOrNull(),
        running = running,
        latestThought = thought,
    )
}

private fun sum(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

private fun workStep(item: JsonObject): WorkStep? {
    val id = item.str("id") ?: return null
    val status = item.str("status") ?: "completed"
    val failedStatus = status == "failed" || item.bool("outputIndicatesFailure") == true ||
        (item.int("exitCode")?.let { it != 0 } ?: false)
    fun step(kind: String, title: String, detail: String?) = WorkStep(id, kind, title, detail, status, failedStatus, item)
    return when (item.str("type")) {
        "reasoning" -> {
            val text = item.str("text")?.trim().orEmpty()
            if (text.isEmpty()) null else step("thought", text.lineSequence().first().trim('*', ' '), text)
        }
        "command_execution" -> {
            val input = item.str("input")?.let(::unwrapShell) ?: ""
            step("command", item.str("title") ?: input.lineSequence().firstOrNull().orEmpty(), input)
        }
        "dynamic_tool" -> step("tool", item.str("title") ?: prettyTool(item.str("toolName")), item["input"]?.toString())
        "file_change" -> {
            val path = item.str("fileName") ?: item.objects("changes").firstOrNull()?.str("path") ?: "file"
            step("edit", "Edited ${path.substringAfterLast('/')}", path)
        }
        "file_search" -> step("search", "Searched files for ${item.str("pattern") ?: "a pattern"}", null)
        "web_search" -> step("web", "Searched the web", item.strings("patterns").joinToString("\n"))
        "checkpoint" -> null
        "run_interrupt_request" -> step("stop", "Stop requested", item.str("message"))
        "run_interrupt_result" -> step("stop", item.str("message") ?: "Stopped", null)
        "handoff" -> step("handoff", "Switched model", item.str("summary"))
        else -> item.str("title")?.let { step("other", it, null) }
    }
}

private fun prettyTool(name: String?): String {
    if (name == null) return "Used a tool"
    val short = name.substringAfterLast('.').substringAfterLast("__").replace('_', ' ')
    return short.replaceFirstChar { it.uppercase() }
}

/** `/bin/bash -lc "cmd"` reads better as `cmd`. */
fun unwrapShell(input: String): String {
    val match = Regex("^(?:/\\S*/)?(?:ba|z)?sh\\s+-l?c\\s+(['\"])([\\s\\S]*)\\1\\s*$").find(input.trim()) ?: return input
    return match.groupValues[2].replace("\\\"", "\"")
}

fun TimelineEntry.Work.duration(now: Instant = Instant.now()): Duration? {
    val start = startedAt ?: return null
    val end = if (running) now else endedAt ?: return null
    return Duration.between(start, end).takeIf { !it.isNegative }
}
