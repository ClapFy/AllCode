package com.allcode.connect.net

import com.allcode.connect.core.WireJson
import com.allcode.connect.core.asObject
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The protocol these clients speak. A server that reports another version refuses the socket. */
const val ORCHESTRATION_PROTOCOL_VERSION = 2

/** What the app shows when a request fails, grouped by what the user can do about it. */
class T3Exception(val kind: Kind, message: String, val status: Int? = null) : Exception(message) {
    enum class Kind { Network, Auth, Incompatible, Server }
}

data class EnvironmentDescriptor(
    val environmentId: String,
    val label: String,
    val os: String?,
    val serverVersion: String?,
    val protocolVersion: Int?,
    val capabilities: JsonObject,
) {
    fun capability(name: String): Boolean = capabilities[name]?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" || it is JsonObject
    } ?: false
}

data class BearerSession(val accessToken: String, val expiresInSeconds: Long, val scope: String)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Sockets read forever; liveness is the RPC ping. */
    val socketClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** HTTP calls to one environment. Every path is relative to the server's origin. */
class EnvironmentHttp(
    val httpBaseUrl: String,
    private val bearer: () -> String?,
    private val client: OkHttpClient = Http.client,
) {
    fun url(path: String): String = httpBaseUrl.trimEnd('/') + path

    private fun authorized(builder: Request.Builder): Request.Builder {
        bearer()?.let { builder.header("authorization", "Bearer $it") }
        return builder.header(ORCHESTRATION_HEADER, ORCHESTRATION_PROTOCOL_VERSION.toString())
    }

    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement {
        val url = url(path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return execute(authorized(Request.Builder().url(url).get()).build())
    }

    suspend fun postJson(path: String, body: JsonElement? = null): JsonElement {
        val requestBody = (body?.toString() ?: "").toRequestBody(JSON)
        return execute(authorized(Request.Builder().url(url(path)).post(requestBody)).build())
    }

    /** Sends attachment bytes to the signed upload URL `attachments.createUploadUrl` minted. */
    suspend fun uploadBytes(relativeUrl: String, bytes: ByteArray, mimeType: String) {
        val body: RequestBody = bytes.toRequestBody(mimeType.toMediaType())
        val response = request(Request.Builder().url(url(relativeUrl)).post(body).header("Content-Type", mimeType).build())
        response.use {
            if (!it.isSuccessful) throw T3Exception(T3Exception.Kind.Server, "Upload failed (${it.code}).", it.code)
        }
    }

    suspend fun getBytes(path: String): ByteArray {
        val response = request(authorized(Request.Builder().url(url(path)).get()).build())
        return response.use {
            if (!it.isSuccessful) throw httpError(it.code, it.body.string())
            it.body.bytes()
        }
    }

    private suspend fun request(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw T3Exception(T3Exception.Kind.Network, networkMessage(e))
        }
    }

    private suspend fun execute(request: Request): JsonElement {
        val response = request(request)
        return response.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw httpError(it.code, text)
            if (text.isBlank()) JsonObject(emptyMap()) else WireJson.parseToJsonElement(text)
        }
    }

    suspend fun descriptor(): EnvironmentDescriptor {
        val body = getJson("/.well-known/t3/environment").asObject()
            ?: throw T3Exception(T3Exception.Kind.Server, "That address didn't answer like T3 Code.")
        return EnvironmentDescriptor(
            environmentId = body.str("environmentId")
                ?: throw T3Exception(T3Exception.Kind.Server, "That address didn't answer like T3 Code."),
            label = body.str("label") ?: "T3 Code",
            os = body.obj("platform")?.str("os"),
            serverVersion = body.str("serverVersion"),
            protocolVersion = body.int("orchestrationProtocolVersion"),
            capabilities = body.obj("capabilities") ?: JsonObject(emptyMap()),
        )
    }

    /** Redeems a one-time pairing credential for a long-lived bearer token. */
    suspend fun redeemPairingCredential(credential: String, clientLabel: String): BearerSession {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            .add("subject_token", credential)
            .add("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
            .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
            .add("client_label", clientLabel)
            .add("client_device_type", "mobile")
            .add("client_os", "android")
            .build()
        val response = request(Request.Builder().url(url("/oauth/token")).post(form).build())
        val text = response.use { it.body.string() }
        if (!response.isSuccessful) {
            if (response.code in 400..401) {
                throw T3Exception(
                    T3Exception.Kind.Auth,
                    "That pairing code expired or was already used. Make a new one on your computer.",
                    response.code,
                )
            }
            throw httpError(response.code, text)
        }
        val body = WireJson.parseToJsonElement(text).asObject()
            ?: throw T3Exception(T3Exception.Kind.Server, "The server sent an unexpected reply.")
        return BearerSession(
            accessToken = body.str("access_token")
                ?: throw T3Exception(T3Exception.Kind.Server, "The server didn't issue a token."),
            expiresInSeconds = body.str("expires_in")?.toDoubleOrNull()?.toLong() ?: 0,
            scope = body.str("scope") ?: "",
        )
    }

    /** A short-lived ticket that authorizes one RPC socket. Older servers only have the bare path. */
    suspend fun webSocketTicket(): String {
        val body = try {
            postJson("/api/auth/websocket-ticket/rpc")
        } catch (e: T3Exception) {
            if (e.status == 404) postJson("/api/auth/websocket-ticket") else throw e
        }
        return body.asObject()?.str("ticket")
            ?: throw T3Exception(T3Exception.Kind.Server, "The server didn't issue a socket ticket.")
    }

    companion object {
        const val ORCHESTRATION_HEADER = "x-t3-orchestration-protocol"
        val JSON = "application/json".toMediaType()

        fun networkMessage(e: IOException): String = when (e) {
            is java.net.UnknownHostException -> "Couldn't find that machine. Check the address and your network."
            is java.net.ConnectException -> "Couldn't reach T3 Code there. Is it running and reachable from this phone?"
            is java.net.SocketTimeoutException -> "The machine took too long to answer."
            is javax.net.ssl.SSLException -> "A secure connection couldn't be made. Try http:// for LAN or Tailscale addresses."
            else -> e.message ?: "The network request failed."
        }

        fun httpError(code: Int, body: String): T3Exception {
            val parsed = runCatching { WireJson.parseToJsonElement(body).asObject() }.getOrNull()
            val message = parsed?.str("message") ?: parsed?.str("detail")
            return when (code) {
                401, 403 -> T3Exception(
                    T3Exception.Kind.Auth,
                    message ?: "This phone isn't authorized anymore. Pair it again.",
                    code,
                )
                426 -> T3Exception(
                    T3Exception.Kind.Incompatible,
                    "This machine runs a T3 Code version that speaks a different protocol. Update T3 Code or AllCode Connect.",
                    code,
                )
                else -> T3Exception(T3Exception.Kind.Server, message ?: "The server answered $code.", code)
            }
        }
    }
}
