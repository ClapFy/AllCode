@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.allcode.connect.net

import com.allcode.connect.core.WireJson
import com.allcode.connect.core.arr
import com.allcode.connect.core.asObject
import com.allcode.connect.core.obj
import com.allcode.connect.core.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A failed RPC: the server's tagged error, or a socket that went away underneath it. */
class RpcException(
    message: String,
    val tag: String? = null,
    val error: JsonObject? = null,
    val transport: Boolean = false,
) : Exception(message)

/**
 * Effect RPC over one WebSocket, JSON-serialized. Each `Request` gets `Chunk`s (acked, so the
 * server keeps streaming) and one `Exit`. Pings go out every 5s; 15s of silence drops the socket.
 */
class RpcSocket private constructor(
    private val label: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val opened = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<RpcException>()
    private var socket: WebSocket? = null
    @Volatile private var lastFrameAt = System.currentTimeMillis()
    private val partBuffer = StringBuilder()

    private sealed interface Pending {
        class Call(val result: CompletableDeferred<JsonElement>) : Pending
        class Stream(val values: Channel<JsonElement>) : Pending
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastFrameAt = System.currentTimeMillis()
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastFrameAt = System.currentTimeMillis()
            // The server splits large frames; every part but the last starts with a record separator.
            if (text.startsWith(PART_PREFIX)) {
                synchronized(partBuffer) { partBuffer.append(text, 1, text.length) }
                return
            }
            val whole = synchronized(partBuffer) {
                if (partBuffer.isEmpty()) text else partBuffer.append(text).toString().also { partBuffer.clear() }
            }
            val parsed = runCatching { WireJson.parseToJsonElement(whole) }.getOrNull() ?: return
            val messages = if (parsed is JsonArray) parsed.mapNotNull { it.asObject() } else listOfNotNull(parsed.asObject())
            messages.forEach(::dispatch)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            fail(RpcException("$label disconnected.", transport = true))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(RpcException("$label disconnected.", transport = true))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when {
                response?.code == 426 -> "This machine runs a T3 Code version that speaks a different protocol."
                response?.code == 401 || response?.code == 403 -> "This phone isn't authorized anymore. Pair it again."
                !opened.isCompleted -> "Couldn't open a connection to $label."
                else -> "$label disconnected."
            }
            fail(RpcException(message, tag = response?.code?.toString(), transport = true))
        }
    }

    private fun dispatch(message: JsonObject) {
        when (message.str("_tag")) {
            "Chunk" -> {
                val id = message.str("requestId") ?: return
                val target = pending[id] as? Pending.Stream ?: return
                message.arr("values")?.forEach { target.values.trySend(it) }
                send(buildJsonObject { put("_tag", "Ack"); put("requestId", id) })
            }
            "Exit" -> {
                val id = message.str("requestId") ?: return
                val target = pending.remove(id) ?: return
                val exit = message.obj("exit")
                if (exit?.str("_tag") == "Success") {
                    when (target) {
                        is Pending.Call -> target.result.complete(exit["value"] ?: JsonObject(emptyMap()))
                        is Pending.Stream -> target.values.close()
                    }
                } else {
                    val failure = exitFailure(exit)
                    when (target) {
                        is Pending.Call -> target.result.completeExceptionally(failure)
                        is Pending.Stream -> target.values.close(failure)
                    }
                }
            }
            "Defect" -> fail(RpcException("$label hit an internal error.", error = message.obj("defect")))
            "ClientProtocolError" -> {
                val error = RpcException("$label rejected a request.", error = message.obj("error"))
                val ids = message.arr("requestIds")?.mapNotNull { (it as? JsonPrimitive)?.content }
                (ids ?: pending.keys.toList()).forEach { id -> failOne(id, error) }
            }
            else -> Unit // Pong and server-initiated requests carry nothing for this client.
        }
    }

    private fun exitFailure(exit: JsonObject?): RpcException {
        val causes = exit?.arr("cause")?.mapNotNull { it.asObject() }.orEmpty()
        val fail = causes.firstOrNull { it.str("_tag") == "Fail" }?.obj("error")
        if (fail != null) {
            val text = fail.str("message") ?: fail.str("detail") ?: fail.str("_tag") ?: "The request failed."
            return RpcException(text, tag = fail.str("_tag"), error = fail)
        }
        if (causes.any { it.str("_tag") == "Interrupt" }) return RpcException("The request was interrupted.", tag = "Interrupt")
        return RpcException("$label hit an internal error.", tag = "Die")
    }

    private fun failOne(id: String, error: RpcException) {
        when (val target = pending.remove(id)) {
            is Pending.Call -> target.result.completeExceptionally(error)
            is Pending.Stream -> target.values.close(error)
            null -> Unit
        }
    }

    private fun fail(error: RpcException) {
        if (!closed.complete(error)) return
        opened.completeExceptionally(error)
        pending.keys.toList().forEach { failOne(it, error) }
        scope.cancel()
        socket?.cancel()
    }

    private fun send(message: JsonObject) {
        socket?.send(message.toString())
    }

    private fun requestMessage(id: String, tag: String, payload: JsonElement) = buildJsonObject {
        put("_tag", "Request")
        put("id", id)
        put("tag", tag)
        put("payload", payload)
        put("headers", JsonArray(emptyList()))
    }

    /** One request, one answer. */
    suspend fun call(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement {
        closed.takeIf { it.isCompleted }?.let { throw it.getCompleted() }
        val id = nextId.getAndIncrement().toString()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = Pending.Call(result)
        send(requestMessage(id, tag, payload))
        try {
            return result.await()
        } finally {
            if (pending.remove(id) != null) send(interrupt(id))
        }
    }

    /** A server stream. Collecting sends the request; cancelling interrupts it on the server. */
    fun stream(tag: String, payload: JsonElement = JsonObject(emptyMap())): Flow<JsonElement> = channelFlow {
        closed.takeIf { it.isCompleted }?.let { throw it.getCompleted() }
        val id = nextId.getAndIncrement().toString()
        val values = Channel<JsonElement>(Channel.UNLIMITED)
        pending[id] = Pending.Stream(values)
        this@RpcSocket.send(requestMessage(id, tag, payload))
        try {
            for (value in values) send(value)
        } finally {
            if (pending.remove(id) != null) this@RpcSocket.send(interrupt(id))
        }
    }

    private fun interrupt(id: String) = buildJsonObject { put("_tag", "Interrupt"); put("requestId", id) }

    fun close() {
        send(buildJsonObject { put("_tag", "Eof") })
        socket?.close(1000, "client closed")
        fail(RpcException("$label disconnected.", transport = true))
    }

    /** Drops a socket known to be dead (its network went away) without waiting for the ping timeout. */
    fun abort(message: String) {
        fail(RpcException(message, transport = true))
    }

    /**
     * Pings now and fails the socket if nothing comes back within [PROBE_TIMEOUT_MS]. Used when the app
     * returns to the foreground, where a socket that died while the phone slept would otherwise look live.
     */
    fun probe() {
        if (closed.isCompleted) return
        val sentAt = System.currentTimeMillis()
        send(buildJsonObject { put("_tag", "Ping") })
        scope.launch {
            delay(PROBE_TIMEOUT_MS)
            if (lastFrameAt < sentAt) fail(RpcException("$label isn't answering.", transport = true))
        }
    }

    private fun startPinger(): Job = scope.launch {
        while (isActive) {
            delay(PING_INTERVAL_MS)
            if (System.currentTimeMillis() - lastFrameAt > PING_TIMEOUT_MS) {
                fail(RpcException("$label stopped responding for 15s. It may be asleep or busy.", transport = true))
                return@launch
            }
            send(buildJsonObject { put("_tag", "Ping") })
        }
    }

    companion object {
        private const val PART_PREFIX = "\u001e"
        private const val PING_INTERVAL_MS = 5_000L
        private const val PING_TIMEOUT_MS = 15_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 4_000L

        /** Opens `/ws` with a fresh ticket and waits for the handshake. */
        suspend fun open(
            wsBaseUrl: String,
            ticket: String,
            label: String,
            clientParams: Map<String, String>,
            client: OkHttpClient = Http.socketClient,
        ): RpcSocket {
            val rpc = RpcSocket(label)
            val url = buildString {
                append(wsBaseUrl.trimEnd('/'))
                append("/ws?wsTicket=").append(encode(ticket))
                append("&frameParts=1&orchestrationProtocol=").append(ORCHESTRATION_PROTOCOL_VERSION)
                clientParams.forEach { (k, v) -> append('&').append(k).append('=').append(encode(v)) }
            }
            rpc.socket = client.newWebSocket(Request.Builder().url(url).build(), rpc.listener)
            try {
                withTimeout(OPEN_TIMEOUT_MS) { rpc.opened.await() }
            } catch (e: Exception) {
                rpc.fail(RpcException("Couldn't open a connection to $label.", transport = true))
                throw (rpc.closed.takeIf { it.isCompleted }?.getCompleted() ?: e)
            }
            rpc.startPinger()
            return rpc
        }

        private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}
