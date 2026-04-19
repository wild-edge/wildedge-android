package dev.wildedge.sdk

import dev.wildedge.sdk.analysis.approximateBpeTokenCount
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TextInputMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Times a suspend inference block and emits a tracking event on completion or error.
 *
 * Usage:
 * ```
 * val result = handle.trackSuspendInference(
 *     inputModality = InputModality.Image,
 *     outputModality = OutputModality.Detection,
 * ) {
 *     withContext(Dispatchers.Default) { model.run(input) }
 * }
 * ```
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> ModelHandle.trackSuspendInference(
    inputModality: InputModality? = null,
    outputModality: OutputModality? = null,
    inputMeta: Map<String, Any?>? = null,
    outputMeta: Map<String, Any?>? = null,
    traceId: String? = null,
    parentSpanId: String? = null,
    block: suspend () -> T,
): T {
    val start = System.currentTimeMillis()
    return try {
        val result = block()
        trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = inputModality,
            outputModality = outputModality,
            inputMeta = inputMeta,
            outputMeta = outputMeta,
            traceId = traceId,
            parentSpanId = parentSpanId,
        )
        result
    } catch (e: Exception) {
        trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = inputModality,
            outputModality = outputModality,
            inputMeta = inputMeta,
            success = false,
            errorCode = e.javaClass.simpleName,
            traceId = traceId,
            parentSpanId = parentSpanId,
        )
        throw e
    }
}

/**
 * Wraps a streaming Flow<String> to capture generation metrics (duration, TTFT, token counts).
 * Emits a tracking event when the flow completes or errors.
 *
 * Usage:
 * ```
 * llmFlow.trackWith(handle, inputMeta = WildEdge.analyzeText(prompt))
 *     .collect { chunk -> append(chunk) }
 * ```
 */
@Suppress("TooGenericExceptionCaught")
fun Flow<String>.trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
    tokenizer: ((String) -> Int)? = null,
): Flow<String> = flow {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var charCount = 0
    val outputBuilder = if (tokenizer != null) StringBuilder() else null

    try {
        collect { chunk ->
            if (chunk.isNotEmpty()) {
                if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                charCount += chunk.length
                outputBuilder?.append(chunk)
            }
            emit(chunk)
        }

        val durationMs = (System.currentTimeMillis() - start).toInt()
        val tokensOut = tokenizer?.invoke(outputBuilder!!.toString())
            ?: approximateBpeTokenCount(charCount)
        handle.trackInference(
            durationMs = durationMs,
            inputModality = inputModality,
            outputModality = OutputModality.Generation,
            inputMeta = inputMeta?.toMap(),
            outputMeta = GenerationOutputMeta(
                tokensIn = inputMeta?.tokenCount,
                tokensOut = tokensOut,
                timeToFirstTokenMs = firstTokenAt?.let { (it - start).toInt() },
                tokensPerSecond = if (durationMs > 0) tokensOut * 1000f / durationMs else null,
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
