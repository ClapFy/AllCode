package com.allcode.connect.data

import com.allcode.connect.core.arr
import com.allcode.connect.core.bool
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

data class ModelOptionValue(val id: String, val value: String)

data class ModelSelection(
    val instanceId: String,
    val model: String,
    val options: List<ModelOptionValue> = emptyList(),
) {
    fun option(id: String): String? = options.firstOrNull { it.id == id }?.value

    fun withOption(id: String, value: String): ModelSelection =
        copy(options = options.filterNot { it.id == id } + ModelOptionValue(id, value))

    fun toJson(): JsonObject = buildJsonObject {
        put("instanceId", instanceId)
        put("model", model)
        if (options.isNotEmpty()) {
            put("options", JsonArray(options.map { buildJsonObject { put("id", it.id); put("value", it.value) } }))
        }
    }

    companion object {
        fun from(json: JsonObject?): ModelSelection? {
            json ?: return null
            val instanceId = json.str("instanceId") ?: return null
            val model = json.str("model") ?: return null
            val options = json.objects("options").mapNotNull { o ->
                val id = o.str("id") ?: return@mapNotNull null
                val value = o.str("value") ?: return@mapNotNull null
                ModelOptionValue(id, value)
            }
            return ModelSelection(instanceId, model, options)
        }
    }
}

data class Project(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val repositoryName: String?,
    val defaultModelSelection: ModelSelection?,
    val pinned: Boolean,
    val archived: Boolean,
    val updatedAt: Instant?,
    val raw: JsonObject,
) {
    companion object {
        fun from(json: JsonObject): Project? {
            val id = json.str("id") ?: return null
            val identity = json.obj("repositoryIdentity")
            return Project(
                id = id,
                title = json.str("title") ?: json.str("workspaceRoot")?.substringAfterLast('/') ?: "Project",
                workspaceRoot = json.str("workspaceRoot") ?: "",
                repositoryName = identity?.str("displayName") ?: identity?.str("name"),
                defaultModelSelection = ModelSelection.from(json.obj("defaultModelSelection")),
                pinned = json.str("pinnedAt") != null,
                archived = json.str("archivedAt") != null,
                updatedAt = parseInstant(json.str("updatedAt")),
                raw = json,
            )
        }
    }
}

enum class ThreadPhase { NeedsYou, Working, Queued, Failed, Idle }

val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")

data class ThreadSummary(
    val id: String,
    val projectId: String,
    val title: String,
    val providerInstanceId: String,
    val modelSelection: ModelSelection?,
    val runtimeMode: String,
    val interactionMode: String,
    val branch: String?,
    val worktreePath: String?,
    val status: String,
    val pendingRequestKind: String?,
    val pendingRequestId: String?,
    val pendingRequestBlocking: Boolean,
    val latestMessageText: String?,
    val latestMessageRole: String?,
    val lastError: String?,
    val parentThreadId: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val snoozedUntil: Instant?,
    val settled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val latestRunCompletedAt: Instant?,
    val lastVisitedAt: Instant?,
    val pullRequestCount: Int,
    val hasActionablePlan: Boolean,
    val visibleItemCount: Int,
    val usageLimitResetAt: Instant?,
) {
    val phase: ThreadPhase
        get() = when {
            pendingRequestKind != null && pendingRequestBlocking -> ThreadPhase.NeedsYou
            hasActionablePlan && status !in ACTIVE_RUN_STATUSES -> ThreadPhase.NeedsYou
            status == "queued" -> ThreadPhase.Queued
            status in ACTIVE_RUN_STATUSES -> ThreadPhase.Working
            status == "failed" -> ThreadPhase.Failed
            else -> ThreadPhase.Idle
        }

    /** Finished since this thread was last opened anywhere. */
    val unread: Boolean
        get() {
            val completed = latestRunCompletedAt ?: return false
            val visited = lastVisitedAt ?: return true
            return completed.isAfter(visited)
        }

    val isSubagent: Boolean get() = parentThreadId != null

    companion object {
        fun from(json: JsonObject): ThreadSummary? {
            val id = json.str("id") ?: return null
            val pending = json.obj("pendingRuntimeRequest")
            val latest = json.obj("latestVisibleMessage")
            return ThreadSummary(
                id = id,
                projectId = json.str("projectId") ?: "",
                title = json.str("title")?.ifBlank { null } ?: "New thread",
                providerInstanceId = json.str("providerInstanceId") ?: "",
                modelSelection = ModelSelection.from(json.obj("modelSelection")),
                runtimeMode = json.str("runtimeMode") ?: "full-access",
                interactionMode = json.str("interactionMode") ?: "default",
                branch = json.str("branch"),
                worktreePath = json.str("worktreePath"),
                status = json.str("status") ?: "idle",
                pendingRequestKind = pending?.str("kind"),
                pendingRequestId = pending?.str("id"),
                pendingRequestBlocking = pending?.bool("blocking") ?: true,
                latestMessageText = latest?.str("text"),
                latestMessageRole = latest?.str("role"),
                lastError = json.str("lastError"),
                parentThreadId = json.obj("lineage")?.str("parentThreadId"),
                pinned = json.str("pinnedAt") != null,
                archived = json.str("archivedAt") != null,
                snoozedUntil = parseInstant(json.str("snoozedUntil")),
                settled = json.str("settledAt") != null,
                createdAt = parseInstant(json.str("createdAt")),
                updatedAt = parseInstant(json.str("updatedAt")),
                latestRunCompletedAt = parseInstant(json.str("latestRunCompletedAt")),
                lastVisitedAt = parseInstant(json.str("lastVisitedAt")),
                pullRequestCount = json.arr("pullRequests")?.size ?: 0,
                hasActionablePlan = json.bool("hasActionableProposedPlan") ?: false,
                visibleItemCount = json.int("visibleItemCount") ?: 0,
                usageLimitResetAt = parseInstant(json.str("usageLimitResetAt")),
            )
        }
    }
}

data class ModelOptionChoice(val id: String, val label: String, val description: String?, val isDefault: Boolean)

data class ModelOptionDescriptor(
    val id: String,
    val label: String,
    val type: String,
    val choices: List<ModelOptionChoice>,
    val currentValue: String?,
)

data class ModelInfo(val slug: String, val name: String, val options: List<ModelOptionDescriptor>)

data class ProviderInfo(
    val instanceId: String,
    val driver: String,
    val displayName: String,
    val enabled: Boolean,
    val status: String,
    val models: List<ModelInfo>,
    val showInteractionModeToggle: Boolean,
    val usageLimits: JsonElement?,
    val slashCommands: List<SlashCommand>,
) {
    val usable: Boolean get() = enabled && status != "disabled" && models.isNotEmpty()

    companion object {
        fun from(json: JsonObject): ProviderInfo? {
            val id = json.str("instanceId") ?: return null
            return ProviderInfo(
                instanceId = id,
                driver = json.str("driver") ?: id,
                displayName = json.str("displayName") ?: id,
                enabled = json.bool("enabled") ?: false,
                status = json.str("status") ?: "unknown",
                models = json.objects("models").mapNotNull(::parseModel),
                showInteractionModeToggle = json.bool("showInteractionModeToggle") ?: true,
                usageLimits = json["usageLimits"],
                slashCommands = json.objects("slashCommands").mapNotNull { c ->
                    val name = c.str("name") ?: return@mapNotNull null
                    SlashCommand(name, c.str("description"))
                },
            )
        }

        private fun parseModel(json: JsonObject): ModelInfo? {
            val slug = json.str("slug") ?: return null
            val options = json.obj("capabilities")?.objects("optionDescriptors").orEmpty().mapNotNull { d ->
                val id = d.str("id") ?: return@mapNotNull null
                ModelOptionDescriptor(
                    id = id,
                    label = d.str("label") ?: id,
                    type = d.str("type") ?: "select",
                    choices = d.objects("options").mapNotNull { o ->
                        val oid = o.str("id") ?: return@mapNotNull null
                        ModelOptionChoice(oid, o.str("label") ?: oid, o.str("description"), o.bool("isDefault") ?: false)
                    },
                    currentValue = (d["currentValue"] as? JsonPrimitive)?.content,
                )
            }
            return ModelInfo(slug, json.str("name") ?: slug, options)
        }
    }
}

data class SlashCommand(val name: String, val description: String?)

data class ServerConfigState(
    val providers: List<ProviderInfo>,
    val settings: JsonObject?,
    val cwd: String?,
    val raw: JsonObject,
) {
    fun provider(instanceId: String?): ProviderInfo? = providers.firstOrNull { it.instanceId == instanceId }

    val usableProviders: List<ProviderInfo> get() = providers.filter { it.usable }

    companion object {
        fun from(config: JsonObject): ServerConfigState = ServerConfigState(
            providers = config.objects("providers").mapNotNull(ProviderInfo::from),
            settings = config.obj("settings"),
            cwd = config.str("cwd"),
            raw = config,
        )
    }
}
