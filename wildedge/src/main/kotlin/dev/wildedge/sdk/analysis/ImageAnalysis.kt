package dev.wildedge.sdk.analysis

import android.graphics.Bitmap
import android.graphics.Color
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.ImageInputMeta
import kotlin.math.sqrt

// Analyzes a Bitmap and returns ImageInputMeta.
// sampleStep skips pixels for performance — step=4 samples ~6% of a 1080p image.
fun WildEdge.Companion.analyzeImage(
    bitmap: Bitmap,
    sampleStep: Int = 4,
): ImageInputMeta {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    fun luminanceAt(x: Int, y: Int): Float {
        val p = pixels[y * width + x]
        val r = (p shr 16 and 0xFF) / 255f
        val g = (p shr 8 and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    val luminances = mutableListOf<Float>()
    val saturations = mutableListOf<Float>()
    for (y in 0 until height step sampleStep) {
        for (x in 0 until width step sampleStep) {
            luminances.add(luminanceAt(x, y))
            val hsv = FloatArray(3)
            Color.colorToHSV(pixels[y * width + x], hsv)
            saturations.add(hsv[1])
        }
    }

    val brightnessMean = luminances.average().toFloat()
    val brightnessStddev = luminances.stddev(brightnessMean)

    val buckets = IntArray(8)
    for (l in luminances) buckets[minOf((l * 8).toInt(), 7)]++

    // Laplacian variance — proxy for sharpness. High = sharp, low = blurry.
    val blurScore = if (width > 2 * sampleStep && height > 2 * sampleStep) {
        val lapValues = mutableListOf<Float>()
        for (y in sampleStep until height - sampleStep step sampleStep) {
            for (x in sampleStep until width - sampleStep step sampleStep) {
                val lap = 4 * luminanceAt(x, y) -
                    luminanceAt(x, y - sampleStep) -
                    luminanceAt(x, y + sampleStep) -
                    luminanceAt(x - sampleStep, y) -
                    luminanceAt(x + sampleStep, y)
                lapValues.add(lap)
            }
        }
        lapValues.variance()
    } else null

    // Variance of neighbor differences — high = noisy.
    val noiseScore = if (width > sampleStep && height > sampleStep) {
        val diffs = mutableListOf<Float>()
        for (y in 0 until height - sampleStep step sampleStep) {
            for (x in 0 until width - sampleStep step sampleStep) {
                diffs.add(luminanceAt(x, y) - luminanceAt(x + sampleStep, y))
                diffs.add(luminanceAt(x, y) - luminanceAt(x, y + sampleStep))
            }
        }
        diffs.variance()
    } else null

    return ImageInputMeta(
        width = width,
        height = height,
        channels = when (bitmap.config) {
            Bitmap.Config.ARGB_8888, Bitmap.Config.ARGB_4444 -> 4
            Bitmap.Config.RGB_565 -> 3
            Bitmap.Config.ALPHA_8 -> 1
            else -> null
        },
        format = bitmap.config?.name,
        brightnessMean = brightnessMean,
        brightnessStddev = brightnessStddev,
        brightnessBuckets = buckets.toList(),
        contrast = brightnessStddev,
        saturationMean = saturations.average().toFloat(),
        blurScore = blurScore,
        noiseScore = noiseScore,
    )
}

private fun List<Float>.average() = sum() / size

private fun List<Float>.variance(): Float {
    val mean = average()
    return map { (it - mean) * (it - mean) }.average()
}

private fun List<Float>.stddev(mean: Float): Float =
    sqrt(map { (it - mean) * (it - mean) }.average())
