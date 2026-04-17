package dev.wildedge.sdk.integrations

import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.OutputModality
import java.io.File

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

internal inline fun <T> trackInferenceExecution(
    handle: ModelHandle,
    inputModality: InputModality,
    outputModality: OutputModality,
    inputMeta: Map<String, Any?>? = null,
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

private fun Throwable.toErrorCode(): String {
    val simple = javaClass.simpleName
    return if (simple.isNotBlank()) simple else javaClass.name
}
