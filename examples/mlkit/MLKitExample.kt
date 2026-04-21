package examples.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeImage
import dev.wildedge.sdk.events.DetectionOutputMeta
import dev.wildedge.sdk.integrations.registerMlKitModel
import dev.wildedge.sdk.integrations.trackWith

// Assumes WildEdge.init() has already run (manifest meta-data or Application.onCreate()).
class MLKitExample {

    private val wildEdge = WildEdge.getInstance()

    private val handle = wildEdge.registerMlKitModel("face-detector", modelVersion = "16.1")

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
    }
}
