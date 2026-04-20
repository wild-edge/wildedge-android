package dev.wildedge.sdk

import android.graphics.Bitmap
import dev.wildedge.sdk.analysis.analyzeImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageAnalysisTest {

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun checkerboard(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
        }
        return bmp
    }

    @Test fun widthAndHeightCorrect() {
        val meta = WildEdge.analyzeImage(solidBitmap(320, 240, 0xFF888888.toInt()))
        assertEquals(320, meta.width)
        assertEquals(240, meta.height)
    }

    @Test fun argb8888ReportsChannel4() {
        val meta = WildEdge.analyzeImage(solidBitmap(10, 10, 0xFF000000.toInt()))
        assertEquals(4, meta.channels)
    }

    @Test fun whiteImageHighBrightness() {
        val meta = WildEdge.analyzeImage(solidBitmap(20, 20, 0xFFFFFFFF.toInt()))
        assertNotNull(meta.brightnessMean)
        assertTrue("white should be bright", meta.brightnessMean!! > 0.9f)
    }

    @Test fun blackImageLowBrightness() {
        val meta = WildEdge.analyzeImage(solidBitmap(20, 20, 0xFF000000.toInt()))
        assertNotNull(meta.brightnessMean)
        assertTrue("black should be dark", meta.brightnessMean!! < 0.1f)
    }

    @Test fun solidColorHasLowStddev() {
        val meta = WildEdge.analyzeImage(solidBitmap(20, 20, 0xFF888888.toInt()))
        assertNotNull(meta.brightnessStddev)
        assertTrue("solid color should have near-zero stddev", meta.brightnessStddev!! < 0.01f)
    }

    @Test fun checkerboardHasHighStddev() {
        val meta = WildEdge.analyzeImage(checkerboard(20), sampleStep = 1)
        assertNotNull(meta.brightnessStddev)
        assertTrue("checkerboard should have high stddev", meta.brightnessStddev!! > 0.3f)
    }

    @Test fun brightnessBucketsSum() {
        val meta = WildEdge.analyzeImage(solidBitmap(20, 20, 0xFF808080.toInt()))
        val buckets = meta.brightnessBuckets
        assertNotNull(buckets)
        assertEquals(8, buckets!!.size)
        assertTrue("buckets should have samples", buckets.sum() > 0)
    }

    @Test fun checkerboardHasHighBlurScore() {
        // Checkerboard has sharp edges, high Laplacian variance
        val meta = WildEdge.analyzeImage(checkerboard(40), sampleStep = 1)
        assertNotNull(meta.blurScore)
        assertTrue("sharp image should have high blur score", meta.blurScore!! > 0f)
    }

    @Test fun solidColorHasNearZeroBlurScore() {
        val meta = WildEdge.analyzeImage(solidBitmap(40, 40, 0xFF808080.toInt()))
        assertNotNull(meta.blurScore)
        assertTrue("solid image should have near-zero blur score", meta.blurScore!! < 0.01f)
    }

    @Test fun noiseScorePresent() {
        val meta = WildEdge.analyzeImage(checkerboard(20))
        assertNotNull(meta.noiseScore)
    }

    @Test fun grayscaleHasNearZeroSaturation() {
        val meta = WildEdge.analyzeImage(solidBitmap(20, 20, 0xFF808080.toInt()))
        assertNotNull(meta.saturationMean)
        assertTrue("gray should have near-zero saturation", meta.saturationMean!! < 0.05f)
    }

    @Test fun sampleStepReducesCoverageNotDimensions() {
        val bmp = solidBitmap(100, 100, 0xFF888888.toInt())
        val meta1 = WildEdge.analyzeImage(bmp, sampleStep = 1)
        val meta2 = WildEdge.analyzeImage(bmp, sampleStep = 8)
        // Dimensions unaffected by sampleStep
        assertEquals(meta1.width, meta2.width)
        assertEquals(meta1.height, meta2.height)
        // Brightness should be roughly the same for a solid color
        val diff = kotlin.math.abs((meta1.brightnessMean ?: 0f) - (meta2.brightnessMean ?: 0f))
        assertTrue("solid color brightness should be stable across sample steps", diff < 0.01f)
    }
}
