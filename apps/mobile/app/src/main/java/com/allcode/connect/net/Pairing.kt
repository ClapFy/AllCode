package com.allcode.connect.net

import java.net.URI
import java.net.URLDecoder

/** Where to redeem a one-time pairing credential. Mirrors `resolveRemotePairingTarget` in packages/shared. */
data class PairingTarget(
    val credential: String,
    val httpBaseUrl: String,
    val wsBaseUrl: String,
    val label: String? = null,
)

class PairingInputException(message: String) : Exception(message)

object Pairing {
    private val appLinkScheme = Regex("^(t3code(-[a-z]+)?|allcode)$")
    private val backendSchemes = setOf("http", "https", "ws", "wss")

    /** Accepts a scanned QR or pasted text: a `t3code://pair` app link or a pairing URL. */
    fun parseLink(raw: String): PairingTarget {
        val text = raw.trim()
        if (text.isEmpty()) throw PairingInputException("Paste a pairing link or scan its QR code.")
        val uri = runCatching { URI(text) }.getOrNull()
            ?: throw PairingInputException("That isn't a pairing link.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && appLinkScheme.matches(scheme)) {
            if (uri.host != "pair") throw PairingInputException("That link doesn't pair a machine.")
            val pairingUrl = queryParams(uri.rawQuery)["pairingUrl"]
                ?: throw PairingInputException("The app link is missing its pairing URL.")
            return parsePairingUrl(pairingUrl)
        }
        return parsePairingUrl(text)
    }

    /** A host such as `192.168.1.20:3773` plus the code `t3 pair` printed. */
    fun fromHostAndCode(host: String, code: String): PairingTarget {
        val base = normalizeHost(host)
        val credential = code.trim()
        if (credential.isEmpty()) throw PairingInputException("Enter the pairing code.")
        return PairingTarget(credential, httpBase(base), wsBase(base))
    }

    private fun parsePairingUrl(text: String): PairingTarget {
        val uri = runCatching { URI(text.trim()) }.getOrNull()
            ?: throw PairingInputException("That pairing URL isn't valid.")
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in backendSchemes) {
            throw PairingInputException("Pairing URLs start with http:// or https://.")
        }
        val hash = queryParams(uri.rawFragment)
        val query = queryParams(uri.rawQuery)
        val token = (hash["token"] ?: query["token"])?.trim().orEmpty()
        val hostedHost = query["host"]?.trim().orEmpty()
        if (hostedHost.isNotEmpty() && token.isNotEmpty()) {
            val base = normalizeHost(hostedHost)
            return PairingTarget(token, httpBase(base), wsBase(base), query["label"]?.trim()?.ifEmpty { null })
        }
        if (token.isEmpty()) throw PairingInputException("The pairing URL for ${uri.host} is missing its token.")
        return PairingTarget(token, httpBase(uri), wsBase(uri))
    }

    fun normalizeHost(raw: String): URI {
        val trimmed = raw.trim().trimStart('/')
        if (trimmed.isEmpty()) throw PairingInputException("Enter the address of the machine running T3 Code.")
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z\\d+-]*://").containsMatchIn(trimmed)) {
            trimmed
        } else {
            "${defaultScheme(trimmed)}://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: throw PairingInputException("That address isn't valid.")
        if (uri.scheme?.lowercase() !in backendSchemes || uri.host.isNullOrEmpty()) {
            throw PairingInputException("That address isn't valid.")
        }
        return uri
    }

    /** Bare LAN and tailnet addresses almost always serve plain http; public names get https. */
    private fun defaultScheme(hostAndPort: String): String {
        val host = hostAndPort.substringBefore('/').substringBefore(':').lowercase()
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        val local = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || !host.contains('.')
        val hasPort = hostAndPort.substringBefore('/').contains(':')
        return if (ipv4 || local || hasPort) "http" else "https"
    }

    private fun origin(uri: URI, scheme: String): String {
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://${uri.host}$port/"
    }

    fun httpBase(uri: URI): String = when (uri.scheme.lowercase()) {
        "ws" -> origin(uri, "http")
        "wss" -> origin(uri, "https")
        else -> origin(uri, uri.scheme.lowercase())
    }

    fun wsBase(uri: URI): String = when (uri.scheme.lowercase()) {
        "http" -> origin(uri, "ws")
        "https" -> origin(uri, "wss")
        else -> origin(uri, uri.scheme.lowercase())
    }

    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
            key to value
        }.toMap()
    }
}
