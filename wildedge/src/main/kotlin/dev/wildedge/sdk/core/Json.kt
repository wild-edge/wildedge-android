package dev.wildedge.sdk

// Minimal JSON serializer — no external dependencies.
// Only handles Map<String, Any?>, List<Any?>, String, Number, Boolean, null.

internal fun Map<String, Any?>.toJson(): String = buildString {
    append('{')
    var first = true
    for ((k, v) in this@toJson) {
        if (!first) append(',')
        first = false
        append('"').append(k.jsonEscape()).append('"').append(':')
        if (v == null) append("null") else append(v.toJsonValue())
    }
    append('}')
}

@Suppress("UNCHECKED_CAST")
internal fun Any.toJsonValue(): String = when (this) {
    is String -> "\"${this.jsonEscape()}\""
    is Boolean -> this.toString()
    is Number -> this.toString()
    is Map<*, *> -> (this as Map<String, Any?>).toJson()
    is List<*> -> buildString {
        append('[')
        var first = true
        for (item in this@toJsonValue) {
            if (item == null) continue
            if (!first) append(',')
            first = false
            append(item.toJsonValue())
        }
        append(']')
    }
    else -> "\"${this.toString().jsonEscape()}\""
}

private fun String.jsonEscape(): String = buildString {
    for (c in this@jsonEscape) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

// Extracts a string value from a known-structure JSON response.
internal fun String.extractJsonString(key: String): String? =
    """"$key"\s*:\s*"([^"]*?)"""".toRegex().find(this)?.groupValues?.get(1)

internal fun String.extractJsonInt(key: String): Int =
    """"$key"\s*:\s*(\d+)""".toRegex().find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
