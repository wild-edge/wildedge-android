package dev.wildedge.sdk.integrations

import com.google.android.gms.tasks.Task
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.DetectionOutputMeta
import dev.wildedge.sdk.events.ImageInputMeta

/** Registers an ML Kit model and returns a [ModelHandle] for tracking its lifecycle. */
fun WildEdgeClient.registerMlKitModel(
    modelId: String,
    modelName: String = modelId,
    modelSource: String = "mlkit",
    modelVersion: String = "unknown",
): ModelHandle = registerModel(
    modelId,
    ModelInfo(
        modelName = modelName,
        modelVersion = modelVersion,
        modelSource = modelSource,
        modelFormat = "api",
    ),
)

/**
 * Adds inference tracking to a [Task] by recording an event on success or failure.
 *
 * Attach [inputMeta] from `WildEdge.analyzeImage()` to include image quality signals.
 * Provide [outputMetaProvider] to record top predictions and confidence scores.
 */
fun <T> Task<T>.trackWith(
    handle: ModelHandle,
    inputModality: InputModality = InputModality.Image,
    outputModality: OutputModality = OutputModality.Detection,
    inputMeta: ImageInputMeta? = null,
    outputMetaProvider: ((T) -> DetectionOutputMeta?)? = null,
): Task<T> {
    val start = System.currentTimeMillis()
    return addOnSuccessListener { result ->
        val durationMs = (System.currentTimeMillis() - start).toInt()
        val outputMeta = result?.let { outputMetaProvider?.invoke(it) }
        handle.trackInference(
            durationMs = durationMs,
            inputModality = inputModality,
            outputModality = outputModality,
            inputMeta = inputMeta?.toMap(),
            outputMeta = outputMeta?.toMap(),
        )
    }.addOnFailureListener { e ->
        val durationMs = (System.currentTimeMillis() - start).toInt()
        handle.trackInference(
            durationMs = durationMs,
            inputModality = inputModality,
            outputModality = outputModality,
            inputMeta = inputMeta?.toMap(),
            success = false,
            errorCode = e.javaClass.simpleName,
        )
    }
}
