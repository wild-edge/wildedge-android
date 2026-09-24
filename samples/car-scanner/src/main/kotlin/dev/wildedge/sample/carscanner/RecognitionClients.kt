package dev.wildedge.sample.carscanner

import dev.wildedge.sdk.InferenceAttachment
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

data class HttpStats(val statusCode: Int, val durationMs: Int, val responseSize: Int)

data class AnalysisResult(
    val carInfo: CarInfo,
    val rawJson: String,
    val httpStats: HttpStats,
    val inferenceId: String,
)

object OpenRouterClient {
    private const val MODEL_ID = "openrouter/gemini-2.5-flash"

    val handle by lazy {
        WildEdge.getInstance().registerModel(
            modelId = MODEL_ID,
            info = ModelInfo(
                modelName = "gemini-2.5-flash",
                modelSource = "openrouter",
                modelFormat = "api",
                modelFamily = "gemini",
            ),
        )
    }

    fun analyze(
        imageData: ByteArray,
        prompt: String,
        inputMeta: Map<String, Any?>,
        runId: String? = null,
        responseDelayMs: Long = 0L,
    ): AnalysisResult {
        val apiKey = BuildConfig.OPENROUTER_API_KEY.trim()
        if (apiKey.isEmpty()) throw ApiException("Set openrouter.api.key in local.properties")

        val body = JSONObject().apply {
            put("model", "google/gemini-2.5-flash")
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            JSONObject().put(
                                                "url",
                                                "data:image/jpeg;base64,${Base64.encodeToString(imageData, Base64.NO_WRAP)}",
                                            ),
                                        )
                                    },
                                )
                                .put(JSONObject().apply { put("type", "text"); put("text", prompt) }),
                        )
                    },
                ),
            )
        }

        val start = System.currentTimeMillis()
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        val statusCode = connection.responseCode
        val responseBytes = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            .use { it.readBytes() }
        val responseText = String(responseBytes)
        val stats = HttpStats(
            statusCode = statusCode,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            responseSize = responseBytes.size,
        )

        if (stats.statusCode != 200) {
            handle.trackError(errorCode = "HTTP_${stats.statusCode}", errorMessage = responseText.take(256))
            throw ApiException(responseText)
        }

        val json = JSONObject(responseText)
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
        if (text == null) {
            handle.trackError(errorCode = "PARSE_ERROR", errorMessage = "Unexpected OpenRouter response format")
            throw ApiException("Unexpected OpenRouter response format")
        }

        val info = try {
            decodeCarInfo(text)
        } catch (e: ApiException) {
            handle.trackError(errorCode = "PARSE_ERROR", errorMessage = e.message)
            throw e
        }

        val inferenceId = handle.trackInference(
            durationMs = stats.durationMs,
            inputModality = InputModality.Multimodal,
            outputModality = OutputModality.Detection,
            success = true,
            inputMeta = inputMeta,
            outputMeta = mergedOutputMeta(detectionMeta(info), openRouterGenerationMeta(json)),
            apiMeta = openRouterApiMeta(json),
            attachments = listOf(
                InferenceAttachment(
                    name = "input.jpg",
                    role = InferenceAttachment.Role.Input,
                    payload = InferenceAttachment.Payload.Data(imageData, "image/jpeg"),
                ),
            ),
            runId = runId,
        )

        return AnalysisResult(info, prettyPrinted(responseText), stats, inferenceId)
    }
}

object GeminiClient {
    private const val MODEL_NAME = "gemini-3.6-flash"
    private const val MODEL_ID = "google/$MODEL_NAME"
    private const val MAX_ATTEMPTS = 4
    private const val INITIAL_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 8_000L
    private const val RETRY_JITTER_MS = 250L

    val handle by lazy {
        WildEdge.getInstance().registerModel(
            modelId = MODEL_ID,
            info = ModelInfo(
                modelName = MODEL_NAME,
                modelSource = "google",
                modelFormat = "api",
                modelFamily = "gemini",
            ),
        )
    }

    fun analyze(
        imageData: ByteArray,
        prompt: String,
        inputMeta: Map<String, Any?>,
        runId: String? = null,
        responseDelayMs: Long = 0L,
    ): AnalysisResult {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isEmpty()) throw ApiException("Set google.ai.api.key in local.properties")

        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", Base64.encodeToString(imageData, Base64.NO_WRAP))
                                    },
                                ),
                            )
                            .put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
        }

        val start = System.currentTimeMillis()
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"
        val requestBytes = body.toString().toByteArray()
        var statusCode = 0
        var responseBytes = ByteArray(0)
        var retryDelayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_ATTEMPTS) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write(requestBytes) }
                statusCode = connection.responseCode
                responseBytes = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
            } finally {
                connection.disconnect()
            }

            if (!isTransientGeminiStatus(statusCode) || attempt == MAX_ATTEMPTS) break

            Thread.sleep(retryDelayMs + Random.nextLong(RETRY_JITTER_MS + 1))
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        val responseText = String(responseBytes)
        val stats = HttpStats(
            statusCode = statusCode,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            responseSize = responseBytes.size,
        )

        if (stats.statusCode != 200) {
            handle.trackError(errorCode = "HTTP_${stats.statusCode}", errorMessage = responseText.take(256))
            if (stats.statusCode == 503) {
                throw ApiException("Gemini is temporarily busy after $MAX_ATTEMPTS attempts. Try again shortly.")
            }
            throw ApiException(responseText)
        }

        val json = JSONObject(responseText)
        val text = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
        if (text == null) {
            handle.trackError(errorCode = "PARSE_ERROR", errorMessage = "Unexpected Gemini response format")
            throw ApiException("Unexpected Gemini response format")
        }

        val info = try {
            decodeCarInfo(text)
        } catch (e: ApiException) {
            handle.trackError(errorCode = "PARSE_ERROR", errorMessage = e.message)
            throw e
        }

        val inferenceId = handle.trackInference(
            durationMs = stats.durationMs,
            inputModality = InputModality.Multimodal,
            outputModality = OutputModality.Detection,
            success = true,
            inputMeta = inputMeta,
            outputMeta = mergedOutputMeta(detectionMeta(info), geminiGenerationMeta(json)),
            apiMeta = geminiApiMeta(json),
            attachments = listOf(
                InferenceAttachment(
                    name = "input.jpg",
                    role = InferenceAttachment.Role.Input,
                    payload = InferenceAttachment.Payload.Data(imageData, "image/jpeg"),
                ),
            ),
            runId = runId,
        )

        return AnalysisResult(info, prettyPrinted(responseText), stats, inferenceId)
    }
}

internal fun isTransientGeminiStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode in 500..599
