package com.allcode.connect.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.allcode.connect.core.asObject
import com.allcode.connect.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val SUPPORTED_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

fun describeUri(context: Context, uri: Uri): Pair<String, Long> {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val name = cursor.getString(0) ?: "attachment"
            val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            return name to size
        }
    }
    return (uri.lastPathSegment ?: "attachment") to 0L
}

suspend fun EnvironmentConnection.uploadAttachment(context: Context, uri: Uri, name: String, mimeType: String): JsonObject {
    val bytes = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read $name.")
    }
    return uploadAttachmentBytes(bytes, name, mimeType)
}

/** Mints a signed upload URL, posts the bytes, and returns the record a message references. */
suspend fun EnvironmentConnection.uploadAttachmentBytes(bytes: ByteArray, name: String, mimeType: String): JsonObject {
    val type = if (mimeType in SUPPORTED_IMAGES) "image" else "file"
    val minted = call("attachments.createUploadUrl", buildJsonObject {
        put("type", type)
        put("name", name.take(255))
        put("mimeType", mimeType)
        put("sizeBytes", bytes.size)
    }).asObject() ?: error("The server didn't accept the attachment.")
    http.uploadBytes(minted.str("relativeUrl") ?: error("No upload URL."), bytes, mimeType)
    return buildJsonObject {
        put("type", type)
        put("id", minted.str("attachmentId") ?: error("No attachment id."))
        put("name", name.take(255))
        put("mimeType", mimeType)
        put("sizeBytes", bytes.size)
    }
}
