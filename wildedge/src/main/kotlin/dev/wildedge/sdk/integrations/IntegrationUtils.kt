package dev.wildedge.sdk.integrations

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.events.ClassificationOutputMeta
import dev.wildedge.sdk.events.TopPrediction
import java.io.File
import kotlin.math.exp

internal fun inferModelId(file: File): String = file.nameWithoutExtension

internal fun inferQuantization(file: File): String? {
    val name = file.name.lowercase()
    return when {
        name.contains("int8") -> "int8"
        name.contains("int4") -> "int4"
        name.contains("float16") || name.contains("fp16") -> "f16"
        name.contains("float32") || name.contains("fp32") -> "f32"
        else -> null
    }
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> trackInferenceExecution(
    handle: ModelHandle,
    inputModality: InputModality,
    outputModality: OutputModality,
    inputMeta: Map<String, Any?>? = null,
    noinline outputMetaProvider: (() -> Map<String, Any?>?)? = null,
    block: () -> T,
): T {
    val start = System.currentTimeMillis()
    return try {
        val result = block()
        runCatching {
            handle.trackInference(
                durationMs = (System.currentTimeMillis() - start).toInt(),
                inputModality = inputModality,
                outputModality = outputModality,
                inputMeta = inputMeta,
                outputMeta = outputMetaProvider?.invoke(),
            )
        }
        result
    } catch (t: Throwable) {
        runCatching {
            handle.trackInference(
                durationMs = (System.currentTimeMillis() - start).toInt(),
                inputModality = inputModality,
                outputModality = outputModality,
                inputMeta = inputMeta,
                success = false,
                errorCode = t.toErrorCode(),
            )
        }
        throw t
    }
}

private const val TOP_K = 5
private const val BYTE_MAX = 255
private const val CONFIDENCE_SCALE = 10000

@Suppress("ReturnCount")
internal fun classificationOutputMeta(
    output: Any,
    numClasses: Int,
    labels: List<String>?,
): ClassificationOutputMeta? {
    if (numClasses <= 0) return null
    val first = (output as? Array<*>)?.takeIf { it.isNotEmpty() }?.get(0) ?: return null
    val probs: FloatArray = try {
        when (first) {
            is FloatArray -> softmax(first)
            is ByteArray -> FloatArray(first.size) { (first[it].toInt() and BYTE_MAX) / BYTE_MAX.toFloat() }
            else -> return null
        }
    } catch (_: Exception) {
        return null
    }
    if (probs.size != numClasses) return null
    val topIdx = probs.indices.sortedByDescending { probs[it] }.take(minOf(TOP_K, numClasses))
    return ClassificationOutputMeta(
        numPredictions = numClasses,
        topK = topIdx.map { i ->
            TopPrediction(
                label = labels?.getOrNull(i) ?: i.toString(),
                confidence = (probs[i] * CONFIDENCE_SCALE).toInt() / CONFIDENCE_SCALE.toFloat(),
            )
        },
        avgConfidence = topIdx.firstOrNull()
            ?.let { i -> (probs[i] * CONFIDENCE_SCALE).toInt() / CONFIDENCE_SCALE.toFloat() },
    )
}

@Suppress("TooGenericExceptionCaught")
internal fun ortOutputMeta(
    result: OrtSession.Result,
    numClasses: Int,
    labels: List<String>?,
): Map<String, Any?>? = try {
    val tensor = result.firstOrNull()?.value as? OnnxTensor ?: return null
    classificationOutputMeta(tensor.value, numClasses, labels)?.toMap()
} catch (_: Exception) {
    null
}

private fun softmax(logits: FloatArray): FloatArray {
    val max = logits.max()
    val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
    val sum = exps.sum()
    return FloatArray(exps.size) { exps[it] / sum }
}

private fun Throwable.toErrorCode(): String {
    val simple = javaClass.simpleName
    return if (simple.isNotBlank()) simple else javaClass.name
}
