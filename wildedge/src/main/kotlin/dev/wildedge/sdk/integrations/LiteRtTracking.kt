package dev.wildedge.sdk.integrations

import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.TextInputMeta

fun WildEdge.registerLiteRtModel(
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
    ),
)

// Wraps a streaming result listener (gallery's ResultListener or any matching lambda).
// No cast needed — defined on the raw function type.
// inputModality defaults to Text; pass Multimodal when images or audio are also present.
fun ((String, Boolean, String?) -> Unit).trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
): (String, Boolean, String?) -> Unit {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var tokenCount = 0

    return { partialResult, done, thinkingResult ->
        if (!done && partialResult.isNotEmpty()) {
            if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
            tokenCount += partialResult.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        }

        if (done) {
            val durationMs = (System.currentTimeMillis() - start).toInt()
            handle.trackInference(
                durationMs = durationMs,
                inputModality = inputModality,
                outputModality = OutputModality.Generation,
                inputMeta = inputMeta?.toMap(),
                outputMeta = GenerationOutputMeta(
                    tokensIn = inputMeta?.tokenCount,
                    tokensOut = tokenCount,
                    timeToFirstTokenMs = firstTokenAt?.let { (it - start).toInt() },
                    tokensPerSecond = if (durationMs > 0) tokenCount * 1000f / durationMs else null,
                ).toMap(),
            )
        }

        this(partialResult, done, thinkingResult)
    }
}

// For direct litertlm users who call conversation.sendMessageAsync() themselves.
// Wraps a MessageCallback to capture generation metrics.
fun MessageCallback.trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
): MessageCallback {
    val start = System.currentTimeMillis()
    var firstTokenAt: Long? = null
    var tokenCount = 0
    val delegate = this

    return object : MessageCallback {
        override fun onMessage(message: Message) {
            val text = message.toString()
            if (text.isNotEmpty()) {
                if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                tokenCount += text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            }
            delegate.onMessage(message)
        }

        override fun onDone() {
            val durationMs = (System.currentTimeMillis() - start).toInt()
            handle.trackInference(
                durationMs = durationMs,
                inputModality = inputModality,
                outputModality = OutputModality.Generation,
                inputMeta = inputMeta?.toMap(),
                outputMeta = GenerationOutputMeta(
                    tokensIn = inputMeta?.tokenCount,
                    tokensOut = tokenCount,
                    timeToFirstTokenMs = firstTokenAt?.let { (it - start).toInt() },
                    tokensPerSecond = if (durationMs > 0) tokenCount * 1000f / durationMs else null,
                ).toMap(),
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
