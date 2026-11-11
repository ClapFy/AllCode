package com.allcode.connect.data

import com.allcode.connect.core.arr
import com.allcode.connect.core.long
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The projects and threads one environment shows in lists. */
data class ShellState(
    val sequence: Long,
    val projects: List<Project>,
    val threads: Map<String, ThreadSummary>,
    val archived: Map<String, ThreadSummary>,
) {
    fun project(id: String?): Project? = projects.firstOrNull { it.id == id }

    companion object {
        val Empty = ShellState(0, emptyList(), emptyMap(), emptyMap())
    }
}

/**
 * Applies one `orchestration.subscribeShell` item. A snapshot that carries
 * `resolvedRepositoryIdentityRoots` only enriches project identities; any other snapshot replaces.
 */
fun ShellState?.applyShellItem(item: JsonObject): ShellState? {
    when (item.str("kind")) {
        "synchronized" -> return this
        "snapshot" -> {
            val snapshot = item.obj("snapshot") ?: return this
            val enrichment = item["resolvedRepositoryIdentityRoots"] != null
            val projects = snapshot.objects("projects").mapNotNull(Project::from)
            if (enrichment && this != null) {
                val byId = projects.associateBy { it.id }
                return copy(projects = this.projects.map { existing ->
                    val candidate = byId[existing.id]
                    if (candidate == null || candidate.workspaceRoot != existing.workspaceRoot) existing
                    else if (candidate.repositoryName != null) existing.copy(repositoryName = candidate.repositoryName)
                    else existing
                })
            }
            return ShellState(
                sequence = snapshot.long("snapshotSequence") ?: 0,
                projects = projects,
                threads = snapshot.objects("threads").mapNotNull(ThreadSummary::from).associateBy { it.id },
                archived = snapshot.objects("archivedThreads").mapNotNull(ThreadSummary::from).associateBy { it.id },
            )
        }
    }
    val state = this ?: return null
    val sequence = item.long("sequence") ?: return state
    if (sequence <= state.sequence) return state
    return when (item.str("kind")) {
        "project.updated" -> {
            val project = item.obj("project")?.let(Project::from) ?: return state
            val previous = state.project(project.id)
            val merged = if (project.repositoryName == null && previous?.workspaceRoot == project.workspaceRoot) {
                project.copy(repositoryName = previous.repositoryName)
            } else project
            val exists = state.projects.any { it.id == project.id }
            state.copy(
                sequence = sequence,
                projects = if (exists) state.projects.map { if (it.id == project.id) merged else it } else state.projects + merged,
            )
        }
        "project.removed" -> state.copy(
            sequence = sequence,
            projects = state.projects.filterNot { it.id == item.str("projectId") },
        )
        "thread.updated" -> {
            val thread = item.obj("thread")?.let(ThreadSummary::from) ?: return state
            if (item.str("location") == "archive") {
                state.copy(sequence = sequence, threads = state.threads - thread.id, archived = state.archived + (thread.id to thread))
            } else {
                state.copy(sequence = sequence, threads = state.threads + (thread.id to thread), archived = state.archived - thread.id)
            }
        }
        "thread.removed" -> {
            val id = item.str("threadId") ?: return state
            if (item.str("location") == "archive") state.copy(sequence = sequence, archived = state.archived - id)
            else state.copy(sequence = sequence, threads = state.threads - id)
        }
        else -> state
    }
}

/** Applies one `subscribeServerConfig` event. */
fun ServerConfigState?.applyConfigEvent(event: JsonObject): ServerConfigState? {
    return when (event.str("type")) {
        "snapshot" -> event.obj("config")?.let(ServerConfigState::from) ?: this
        "providerStatuses" -> {
            val providers = event.obj("payload")?.objects("providers") ?: return this
            this?.copy(providers = providers.mapNotNull(ProviderInfo::from))
        }
        "settingsUpdated" -> this?.copy(settings = event.obj("payload")?.obj("settings") ?: settings)
        else -> this
    }
}

fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.content

fun JsonObject.idList(key: String): List<String> = arr(key)?.mapNotNull { it.primitiveContent() } ?: emptyList()
