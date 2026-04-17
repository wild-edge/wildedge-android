package examples.tflite

import android.content.Context
import android.graphics.Bitmap
import dev.wildedge.sdk.Accelerator
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.analyzeImage
import dev.wildedge.sdk.integrations.decorate
import org.tensorflow.lite.Interpreter
import java.io.File

class TFLiteExample(context: Context) {

    private val wildEdge: WildEdgeClient = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
    }

    private val modelFile = File(context.filesDir, "models/mobilenet_v3_int8.tflite")

    // modelId inferred as "mobilenet_v3_int8", quantization inferred as "int8"
    private val tracked = wildEdge.decorate(
        Interpreter(modelFile, Interpreter.Options().apply { numThreads = 4 }),
        modelFile,
        modelVersion = "3.0",
    )

    // Pass Accelerator.GPU when using a GPU delegate so inference events carry the correct context.
    // Wire up your delegate of choice (GpuDelegate, NnApiDelegate, etc.) via Interpreter.Options,
    // then pass the matching Accelerator constant here.
    private val trackedGpu = wildEdge.decorate(
        Interpreter(modelFile, Interpreter.Options().apply { numThreads = 1 }),
        modelFile,
        modelVersion = "3.0",
        accelerator = Accelerator.GPU,
    )

    fun classify(bitmap: Bitmap, inputBuffer: Any, outputBuffer: Any) {
        tracked.run(
            input = inputBuffer,
            output = outputBuffer,
            inputMeta = WildEdge.analyzeImage(bitmap),
        )
    }

    fun close() {
        tracked.close()
        trackedGpu.close()
        wildEdge.close()
    }
}
