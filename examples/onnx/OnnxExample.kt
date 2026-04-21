package examples.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeImage
import dev.wildedge.sdk.integrations.decorate
import java.io.File

// Assumes WildEdge.init() has already run (manifest meta-data or Application.onCreate()).
class OnnxExample(context: Context) {

    private val wildEdge = WildEdge.getInstance()

    private val modelFile = File(context.filesDir, "models/face_detector_int8.onnx")

    private val session = OrtEnvironment.getEnvironment().createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions(),
    )

    // modelId inferred as "face_detector_int8", quantization inferred as "int8"
    // pass Accelerator.NNAPI if SessionOptions were configured with NNAPIExecutionProvider
    private val tracked = wildEdge.decorate(session, modelFile, modelVersion = "1.0")

    fun run(bitmap: Bitmap, inputs: Map<String, OnnxTensor>) = tracked.run(
        inputs = inputs,
        inputMeta = WildEdge.analyzeImage(bitmap),
    )

    fun close() {
        tracked.close()
    }
}
