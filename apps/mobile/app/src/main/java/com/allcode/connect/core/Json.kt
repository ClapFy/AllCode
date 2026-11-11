package com.allcode.connect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Wire format shared by every T3 request: unknown fields from newer servers are ignored. */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

fun JsonObject.str(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    return value.content
}

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let {
    it.longOrNull ?: it.doubleOrNull?.toLong()
}

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objects(key: String): List<JsonObject> =
    arr(key)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonObject.strings(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content } ?: emptyList()

/** Copies an object with some keys replaced; used when patching projection records. */
fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject =
    JsonObject(toMutableMap().apply { pairs.forEach { (k, v) -> put(k, v) } })
