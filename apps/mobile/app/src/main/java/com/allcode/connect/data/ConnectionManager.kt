@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.allcode.connect.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.allcode.connect.net.EnvironmentHttp
import com.allcode.connect.net.ORCHESTRATION_PROTOCOL_VERSION
import com.allcode.connect.net.PairingTarget
import com.allcode.connect.net.T3Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A thread with the machine it lives on, for lists that span machines. */
data class EnvThread(
    val environmentId: String,
    val environmentLabel: String,
    val thread: ThreadSummary,
    val project: Project?,
)

data class EnvSnapshot(
    val connection: EnvironmentConnection,
    val env: SavedEnvironment,
    val status: ConnectionStatus,
    val shell: ShellState?,
    val config: ServerConfigState?,
)

/** Owns every paired machine's connection and decides when they should be live. */
class ConnectionManager(
    private val context: Context,
    private val store: EnvironmentStore,
    private val prefs: PreferencesStore,
    private val scope: CoroutineScope,
) {
    private val _connections = MutableStateFlow<List<EnvironmentConnection>>(emptyList())
    val connections: StateFlow<List<EnvironmentConnection>> = _connections.asStateFlow()
    private val _networkUp = MutableStateFlow(true)

    /** False while the phone itself has no network, which no amount of retrying will fix. */
    val networkUp: StateFlow<Boolean> = _networkUp.asStateFlow()
    private var foreground = false
    private var holdForService = false
    private var idleStop: Job? = null

    val clientParams: Map<String, String> = buildMap {
        put("clientSurface", "mobile")
        put("clientDeviceType", "phone")
        put("clientOs", "android")
        put("clientOsMajorVersion", Build.VERSION.RELEASE.substringBefore('.'))
        put("clientDeviceModel", Build.MODEL)
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()?.let { put("clientAppVersion", it) }
    }

    init {
        _connections.value = store.load().map { EnvironmentConnection(it, clientParams, scope) }
        watchNetwork()
    }

    /**
     * A socket on a network that's gone never errors on its own; it only times out. Following the
     * default network drops those sockets the moment it changes and retries as soon as one is back.
     */
    private fun watchNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                _networkUp.value = true
                if (previous != null && previous != network) {
                    _connections.value.forEach { it.drop("The network changed."); it.retryNow() }
                } else {
                    _connections.value.forEach { it.retryNow() }
                }
            }

            override fun onLost(network: Network) {
                if (current != network) return
                current = null
                _networkUp.value = false
                _connections.value.forEach { it.drop("This phone is offline.") }
            }
        })
    }

    fun connection(id: String?): EnvironmentConnection? = _connections.value.firstOrNull { it.id == id }

    /** Every machine with its live status, shell and config. */
    val snapshots: Flow<List<EnvSnapshot>> = connections.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { c ->
            combine(c.env, c.status, c.shell, c.config) { env, status, shell, config -> EnvSnapshot(c, env, status, shell, config) }
        }) { it.toList() }
    }

    val threads: Flow<List<EnvThread>> = snapshots.map { envs ->
        envs.flatMap { snap ->
            val shell = snap.shell ?: return@flatMap emptyList()
            shell.threads.values.map { EnvThread(snap.env.environmentId, snap.env.label, it, shell.project(it.projectId)) }
        }
    }

    /** True while any agent is working or waiting on the user, which keeps the live service up. */
    val hasLiveWork: Flow<Boolean> = threads.map { list ->
        list.any { it.thread.phase == ThreadPhase.Working || it.thread.phase == ThreadPhase.NeedsYou || it.thread.phase == ThreadPhase.Queued }
    }

    private val clientLabel: String
        get() = "AllCode on ${Build.MODEL}"

    /** Redeems a pairing credential and saves the machine. Re-pairing a known machine refreshes it. */
    suspend fun pair(target: PairingTarget): SavedEnvironment {
        val http = EnvironmentHttp(target.httpBaseUrl, { null })
        val descriptor = http.descriptor()
        val version = descriptor.protocolVersion
        if (version != null && version != ORCHESTRATION_PROTOCOL_VERSION) {
            throw T3Exception(
                T3Exception.Kind.Incompatible,
                if (version > ORCHESTRATION_PROTOCOL_VERSION) "${descriptor.label} runs a newer T3 Code than this app supports. Update AllCode Connect."
                else "${descriptor.label} runs an older T3 Code. Update it on that machine, then pair again.",
            )
        }
        val session = http.redeemPairingCredential(target.credential, clientLabel)
        val saved = SavedEnvironment(
            environmentId = descriptor.environmentId,
            label = target.label ?: descriptor.label,
            httpBaseUrl = target.httpBaseUrl,
            wsBaseUrl = target.wsBaseUrl,
            bearer = session.accessToken,
            addedAt = System.currentTimeMillis(),
            os = descriptor.os,
            serverVersion = descriptor.serverVersion,
        )
        val existing = connection(saved.environmentId)
        if (existing != null) {
            existing.update(saved.copy(addedAt = existing.env.value.addedAt))
        } else {
            val connection = EnvironmentConnection(saved, clientParams, scope)
            _connections.value = _connections.value + connection
        }
        persist()
        connection(saved.environmentId)?.start()
        prefs.update { it.copy(lastEnvironmentId = saved.environmentId) }
        return saved
    }

    internal fun adoptForPreview(connection: EnvironmentConnection) {
        _connections.value = _connections.value + connection
    }

    fun rename(id: String, label: String) {
        val connection = connection(id) ?: return
        connection.update(connection.env.value.copy(label = label.trim().ifEmpty { connection.env.value.label }))
        persist()
    }

    fun remove(id: String) {
        val connection = connection(id) ?: return
        connection.stop()
        _connections.value = _connections.value - connection
        persist()
    }

    private fun persist() {
        store.save(_connections.value.map { it.env.value })
    }

    fun setForeground(value: Boolean) {
        foreground = value
        reconcile()
        if (value) _connections.value.forEach { it.probe(); it.retryNow() }
    }

    fun setHeldByService(value: Boolean) {
        holdForService = value
        reconcile()
    }

    private fun reconcile() {
        idleStop?.cancel()
        if (foreground || holdForService) {
            _connections.value.forEach { it.start() }
            return
        }
        // A short grace period keeps quick app switches from dropping every socket.
        idleStop = scope.launch {
            delay(45_000)
            _connections.value.forEach { it.stop() }
        }
    }
}
