package dev.wildedge.sdk.integrations

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.ImageInputMeta
import java.io.File

class OrtDecorator(
    private val session: OrtSession,
    private val wildEdge: WildEdgeClient,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
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

    fun run(inputs: Map<String, OnnxTensor>, inputMeta: ImageInputMeta? = null): OrtSession.Result {
        return trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = OutputModality.Tensor,
            inputMeta = inputMeta?.toMap()
        ) {
            session.run(inputs)
        }
    }

    fun run(
        inputNames: Set<String>,
        inputs: Map<String, OnnxTensor>,
        inputMeta: ImageInputMeta? = null,
    ): OrtSession.Result {
        return trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = OutputModality.Tensor,
            inputMeta = inputMeta?.toMap()
        ) {
            session.run(inputs, inputNames)
        }
    }

    val inputNames: Set<String> get() = session.inputNames
    val outputNames: Set<String> get() = session.outputNames

    override fun close() {
        handle.trackUnload()
        session.close()
    }
}

fun WildEdgeClient.decorate(
    session: OrtSession,
    modelFile: File,
    modelVersion: String = "unknown",
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): OrtDecorator = OrtDecorator(
    session, this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
)

fun WildEdgeClient.decorate(
    session: OrtSession,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): OrtDecorator = OrtDecorator(session, this, modelId, modelVersion, quantization, accelerator)
