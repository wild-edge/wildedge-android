package dev.wildedge.sdk.integrations

import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.ImageInputMeta
import org.tensorflow.lite.InterpreterApi
import java.io.File

class PlayServicesTfliteDecorator(
    private val interpreter: InterpreterApi,
    wildEdge: WildEdge,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: String? = null,
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

    override fun close() {
        handle.trackUnload()
        interpreter.close()
    }
}

fun WildEdge.decorate(
    interpreter: InterpreterApi,
    modelFile: File,
    modelVersion: String = "unknown",
    accelerator: String? = null,
): PlayServicesTfliteDecorator = PlayServicesTfliteDecorator(
    interpreter, this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
)

fun WildEdge.decorate(
    interpreter: InterpreterApi,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: String? = null,
): PlayServicesTfliteDecorator = PlayServicesTfliteDecorator(
    interpreter, this, modelId, modelVersion, quantization, accelerator,
)
