package dev.wildedge.sdk

import dev.wildedge.sdk.integrations.classificationOutputMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationOutputMetaTest {

    @Test fun returnsNullWhenNumClassesIsZero() {
        assertNull(classificationOutputMeta(Array(1) { FloatArray(3) { 0.5f } }, 0, null))
    }

    @Test fun returnsNullForUnrecognizedOutputType() {
        assertNull(classificationOutputMeta(Array(1) { IntArray(3) }, 3, null))
    }

    @Test fun returnsNullForEmptyArray() {
        assertNull(classificationOutputMeta(emptyArray<FloatArray>(), 3, null))
    }

    @Test fun returnsNullWhenProbsSizeDoesNotMatchNumClasses() {
        val output = Array(1) { FloatArray(5) { 1f } }
        assertNull(classificationOutputMeta(output, 10, null))
    }

    @Test fun floatOutputTopClassIsArgmax() {
        val logits = FloatArray(5) { 0f }.also { it[2] = 10f }
        val meta = classificationOutputMeta(Array(1) { logits }, 5, null)
        assertNotNull(meta)
        assertEquals("2", meta!!.topK!!.first().label)
    }

    @Test fun byteOutputTopClassIsArgmax() {
        val bytes = ByteArray(5) { 0 }.also { it[3] = 200.toByte() }
        val meta = classificationOutputMeta(Array(1) { bytes }, 5, null)
        assertNotNull(meta)
        assertEquals("3", meta!!.topK!!.first().label)
    }

    @Test fun usesLabelNamesWhenProvided() {
        val labels = listOf("cat", "dog", "bird")
        val logits = FloatArray(3) { 0f }.also { it[1] = 5f }
        val meta = classificationOutputMeta(Array(1) { logits }, 3, labels)
        assertEquals("dog", meta!!.topK!!.first().label)
    }

    @Test fun fallsBackToNumericIndexWhenLabelsNull() {
        val logits = FloatArray(3) { 0f }.also { it[1] = 5f }
        val meta = classificationOutputMeta(Array(1) { logits }, 3, null)
        assertEquals("1", meta!!.topK!!.first().label)
    }

    @Test fun topKCappedAtFive() {
        val logits = FloatArray(10) { it.toFloat() }
        val meta = classificationOutputMeta(Array(1) { logits }, 10, null)
        assertEquals(5, meta!!.topK!!.size)
    }

    @Test fun topKIsAllClassesWhenFewerThanFive() {
        val logits = FloatArray(3) { it.toFloat() }
        val meta = classificationOutputMeta(Array(1) { logits }, 3, null)
        assertEquals(3, meta!!.topK!!.size)
    }

    @Test fun topKIsDescendingByConfidence() {
        val logits = FloatArray(5) { it.toFloat() }
        val meta = classificationOutputMeta(Array(1) { logits }, 5, null)
        val confidences = meta!!.topK!!.map { it.confidence!! }
        assertTrue(confidences.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test fun numPredictionsMatchesNumClasses() {
        val logits = FloatArray(7) { 1f }
        val meta = classificationOutputMeta(Array(1) { logits }, 7, null)
        assertEquals(7, meta!!.numPredictions)
    }

    @Test fun avgConfidenceMatchesTopOnePrediction() {
        val logits = FloatArray(4) { 0f }.also { it[0] = 10f }
        val meta = classificationOutputMeta(Array(1) { logits }, 4, null)
        assertEquals(meta!!.topK!!.first().confidence, meta.avgConfidence)
    }

    @Test fun confidenceIsInZeroOneRange() {
        val bytes = ByteArray(5) { (it * 50).toByte() }
        val meta = classificationOutputMeta(Array(1) { bytes }, 5, null)
        meta!!.topK!!.forEach { pred ->
            assertTrue(pred.confidence!! in 0f..1f)
        }
    }

    @Test fun toMapIncludesClassificationTask() {
        val logits = FloatArray(3) { it.toFloat() }
        val meta = classificationOutputMeta(Array(1) { logits }, 3, null)
        assertEquals("classification", meta!!.toMap()["task"])
    }
}
