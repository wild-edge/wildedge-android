package dev.wildedge.sample.carscanner

import android.graphics.Bitmap
import dev.wildedge.sdk.FeedbackType
import java.util.UUID

const val CAR_PROMPT = """
Analyze this image. If a car is visible, return the top 3 most likely candidates as ONLY valid JSON - no other text:
{"found": true, "candidates": [
  {"brand": "Toyota", "model": "Camry", "color": "Silver", "year": "2019", "confidence": 92, "bbox": {"x": 0.1, "y": 0.15, "width": 0.8, "height": 0.65}},
  {"brand": "Honda", "model": "Accord", "color": "Gray", "year": "2018", "confidence": 75, "bbox": {"x": 0.1, "y": 0.15, "width": 0.8, "height": 0.65}},
  {"brand": "Nissan", "model": "Altima", "color": "Silver", "year": "2017", "confidence": 55, "bbox": {"x": 0.1, "y": 0.15, "width": 0.8, "height": 0.65}}
]}
confidence is an integer 0-100. Order by confidence descending.
bbox contains normalized coordinates (0.0-1.0): x and y are the top-left corner, width and height are the span of the bounding box around the car.
If no car is found, return exactly: {"found": false, "candidates": []}
"""

enum class RecognitionProvider(val label: String) {
    OpenRouter("OpenRouter"),
    Gemini("Gemini"),
    Both("Both"),
}

enum class CaptureFilter(val label: String) {
    LensSmudge("Lens smudge"),
    Rain("Rain"),
    Fog("Fog"),
    LowLight("Low light"),
    Defocus("Defocus"),
    ImagePipeline("Image pipeline degradation"),
}

enum class FilterStrength(val label: String) {
    Low("Low"),
    Medium("Medium"),
    High("High"),
}

data class FilterIncident(
    val filter: CaptureFilter,
    val strength: FilterStrength,
    val remainingCaptures: Int,
)

data class AppliedFilter(
    val filter: CaptureFilter,
    val strength: FilterStrength,
)

data class PhotoMetadata(val fileSizeBytes: Int, val width: Int?, val height: Int?) {
    val fileSizeFormatted: String get() = formatBytes(fileSizeBytes)
}

fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

data class ScanResult(
    val provider: String,
    val info: CarInfo,
    val photo: PhotoMetadata,
    val rawJson: String,
    val httpStats: HttpStats,
    val inferenceId: String,
    val sendFeedback: (FeedbackType) -> Unit,
)

sealed class ScanJobStatus {
    object Scanning : ScanJobStatus()
    data class Completed(val results: List<ScanResult>) : ScanJobStatus()
    data class Failed(val message: String) : ScanJobStatus()
}

data class ScanJob(
    val id: UUID = UUID.randomUUID(),
    val createdAt: Long = System.currentTimeMillis(),
    val provider: RecognitionProvider,
    var thumbnail: Bitmap? = null,
    var imageBytes: ByteArray? = null,
    var photoMetadata: PhotoMetadata? = null,
    var status: ScanJobStatus = ScanJobStatus.Scanning,
)
