package dev.wildedge.sdk.integrations

import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.ImageInputMeta
import org.tensorflow.lite.InterpreterApi
import java.io.File

/**
 * Wraps a Play Services TFLite [InterpreterApi] to automatically record inference metrics
 * via [dev.wildedge.sdk.ModelHandle].
 *
 * Create via [WildEdgeClient.decorate] rather than constructing directly.
 */
class PlayServicesTfliteDecorator(
    private val interpreter: InterpreterApi,
    wildEdge: WildEdgeClient,
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

    /** Runs inference on a single input tensor, recording an inference event. */
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

    /** Runs inference on multiple input/output tensors, recording an inference event. */
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

    /** Records an unload event and closes the underlying interpreter. */
    override fun close() {
        handle.trackUnload()
        interpreter.close()
    }
}

/** Creates a [PlayServicesTfliteDecorator], inferring model metadata from [modelFile]. */
fun WildEdgeClient.decorate(
    interpreter: InterpreterApi,
    modelFile: File,
    modelVersion: String = "unknown",
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): PlayServicesTfliteDecorator = PlayServicesTfliteDecorator(
    interpreter,
    this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
)

/** Creates a [PlayServicesTfliteDecorator] with explicit model metadata. */
fun WildEdgeClient.decorate(
    interpreter: InterpreterApi,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): PlayServicesTfliteDecorator = PlayServicesTfliteDecorator(
    interpreter,
    this,
    modelId,
    modelVersion,
    quantization,
    accelerator,
)
