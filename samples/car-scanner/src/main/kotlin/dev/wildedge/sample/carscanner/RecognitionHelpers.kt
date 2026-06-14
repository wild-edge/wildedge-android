package dev.wildedge.sample.carscanner

import dev.wildedge.sdk.ApiMeta
import dev.wildedge.sdk.events.DetectionOutputMeta
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TopPrediction
import org.json.JSONArray
import org.json.JSONObject

data class BoundingBox(val x: Double, val y: Double, val width: Double, val height: Double)

data class CarCandidate(
    val brand: String?,
    val model: String?,
    val color: String?,
    val year: String?,
    val confidence: Int?,
    val bbox: BoundingBox?,
)

data class CarInfo(val found: Boolean, val candidates: List<CarCandidate>?)

class ApiException(message: String) : Exception(message)

fun extractJson(text: String): String {
    val trimmed = text.trim()
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start == -1 || end == -1 || end < start) return trimmed
    return trimmed.substring(start, end + 1)
}

fun decodeCarInfo(text: String): CarInfo {
    val extracted = extractJson(text)
    val json = try {
        JSONObject(extracted)
    } catch (e: Exception) {
        throw ApiException("Cannot parse model response")
    }
    val found = json.optBoolean("found", false)
    val candidates = json.optJSONArray("candidates")?.let { array ->
        (0 until array.length()).map { i -> array.getJSONObject(i).toCarCandidate() }
    }
    return CarInfo(found, candidates)
}

private fun JSONObject.toCarCandidate(): CarCandidate {
    val bbox = optJSONObject("bbox")?.let {
        BoundingBox(
            x = it.optDouble("x"),
            y = it.optDouble("y"),
            width = it.optDouble("width"),
            height = it.optDouble("height"),
        )
    }
    return CarCandidate(
        brand = optStringOrNull("brand"),
        model = optStringOrNull("model"),
        color = optStringOrNull("color"),
        year = optStringOrNull("year"),
        confidence = if (has("confidence") && !isNull("confidence")) optInt("confidence") else null,
        bbox = bbox,
    )
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun prettyPrinted(text: String): String = try {
    val trimmed = text.trim()
    if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
} catch (e: Exception) {
    text
}

fun assertHttp200(statusCode: Int, body: String) {
    if (statusCode != 200) throw ApiException(body)
}

fun detectionMeta(info: CarInfo): Map<String, Any?> {
    val candidates = info.candidates
    if (!info.found || candidates.isNullOrEmpty()) {
        return DetectionOutputMeta(numPredictions = 0).toMap()
    }
    val topK = candidates.mapNotNull { c ->
        val parts = listOfNotNull(c.brand, c.model)
        if (parts.isEmpty()) return@mapNotNull null
        val confidence = c.confidence?.let { it / 100f }
        TopPrediction(label = parts.joinToString(" "), confidence = confidence)
    }
    val confidences = topK.mapNotNull { it.confidence }
    val avg = if (confidences.isEmpty()) null else confidences.sum() / confidences.size
    return DetectionOutputMeta(
        numPredictions = topK.size,
        topK = topK,
        avgConfidence = avg,
    ).toMap()
}

fun geminiGenerationMeta(json: JSONObject?): Map<String, Any?> {
    var tokensIn: Int? = null
    var tokensOut: Int? = null
    var stopReason: String? = null
    json?.optJSONObject("usageMetadata")?.let { usage ->
        tokensIn = usage.optIntOrNull("promptTokenCount")
        tokensOut = usage.optIntOrNull("candidatesTokenCount")
    }
    json?.optJSONArray("candidates")?.let { candidates ->
        if (candidates.length() > 0) {
            stopReason = candidates.getJSONObject(0).optStringOrNull("finishReason")?.lowercase()
        }
    }
    return GenerationOutputMeta(tokensIn = tokensIn, tokensOut = tokensOut, stopReason = stopReason).toMap()
}

fun openRouterGenerationMeta(json: JSONObject?): Map<String, Any?> {
    var tokensIn: Int? = null
    var tokensOut: Int? = null
    var cachedInputTokens: Int? = null
    var stopReason: String? = null
    json?.optJSONObject("usage")?.let { usage ->
        tokensIn = usage.optIntOrNull("prompt_tokens")
        tokensOut = usage.optIntOrNull("completion_tokens")
        usage.optJSONObject("prompt_tokens_details")?.let { details ->
            cachedInputTokens = details.optIntOrNull("cached_tokens")
        }
    }
    json?.optJSONArray("choices")?.let { choices ->
        if (choices.length() > 0) {
            stopReason = choices.getJSONObject(0).optStringOrNull("finish_reason")?.lowercase()
        }
    }
    return GenerationOutputMeta(
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        cachedInputTokens = cachedInputTokens,
        stopReason = stopReason,
    ).toMap()
}

/**
 * Merges generation metadata fields into the detection meta map.
 * Generation fields (tokens, stop_reason) are added alongside detection
 * fields; the "task" key from detection is preserved.
 */
fun mergedOutputMeta(detection: Map<String, Any?>, generation: Map<String, Any?>): Map<String, Any?> {
    val merged = detection.toMutableMap()
    for ((key, value) in generation) {
        if (key != "task") merged[key] = value
    }
    return merged
}

fun geminiApiMeta(json: JSONObject?): ApiMeta = ApiMeta(resolvedModelId = json?.optStringOrNull("modelVersion"))

fun openRouterApiMeta(json: JSONObject?): ApiMeta = ApiMeta(
    resolvedModelId = json?.optStringOrNull("model"),
    systemFingerprint = json?.optStringOrNull("system_fingerprint"),
    serviceTier = json?.optStringOrNull("service_tier"),
)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
