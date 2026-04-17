package examples.tflite

import android.content.Context
import android.graphics.Bitmap
import dev.wildedge.sdk.Accelerator
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeImage
import dev.wildedge.sdk.integrations.decorate
import org.tensorflow.lite.GpuDelegate
import org.tensorflow.lite.Interpreter
import java.io.File

class TFLiteExample(context: Context) {

    private val wildEdge = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
        appVersion = "1.0.0"
    }

    private val modelFile = File(context.filesDir, "models/mobilenet_v3_int8.tflite")

    // modelId inferred as "mobilenet_v3_int8", quantization inferred as "int8"
    private val tracked = wildEdge.decorate(
        Interpreter(modelFile, Interpreter.Options().apply { numThreads = 4 }),
        modelFile,
    )

    // GPU delegate: pass Accelerator.GPU so inference events carry the correct hardware context
    private val trackedGpu = wildEdge.decorate(
        Interpreter(modelFile, Interpreter.Options().apply { addDelegate(GpuDelegate()) }),
        modelFile,
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
