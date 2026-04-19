package dev.wildedge.sdk.integrations

import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.ImageInputMeta
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * Wraps a TFLite [Interpreter] to automatically record inference metrics via [dev.wildedge.sdk.ModelHandle].
 *
 * Create via [WildEdgeClient.decorate] rather than constructing directly.
 */
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

    /** Returns the input tensor at [index]. */
    fun getInputTensor(index: Int) = interpreter.getInputTensor(index)

    /** Returns the output tensor at [index]. */
    fun getOutputTensor(index: Int) = interpreter.getOutputTensor(index)

    val inputTensorCount: Int get() = interpreter.inputTensorCount
    val outputTensorCount: Int get() = interpreter.outputTensorCount

    /** Records an unload event and closes the underlying interpreter. */
    override fun close() {
        handle.trackUnload()
        interpreter.close()
    }
}

/** Creates a [TFLiteDecorator], inferring model metadata from [modelFile]. */
fun WildEdgeClient.decorate(
    interpreter: Interpreter,
    modelFile: File,
    modelVersion: String = "unknown",
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): TFLiteDecorator = TFLiteDecorator(
    interpreter,
    this,
    modelId = inferModelId(modelFile),
    modelVersion = modelVersion,
    quantization = inferQuantization(modelFile),
    accelerator = accelerator,
)

/** Creates a [TFLiteDecorator] with explicit model metadata. */
fun WildEdgeClient.decorate(
    interpreter: Interpreter,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): TFLiteDecorator = TFLiteDecorator(interpreter, this, modelId, modelVersion, quantization, accelerator)
