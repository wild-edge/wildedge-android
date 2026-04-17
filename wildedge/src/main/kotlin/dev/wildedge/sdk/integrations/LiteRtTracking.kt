package dev.wildedge.sdk.integrations

import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.approximateBpeTokenCount
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
// No cast needed -- defined on the raw function type.
// inputModality defaults to Text; pass Multimodal when images or audio are also present.
// tokenizer: optional function that counts tokens in the full assembled output string.
//            Without it, token count is approximated from character count (~4 chars/token).
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

// For direct litertlm users who call conversation.sendMessageAsync() themselves.
// Wraps a MessageCallback to capture generation metrics.
// tokenizer: optional function that counts tokens in the full assembled output string.
//            Without it, token count is approximated from character count (~4 chars/token).
fun MessageCallback.trackWith(
    handle: ModelHandle,
    inputMeta: TextInputMeta? = null,
    inputModality: InputModality = InputModality.Text,
    tokenizer: ((String) -> Int)? = null,
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
