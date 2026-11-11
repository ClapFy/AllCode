package com.allcode.connect.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.allcode.connect.core.WireJson
import com.allcode.connect.core.asObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.InputStream

sealed interface FrameEvent {
    data class Frame(val bitmap: Bitmap) : FrameEvent
    data class Message(val json: JsonObject) : FrameEvent
    data class Closed(val reason: String?, val code: Int? = null) : FrameEvent
}

/**
 * The server browser's screencast: binary JPEG frames, each acked so the server sends the next,
 * plus JSON messages for viewport, control, and dialogs. Input goes back as JSON.
 */
class PreviewStreamClient(private val url: String) {
    private var socket: WebSocket? = null

    fun events(): Flow<FrameEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val array = bytes.toByteArray()
                webSocket.send("""{"type":"ack"}""")
                BitmapFactory.decodeByteArray(array, 0, array.size)?.let { trySend(FrameEvent.Frame(it)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { WireJson.parseToJsonElement(text).asObject() }.getOrNull()?.let { trySend(FrameEvent.Message(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(FrameEvent.Closed(reason.ifEmpty { null }, code))
                webSocket.close(1000, null)
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(FrameEvent.Closed(reason.ifEmpty { null }, code))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(FrameEvent.Closed(if (response?.code == 401) "Not authorized" else t.message))
                close()
            }
        }
        socket = Http.socketClient.newWebSocket(Request.Builder().url(url).build(), listener)
        awaitClose { socket?.close(1000, null) }
    }

    fun send(input: JsonObject): Boolean = socket?.send(input.toString()) ?: false
}

/** Reads a multipart MJPEG stream (device screens) and yields each decoded frame. */
fun mjpegFrames(url: String): Flow<Bitmap> = flow {
    val response = Http.socketClient.newCall(Request.Builder().url(url).build()).execute()
    response.use {
        if (!it.isSuccessful) throw T3Exception(T3Exception.Kind.Server, "The device stream answered ${it.code}.", it.code)
        val input = it.body.byteStream()
        while (true) {
            val frame = readJpeg(input) ?: break
            BitmapFactory.decodeByteArray(frame, 0, frame.size)?.let { bitmap -> emit(bitmap) }
        }
    }
}.flowOn(Dispatchers.IO)

/** Scans for the next SOI…EOI JPEG. Multipart headers between frames are skipped. */
private fun readJpeg(input: InputStream): ByteArray? {
    var previous = -1
    while (true) {
        val b = input.read()
        if (b == -1) return null
        if (previous == 0xFF && b == 0xD8) break
        previous = b
    }
    val out = ByteArrayOutputStream(64 * 1024)
    out.write(0xFF); out.write(0xD8)
    previous = -1
    while (true) {
        val b = input.read()
        if (b == -1) return null
        out.write(b)
        if (previous == 0xFF && b == 0xD9) return out.toByteArray()
        previous = b
    }
}
