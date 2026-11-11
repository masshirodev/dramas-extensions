package dramas.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lenient readers over kotlinx.serialization's tree: the sites' JSON is read by key,
// with no generated serializers, so a field that changes type reads as absent instead
// of throwing from inside a class the host has to load.

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content
    ?.takeIf { it.isNotEmpty() }

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content
    ?.toLongOrNull()

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content
    ?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()

fun JsonElement?.strings(): List<String> = (this as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content?.takeIf(String::isNotBlank) }
    .orEmpty()
