package examples.mlkit

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeImage
import dev.wildedge.sdk.events.DetectionOutputMeta
import dev.wildedge.sdk.integrations.registerMlKitModel
import dev.wildedge.sdk.integrations.trackWith

class MLKitExample(context: Context) {

    private val wildEdge = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
        appVersion = "1.0.0"
    }

    private val handle = wildEdge.registerMlKitModel("face-detector")

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    fun detect(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image).trackWith(
            handle = handle,
            inputMeta = WildEdge.analyzeImage(bitmap),
        ) { faces ->
            DetectionOutputMeta(numPredictions = faces.size)
        }
    }

    fun close() {
        detector.close()
        wildEdge.close()
    }
}
