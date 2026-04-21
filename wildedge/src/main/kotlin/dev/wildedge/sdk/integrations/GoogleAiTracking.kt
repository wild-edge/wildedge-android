package dev.wildedge.sdk.integrations

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.analysis.approximateBpeTokenCount
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TextInputMeta
import dev.wildedge.sdk.trackSuspendInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Suppress("TooGenericExceptionCaught")
internal fun Flow<GenerateContentResponse>.trackWith(
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

internal suspend fun GenerativeModel.generateContentTracked(
    handle: ModelHandle,
    prompt: String,
    inputMeta: TextInputMeta? = null,
): GenerateContentResponse = handle.trackSuspendInference(
    inputModality = InputModality.Text,
    outputModality = OutputModality.Generation,
    inputMeta = inputMeta?.toMap(),
    outputMetaExtractor = { r -> r.generationOutputMeta(inputMeta?.tokenCount) },
) { generateContent(prompt) }

internal suspend fun GenerativeModel.generateContentTracked(
    handle: ModelHandle,
    vararg prompt: Content,
    inputMeta: TextInputMeta? = null,
): GenerateContentResponse = handle.trackSuspendInference(
    inputModality = InputModality.Text,
    outputModality = OutputModality.Generation,
    inputMeta = inputMeta?.toMap(),
    outputMetaExtractor = { r -> r.generationOutputMeta(inputMeta?.tokenCount) },
) { generateContent(*prompt) }

internal suspend fun GenerativeModel.generateContentTracked(
    handle: ModelHandle,
    prompt: Bitmap,
): GenerateContentResponse = handle.trackSuspendInference(
    inputModality = InputModality.Image,
    outputModality = OutputModality.Generation,
    outputMetaExtractor = { r -> r.generationOutputMeta(tokensInFallback = null) },
) { generateContent(prompt) }

private fun GenerateContentResponse.generationOutputMeta(tokensInFallback: Int?): Map<String, Any?> {
    val meta = usageMetadata
    return GenerationOutputMeta(
        tokensIn = meta?.promptTokenCount ?: tokensInFallback,
        tokensOut = meta?.candidatesTokenCount ?: approximateBpeTokenCount(text?.length ?: 0),
    ).toMap()
}
