package dev.wildedge.sdk.integrations

import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.ImageInputMeta
import org.tensorflow.lite.Interpreter
import java.io.File

class TFLiteDecorator(
    private val interpreter: Interpreter,
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
            modelFormat = "tflite",
            quantization = quantization,
        ),
    ).also { it.acceleratorActual = accelerator }

    fun run(input: Any, output: Any, inputMeta: ImageInputMeta? = null) {
        trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = OutputModality.Tensor,
            inputMeta = inputMeta?.toMap()
        ) {
            interpreter.run(input, output)
        }
    }

    fun runForMultipleInputsOutputs(
        inputs: Array<Any>,
        outputs: Map<Int, Any>,
        inputMeta: ImageInputMeta? = null,
    ) {
        trackInferenceExecution(
            handle = handle,
            inputModality = if (inputMeta != null) InputModality.Image else InputModality.Tensor,
            outputModality = OutputModality.Tensor,
            inputMeta = inputMeta?.toMap()
        ) {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        }
    }

    fun getInputTensor(index: Int) = interpreter.getInputTensor(index)
    fun getOutputTensor(index: Int) = interpreter.getOutputTensor(index)
    val inputTensorCount: Int get() = interpreter.inputTensorCount
    val outputTensorCount: Int get() = interpreter.outputTensorCount

    override fun close() {
        handle.trackUnload()
        interpreter.close()
    }
}

// Infers modelId and quantization from the model file name.
fun WildEdgeClient.decorate(
    interpreter: Interpreter,
    modelFile: File,
    modelVersion: String = "unknown",
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): TFLiteDecorator = TFLiteDecorator(
    interpreter, this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
)

// Explicit control over all metadata.
fun WildEdgeClient.decorate(
    interpreter: Interpreter,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): TFLiteDecorator = TFLiteDecorator(interpreter, this, modelId, modelVersion, quantization, accelerator)
