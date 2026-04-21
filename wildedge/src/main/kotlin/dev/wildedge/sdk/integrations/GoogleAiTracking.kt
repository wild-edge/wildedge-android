package dev.wildedge.sdk.integrations

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.approximateBpeTokenCount
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TextInputMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Registers a Google AI (Gemini) model and returns a [ModelHandle] for tracking.
 *
 * Pass a [modelId] matching the model name passed to [GenerativeModel] (e.g. `"gemini-2.0-flash"`).
 */
fun WildEdgeClient.registerGoogleAiModel(
    modelId: String,
    modelName: String = modelId,
    modelVersion: String = "unknown",
    modelFamily: String? = "gemini",
): ModelHandle = registerModel(
    modelId,
    ModelInfo(
        modelName = modelName,
        modelVersion = modelVersion,
        modelSource = "api",
        modelFormat = "api",
        modelFamily = modelFamily,
        inputModality = InputModality.Text,
        outputModality = OutputModality.Generation,
    ),
)

/**
 * Wraps a [Flow]<[GenerateContentResponse]> to track generation metrics.
 *
 * Token counts are read from [GenerateContentResponse.usageMetadata] on the final chunk,
 * with a char-based estimate as fallback. A tracking event is emitted on completion or error.
 *
 * Usage:
 * ```
 * model.generateContentStream(prompt)
 *     .trackWith(handle, inputMeta = WildEdge.analyzeText(prompt))
 *     .collect { response -> append(response.text.orEmpty()) }
 * ```
 */
@Suppress("TooGenericExceptionCaught")
fun Flow<GenerateContentResponse>.trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
): Flow<GenerateContentResponse> = flow {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var charCount = 0
    var tokensIn: Int? = null
    var tokensOut: Int? = null

    try {
        collect { response ->
            val chunk = response.text.orEmpty()
            if (chunk.isNotEmpty() && firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
            charCount += chunk.length
            // usageMetadata is typically populated only on the final chunk
            response.usageMetadata?.let { meta ->
                if (meta.promptTokenCount > 0) tokensIn = meta.promptTokenCount
                if (meta.candidatesTokenCount > 0) tokensOut = meta.candidatesTokenCount
            }
            emit(response)
        }
        val durationMs = (System.currentTimeMillis() - start).toInt()
        val outTokens = tokensOut ?: approximateBpeTokenCount(charCount)
        handle.trackInference(
            durationMs = durationMs,
            inputModality = inputModality,
            outputModality = OutputModality.Generation,
            inputMeta = inputMeta?.toMap(),
            outputMeta = GenerationOutputMeta(
                tokensIn = tokensIn ?: inputMeta?.tokenCount,
                tokensOut = outTokens,
                timeToFirstTokenMs = firstTokenAt?.let { (it - start).toInt() },
                tokensPerSecond = if (durationMs > 0 && outTokens > 0) outTokens * 1000f / durationMs else null,
            ).toMap(),
        )
    } catch (e: Exception) {
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = inputModality,
            outputModality = OutputModality.Generation,
            success = false,
            errorCode = e.javaClass.simpleName,
        )
        throw e
    }
}

/**
 * Calls [GenerativeModel.generateContent] and tracks the inference event.
 *
 * Token counts are read from [GenerateContentResponse.usageMetadata].
 *
 * Usage:
 * ```
 * val response = model.generateContentTracked(
 *     handle = handle,
 *     prompt = "Summarize this article: ...",
 *     inputMeta = WildEdge.analyzeText(prompt),
 * )
 * ```
 */
@Suppress("TooGenericExceptionCaught")
suspend fun GenerativeModel.generateContentTracked(
    handle: ModelHandle,
    prompt: String,
    inputMeta: TextInputMeta? = null,
): GenerateContentResponse {
    val start = System.currentTimeMillis()
    return try {
        val response = generateContent(prompt)
        val meta = response.usageMetadata
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
            inputMeta = inputMeta?.toMap(),
            outputMeta = GenerationOutputMeta(
                tokensIn = meta?.promptTokenCount ?: inputMeta?.tokenCount,
                tokensOut = meta?.candidatesTokenCount
                    ?: approximateBpeTokenCount(response.text?.length ?: 0),
            ).toMap(),
        )
        response
    } catch (e: Exception) {
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
            success = false,
            errorCode = e.javaClass.simpleName,
        )
        throw e
    }
}
