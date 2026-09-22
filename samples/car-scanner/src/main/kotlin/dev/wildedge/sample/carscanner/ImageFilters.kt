package dev.wildedge.sample.carscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.random.Random

fun applyCaptureFilter(source: Bitmap, applied: AppliedFilter?, seed: Int): Bitmap {
    if (applied == null) return source
    return when (applied.filter) {
        CaptureFilter.LensSmudge -> applyLensSmudge(source, applied.strength, seed)
        CaptureFilter.Rain -> applyRain(source, applied.strength, seed)
        CaptureFilter.Fog -> applyFog(source, applied.strength)
        CaptureFilter.LowLight -> applyLowLight(source, applied.strength, seed)
        CaptureFilter.Defocus -> applyDefocus(source, applied.strength)
        CaptureFilter.ImagePipeline -> applyImagePipelineDegradation(source, applied.strength)
    }
}

private fun applyLensSmudge(source: Bitmap, strength: FilterStrength, seed: Int): Bitmap {
    val scale = when (strength) {
        FilterStrength.Low -> 0.65f
        FilterStrength.Medium -> 0.4f
        FilterStrength.High -> 0.22f
    }
    val blurred = blurByScaling(source, scale)
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val basePixels = IntArray(source.width * source.height)
    val blurredPixels = IntArray(basePixels.size)
    output.getPixels(basePixels, 0, source.width, 0, 0, source.width, source.height)
    blurred.getPixels(blurredPixels, 0, source.width, 0, 0, source.width, source.height)

    val random = Random(seed)
    val patchCount = when (strength) {
        FilterStrength.Low -> 1
        FilterStrength.Medium -> 2
        FilterStrength.High -> 3
    }
    val blend = when (strength) {
        FilterStrength.Low -> 0.42f
        FilterStrength.Medium -> 0.62f
        FilterStrength.High -> 0.78f
    }
    repeat(patchCount) {
        val centerX = random.nextFloat() * source.width
        val centerY = random.nextFloat() * source.height
        val radiusX = source.width * (0.18f + random.nextFloat() * 0.16f)
        val radiusY = source.height * (0.12f + random.nextFloat() * 0.18f)
        val minX = max(0, (centerX - radiusX).toInt())
        val maxX = minOf(source.width - 1, (centerX + radiusX).toInt())
        val minY = max(0, (centerY - radiusY).toInt())
        val maxY = minOf(source.height - 1, (centerY + radiusY).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = dx * dx + dy * dy
                if (distance >= 1f) continue
                val featheredBlend = blend * (1f - distance).coerceIn(0f, 1f)
                val index = y * source.width + x
                basePixels[index] = blendColors(
                    basePixels[index],
                    lighten(blurredPixels[index], 0.08f),
                    featheredBlend,
                )
            }
        }
    }
    output.setPixels(basePixels, 0, source.width, 0, 0, source.width, source.height)
    return output
}

private fun applyRain(source: Bitmap, strength: FilterStrength, seed: Int): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val random = Random(seed)
    val count = when (strength) {
        FilterStrength.Low -> 35
        FilterStrength.Medium -> 75
        FilterStrength.High -> 130
    }
    val alpha = when (strength) {
        FilterStrength.Low -> 65
        FilterStrength.Medium -> 90
        FilterStrength.High -> 120
    }
    val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha, 220, 235, 245)
        strokeWidth = max(2f, source.width / 360f)
        strokeCap = Paint.Cap.ROUND
    }
    repeat(count) {
        val x = random.nextFloat() * source.width
        val y = random.nextFloat() * source.height
        val length = source.height * (0.018f + random.nextFloat() * 0.055f)
        canvas.drawLine(x, y, x - length * 0.2f, y + length, streakPaint)
    }
    val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha / 2, 235, 245, 255)
        style = Paint.Style.FILL
    }
    repeat(count / 5) {
        val radius = source.width * (0.006f + random.nextFloat() * 0.018f)
        canvas.drawCircle(
            random.nextFloat() * source.width,
            random.nextFloat() * source.height,
            radius,
            dropPaint,
        )
    }
    return if (strength == FilterStrength.High) blurByScaling(output, 0.82f) else output
}

private fun applyFog(source: Bitmap, strength: FilterStrength): Bitmap {
    val haze = when (strength) {
        FilterStrength.Low -> 0.18f
        FilterStrength.Medium -> 0.34f
        FilterStrength.High -> 0.52f
    }
    return transformPixels(source) { color, _ ->
        val gray = (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f).toInt()
        val desaturated = Color.rgb(
            ((Color.red(color) * 0.72f) + gray * 0.28f).toInt(),
            ((Color.green(color) * 0.72f) + gray * 0.28f).toInt(),
            ((Color.blue(color) * 0.72f) + gray * 0.28f).toInt(),
        )
        blendColors(desaturated, Color.rgb(225, 230, 232), haze)
    }
}

private fun applyLowLight(source: Bitmap, strength: FilterStrength, seed: Int): Bitmap {
    val exposure = when (strength) {
        FilterStrength.Low -> 0.62f
        FilterStrength.Medium -> 0.4f
        FilterStrength.High -> 0.24f
    }
    val noise = when (strength) {
        FilterStrength.Low -> 8
        FilterStrength.Medium -> 18
        FilterStrength.High -> 32
    }
    val random = Random(seed)
    return transformPixels(source) { color, _ ->
        fun channel(value: Int): Int {
            val offset = random.nextInt(-noise, noise + 1)
            return (value * exposure + offset).toInt().coerceIn(0, 255)
        }
        Color.rgb(channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
    }
}

private fun applyDefocus(source: Bitmap, strength: FilterStrength): Bitmap {
    val scale = when (strength) {
        FilterStrength.Low -> 0.7f
        FilterStrength.Medium -> 0.42f
        FilterStrength.High -> 0.2f
    }
    return blurByScaling(source, scale)
}

private fun applyImagePipelineDegradation(source: Bitmap, strength: FilterStrength): Bitmap {
    val scale = when (strength) {
        FilterStrength.Low -> 0.72f
        FilterStrength.Medium -> 0.45f
        FilterStrength.High -> 0.24f
    }
    val jpegQuality = when (strength) {
        FilterStrength.Low -> 55
        FilterStrength.Medium -> 28
        FilterStrength.High -> 12
    }
    val channelMask = when (strength) {
        FilterStrength.Low -> 0xFC
        FilterStrength.Medium -> 0xF8
        FilterStrength.High -> 0xF0
    }
    val pixelated = pixelateByScaling(source, scale)
    val quantized = transformPixels(pixelated) { color, _ ->
        Color.rgb(
            Color.red(color) and channelMask,
            Color.green(color) and channelMask,
            Color.blue(color) and channelMask,
        )
    }
    val output = ByteArrayOutputStream()
    quantized.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
    val bytes = output.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: quantized
}

private fun blurByScaling(source: Bitmap, scale: Float): Bitmap {
    val width = max(1, (source.width * scale).toInt())
    val height = max(1, (source.height * scale).toInt())
    val downscaled = Bitmap.createScaledBitmap(source, width, height, true)
    return Bitmap.createScaledBitmap(downscaled, source.width, source.height, true)
}

private fun pixelateByScaling(source: Bitmap, scale: Float): Bitmap {
    val width = max(1, (source.width * scale).toInt())
    val height = max(1, (source.height * scale).toInt())
    val downscaled = Bitmap.createScaledBitmap(source, width, height, false)
    return Bitmap.createScaledBitmap(downscaled, source.width, source.height, false)
}

private inline fun transformPixels(source: Bitmap, transform: (Int, Int) -> Int): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) pixels[index] = transform(pixels[index], index)
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}

private fun blendColors(first: Int, second: Int, amount: Float): Int {
    val inverse = 1f - amount
    return Color.rgb(
        (Color.red(first) * inverse + Color.red(second) * amount).toInt().coerceIn(0, 255),
        (Color.green(first) * inverse + Color.green(second) * amount).toInt().coerceIn(0, 255),
        (Color.blue(first) * inverse + Color.blue(second) * amount).toInt().coerceIn(0, 255),
    )
}

private fun lighten(color: Int, amount: Float): Int = blendColors(color, Color.WHITE, amount)
