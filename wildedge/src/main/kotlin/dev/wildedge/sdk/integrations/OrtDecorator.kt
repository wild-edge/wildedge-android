package dev.wildedge.sdk.integrations

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.ImageInputMeta
import java.io.File

/**
 * Wraps an ONNX Runtime [OrtSession] to automatically record inference metrics via [dev.wildedge.sdk.ModelHandle].
 *
 * Create via [WildEdgeClient.decorate] rather than constructing directly.
 */
class OrtDecorator(
    private val session: OrtSession,
    private val wildEdge: WildEdgeClient,
    modelId: String,
    modelVersion: String? = null,
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
    private val labels: List<String>? = null,
    private val numClasses: Int = labels?.size ?: 0,
) : AutoCloseable {

    val handle = wildEdge.registerModel(
        modelId,
        ModelInfo(
            modelName = modelId,
            modelVersion = modelVersion,
            modelSource = "local",
            modelFormat = "onnx",
            quantization = quantization,
        ),
    ).also { it.acceleratorActual = accelerator }

    private val outputModality = if (numClasses > 0) OutputModality.Classification else OutputModality.Tensor

    /** Runs inference on the given inputs, recording an inference event. */
    fun run(inputs: Map<String, OnnxTensor>, inputMeta: ImageInputMeta? = null): OrtSession.Result {
        var result: OrtSession.Result? = null
        return trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = outputModality,
            inputMeta = inputMeta?.toMap(),
            outputMetaProvider = { result?.let { ortOutputMeta(it, numClasses, labels) } },
        ) {
            session.run(inputs).also { result = it }
        }
    }

    /** Runs inference on the given inputs, fetching only the specified output names. */
    fun run(
        inputNames: Set<String>,
        inputs: Map<String, OnnxTensor>,
        inputMeta: ImageInputMeta? = null,
    ): OrtSession.Result {
        var result: OrtSession.Result? = null
        return trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = outputModality,
            inputMeta = inputMeta?.toMap(),
            outputMetaProvider = { result?.let { ortOutputMeta(it, numClasses, labels) } },
        ) {
            session.run(inputs, inputNames).also { result = it }
        }
    }

    val inputNames: Set<String> get() = session.inputNames
    val outputNames: Set<String> get() = session.outputNames

    /** Records an unload event and closes the underlying session. */
    override fun close() {
        handle.trackUnload()
        session.close()
    }
}

/** Creates an [OrtDecorator], inferring model metadata from [modelFile]. */
fun WildEdgeClient.decorate(
    session: OrtSession,
    modelFile: File,
    modelVersion: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
    labels: List<String>? = null,
    numClasses: Int = labels?.size ?: 0,
): OrtDecorator = OrtDecorator(
    session,
    this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
    labels = labels,
    numClasses = numClasses,
)

/** Creates an [OrtDecorator] with explicit model metadata. */
fun WildEdgeClient.decorate(
    session: OrtSession,
    modelId: String,
    modelVersion: String? = null,
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
    labels: List<String>? = null,
    numClasses: Int = labels?.size ?: 0,
): OrtDecorator = OrtDecorator(session, this, modelId, modelVersion, quantization, accelerator, labels, numClasses)
