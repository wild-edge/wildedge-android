package dev.wildedge.sample.carscanner

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFiltersTest {

    @Test
    fun everyFilterPreservesDimensionsAndChangesImage() {
        val source = checkerboard(80, 60)

        for (filter in CaptureFilter.entries) {
            val result = applyCaptureFilter(
                source,
                AppliedFilter(filter, FilterStrength.High),
                seed = 42,
            )

            assertEquals(filter.label, source.width, result.width)
            assertEquals(filter.label, source.height, result.height)
            assertFalse(filter.label, pixels(source).contentEquals(pixels(result)))
        }
    }

    @Test
    fun seededFiltersAreRepeatable() {
        val source = checkerboard(80, 60)

        for (filter in listOf(CaptureFilter.LensSmudge, CaptureFilter.Rain, CaptureFilter.LowLight)) {
            val first = applyCaptureFilter(source, AppliedFilter(filter, FilterStrength.Medium), seed = 7)
            val second = applyCaptureFilter(source, AppliedFilter(filter, FilterStrength.Medium), seed = 7)

            assertArrayEquals(filter.label, pixels(first), pixels(second))
        }
    }

    @Test
    fun noAppliedFilterReturnsOriginalBitmap() {
        val source = checkerboard(20, 20)

        assertEquals(source, applyCaptureFilter(source, null, seed = 1))
    }

    @Test
    fun geminiRetriesOnlyTransientHttpFailures() {
        listOf(408, 429, 500, 503, 599).forEach { status ->
            assertTrue("Expected $status to be retried", isTransientGeminiStatus(status))
        }
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertFalse("Expected $status not to be retried", isTransientGeminiStatus(status))
        }
    }

    private fun checkerboard(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.WHITE else Color.BLACK)
            }
        }
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
