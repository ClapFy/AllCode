@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.allcode.connect.data

import com.allcode.connect.core.asObject
import com.allcode.connect.core.str
import com.allcode.connect.net.EnvironmentHttp
import com.allcode.connect.net.RpcException
import com.allcode.connect.net.RpcSocket
import com.allcode.connect.net.T3Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A paired machine. The bearer token is what `t3 pair` issued for this phone. */
@Serializable
data class SavedEnvironment(
    val environmentId: String,
    val label: String,
    val httpBaseUrl: String,
    val wsBaseUrl: String,
    val bearer: String,
    val addedAt: Long,
    val os: String? = null,
    val serverVersion: String? = null,
)

sealed interface ConnectionStatus {
    data object Offline : ConnectionStatus
    data class Connecting(val attempt: Int) : ConnectionStatus
    data object Syncing : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Retrying(val message: String, val retryAtMillis: Long) : ConnectionStatus
    /** Needs the user: re-pair, or update one side. Retries only when asked. */
    data class Blocked(val message: String, val needsPairing: Boolean) : ConnectionStatus
}

sealed interface ThreadFeed {
    data object Loading : ThreadFeed
    data class Ready(val projection: ThreadProjection) : ThreadFeed
    data class Failed(val message: String) : ThreadFeed
}

/** Keeps one environment's socket alive and mirrors its shell and config. */
class EnvironmentConnection(
    initial: SavedEnvironment,
    private val clientParams: Map<String, String>,
    private val scope: CoroutineScope,
) {
    private val _env = MutableStateFlow(initial)
    val env: StateFlow<SavedEnvironment> = _env.asStateFlow()
    val id: String get() = _env.value.environmentId

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    val shell = MutableStateFlow<ShellState?>(null)
    val config = MutableStateFlow<ServerConfigState?>(null)
    private val socket = MutableStateFlow<RpcSocket?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var previewOnly = false

    val http: EnvironmentHttp get() = EnvironmentHttp(_env.value.httpBaseUrl, { _env.value.bearer })

    fun update(saved: SavedEnvironment) {
        val reconnect = saved.bearer != _env.value.bearer || saved.wsBaseUrl != _env.value.wsBaseUrl
        _env.value = saved
        if (reconnect) {
            socket.value?.close()
            retryNow()
        }
    }

    fun start() {
        if (previewOnly || loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket.value?.close()
        socket.value = null
        _status.value = ConnectionStatus.Offline
    }

    fun retryNow() {
        wake.trySend(Unit)
    }

    /** The phone's network went away: the socket is dead, so say so now instead of after the ping timeout. */
    fun drop(reason: String) {
        socket.value?.abort(reason)
    }

    /** Checks that a socket which looks open still answers. */
    fun probe() {
        socket.value?.probe()
    }

    /** Screenshot tests show a machine with fixed state and no socket. */
    internal fun showForPreview(status: ConnectionStatus, shell: ShellState?, config: ServerConfigState?) {
        previewOnly = true
        _status.value = status
        this.shell.value = shell
        this.config.value = config
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _status.value = ConnectionStatus.Connecting(attempt)
            val failure: Exception? = try {
                connectOnce { attempt = 0 }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            val blocked = when {
                failure is T3Exception && failure.kind == T3Exception.Kind.Auth -> ConnectionStatus.Blocked(failure.message ?: "", true)
                failure is T3Exception && failure.kind == T3Exception.Kind.Incompatible -> ConnectionStatus.Blocked(failure.message ?: "", false)
                failure is RpcException && failure.tag in setOf("401", "403") -> ConnectionStatus.Blocked(failure.message ?: "", true)
                failure is RpcException && failure.tag == "426" -> ConnectionStatus.Blocked(failure.message ?: "", false)
                else -> null
            }
            if (blocked != null) {
                _status.value = blocked
                wake.receive()
                attempt = 0
                continue
            }
            val delayMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
            attempt += 1
            _status.value = ConnectionStatus.Retrying(
                failure?.message ?: "Disconnected.",
                System.currentTimeMillis() + delayMs,
            )
            withTimeoutOrNull(delayMs) { wake.receive() }
        }
    }

    private suspend fun connectOnce(onReady: () -> Unit): Nothing = coroutineScope {
        val saved = _env.value
        val ticket = http.webSocketTicket()
        val rpc = RpcSocket.open(saved.wsBaseUrl, ticket, saved.label, clientParams)
        socket.value = rpc
        try {
            _status.value = ConnectionStatus.Syncing
            launch {
                rpc.stream("subscribeServerConfig", buildJsonObject { put("usageLimitSources", true) }).collect { event ->
                    event.asObject()?.let { e -> config.update { it.applyConfigEvent(e) } }
                }
            }
            launch {
                rpc.stream("orchestration.subscribeShell").collect { item ->
                    val obj = item.asObject() ?: return@collect
                    shell.update { it.applyShellItem(obj) }
                    if (obj.str("kind") == "snapshot" && _status.value != ConnectionStatus.Connected) {
                        _status.value = ConnectionStatus.Connected
                        onReady()
                    }
                }
            }
            throw rpc.closed.await()
        } finally {
            socket.compareAndSet(rpc, null)
            rpc.close()
        }
    }

    /** Waits for a live socket, starting the connection if needed. */
    suspend fun awaitSocket(timeoutMs: Long = 20_000): RpcSocket {
        start()
        return withTimeoutOrNull(timeoutMs) { socket.filterNotNull().first() }
            ?: throw T3Exception(T3Exception.Kind.Network, "${_env.value.label} isn't reachable right now.")
    }

    suspend fun call(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement =
        awaitSocket().call(tag, payload)

    suspend fun dispatch(command: JsonObject): JsonElement = call("orchestration.dispatchCommand", command)

    fun stream(tag: String, payload: JsonElement): Flow<JsonElement> =
        socket.filterNotNull().flatMapLatest { rpc -> rpc.stream(tag, payload).catch { if (it !is RpcException || !it.transport) throw it } }

    /** The live projection of one thread, resubscribed after every reconnect. */
    fun thread(threadId: String): Flow<ThreadFeed> = flow {
        emit(ThreadFeed.Loading)
        start()
        socket.filterNotNull().collect { rpc ->
            var projection: ThreadProjection? = null
            try {
                val payload = buildJsonObject {
                    put("threadId", threadId)
                    put("acceptBoundedSnapshot", true)
                }
                rpc.stream("orchestration.subscribeThread", payload).collect { value ->
                    val item = value.asObject() ?: return@collect
                    projection = when (item.str("kind")) {
                        "snapshot" -> ThreadProjection.fromSnapshot(item) ?: projection
                        "event" -> projection?.apply(item)
                        else -> projection
                    }
                    projection?.let { emit(ThreadFeed.Ready(it)) }
                }
            } catch (e: RpcException) {
                if (!e.transport) emit(ThreadFeed.Failed(e.message ?: "This thread couldn't load."))
            }
        }
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
