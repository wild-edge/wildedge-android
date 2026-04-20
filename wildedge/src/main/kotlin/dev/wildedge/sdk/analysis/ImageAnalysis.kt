package dev.wildedge.sdk.analysis

import android.graphics.Bitmap
import android.graphics.Color
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.ImageInputMeta
import kotlin.math.sqrt

// ITU-R BT.709 luminance coefficients
private const val LUMINANCE_RED = 0.2126f
private const val LUMINANCE_GREEN = 0.7152f
private const val LUMINANCE_BLUE = 0.0722f

private const val BRIGHTNESS_BUCKET_COUNT = 8
private const val LAPLACIAN_CENTER = 4f

private const val CHANNELS_ARGB = 4
private const val CHANNELS_RGB = 3
private const val CHANNELS_ALPHA = 1

private const val PIXEL_RED_SHIFT = 16
private const val PIXEL_GREEN_SHIFT = 8
private const val PIXEL_CHANNEL_MASK = 0xFF
private const val PIXEL_CHANNEL_MAX = 255f
private const val HSV_COMPONENT_COUNT = 3

/**
 * Analyzes a [Bitmap] and returns [ImageInputMeta] for use with [dev.wildedge.sdk.ModelHandle.trackInference].
 *
 * @param bitmap Input bitmap to analyze.
 * @param sampleStep Pixel sampling step for performance; step=4 samples ~6% of a 1080p image.
 */
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
        val r = (p shr PIXEL_RED_SHIFT and PIXEL_CHANNEL_MASK) / PIXEL_CHANNEL_MAX
        val g = (p shr PIXEL_GREEN_SHIFT and PIXEL_CHANNEL_MASK) / PIXEL_CHANNEL_MAX
        val b = (p and PIXEL_CHANNEL_MASK) / PIXEL_CHANNEL_MAX
        return LUMINANCE_RED * r + LUMINANCE_GREEN * g + LUMINANCE_BLUE * b
    }

    val luminances = mutableListOf<Float>()
    val saturations = mutableListOf<Float>()
    for (y in 0 until height step sampleStep) {
        for (x in 0 until width step sampleStep) {
            luminances.add(luminanceAt(x, y))
            val hsv = FloatArray(HSV_COMPONENT_COUNT)
            Color.colorToHSV(pixels[y * width + x], hsv)
            saturations.add(hsv[1])
        }
    }

    val brightnessMean = luminances.average()
    val brightnessStddev = luminances.stddev(brightnessMean)

    val buckets = IntArray(BRIGHTNESS_BUCKET_COUNT)
    for (l in luminances) buckets[minOf((l * BRIGHTNESS_BUCKET_COUNT).toInt(), BRIGHTNESS_BUCKET_COUNT - 1)]++

    // Laplacian variance: proxy for sharpness. High = sharp, low = blurry.
    val blurScore = if (width > 2 * sampleStep && height > 2 * sampleStep) {
        val lapValues = mutableListOf<Float>()
        for (y in sampleStep until height - sampleStep step sampleStep) {
            for (x in sampleStep until width - sampleStep step sampleStep) {
                val lap = LAPLACIAN_CENTER * luminanceAt(x, y) -
                    luminanceAt(x, y - sampleStep) -
                    luminanceAt(x, y + sampleStep) -
                    luminanceAt(x - sampleStep, y) -
                    luminanceAt(x + sampleStep, y)
                lapValues.add(lap)
            }
        }
        lapValues.variance()
    } else {
        null
    }

    // Variance of neighbor differences, high = noisy.
    val noiseScore = if (width > sampleStep && height > sampleStep) {
        val diffs = mutableListOf<Float>()
        for (y in 0 until height - sampleStep step sampleStep) {
            for (x in 0 until width - sampleStep step sampleStep) {
                diffs.add(luminanceAt(x, y) - luminanceAt(x + sampleStep, y))
                diffs.add(luminanceAt(x, y) - luminanceAt(x, y + sampleStep))
            }
        }
        diffs.variance()
    } else {
        null
    }

    return ImageInputMeta(
        width = width,
        height = height,
        channels = when (bitmap.config) {
            Bitmap.Config.ARGB_8888, Bitmap.Config.ARGB_4444 -> CHANNELS_ARGB
            Bitmap.Config.RGB_565 -> CHANNELS_RGB
            Bitmap.Config.ALPHA_8 -> CHANNELS_ALPHA
            else -> null
        },
        format = bitmap.config?.name,
        brightnessMean = brightnessMean,
        brightnessStddev = brightnessStddev,
        brightnessBuckets = buckets.toList(),
        contrast = brightnessStddev,
        saturationMean = saturations.average(),
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
