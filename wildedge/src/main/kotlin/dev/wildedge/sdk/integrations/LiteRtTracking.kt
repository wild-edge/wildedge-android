package dev.wildedge.sdk.integrations

import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.approximateBpeTokenCount
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TextInputMeta

/** Registers a LiteRT model and returns a [ModelHandle] for tracking its lifecycle. */
fun WildEdgeClient.registerLiteRtModel(
    modelId: String,
    modelName: String = modelId,
    modelVersion: String = "unknown",
    quantization: String? = null,
): ModelHandle = registerModel(
    modelId,
    ModelInfo(
        modelName = modelName,
        modelVersion = modelVersion,
        modelSource = "local",
        modelFormat = "litertlm",
        quantization = quantization,
        inputModality = InputModality.Text,
        outputModality = OutputModality.Generation,
    ),
)

/**
 * Wraps a streaming result listener `(String, Boolean, String?) -> Unit` to capture generation metrics.
 *
 * Pass [inputMeta] from `WildEdge.analyzeText()` to record token counts and language.
 * Set [inputModality] to [InputModality.Multimodal] when images or audio are also included.
 */
fun ((String, Boolean, String?) -> Unit).trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
    tokenizer: ((String) -> Int)? = null,
): (String, Boolean, String?) -> Unit {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var charCount = 0
    val outputBuilder = if (tokenizer != null) StringBuilder() else null

    return { partialResult, done, thinkingResult ->
        if (!done && partialResult.isNotEmpty()) {
            if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
            charCount += partialResult.length
            outputBuilder?.append(partialResult)
        }

        if (done) {
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
        }

        this(partialResult, done, thinkingResult)
    }
}

/**
 * Wraps a [MessageCallback] to capture generation metrics on completion or error.
 *
 * Pass [inputMeta] from `WildEdge.analyzeText()` to record token counts and language.
 */
fun MessageCallback.trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
    tokenizer: ((String) -> Int)? = null,
    runId: String? = null,
    agentId: String? = null,
): MessageCallback {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var charCount = 0
    val outputBuilder = if (tokenizer != null) StringBuilder() else null
    val delegate = this

    return object : MessageCallback {
        override fun onMessage(message: Message) {
            val text = message.toString()
            if (text.isNotEmpty()) {
                if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                charCount += text.length
                outputBuilder?.append(text)
            }
            delegate.onMessage(message)
        }

        override fun onDone() {
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
                runId = runId,
                agentId = agentId,
            )
            delegate.onDone()
        }

        override fun onError(throwable: Throwable) {
            val durationMs = (System.currentTimeMillis() - start).toInt()
            handle.trackInference(
                durationMs = durationMs,
                inputModality = inputModality,
                outputModality = OutputModality.Generation,
                success = false,
                errorCode = throwable.javaClass.simpleName,
            )
            delegate.onError(throwable)
        }
    }
}
