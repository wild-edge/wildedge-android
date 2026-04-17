package dev.wildedge.sdk

import dev.wildedge.sdk.events.buildInferenceEvent
import org.junit.Assert.*
import org.junit.Test

// Reservoir is not yet extracted as a standalone class (v1 uses flat queue).
// These tests cover the sampling envelope computed in Batch.kt.

class BatchSamplingTest {

    private fun inferenceEvent(modelId: String, avgConfidence: Float?): MutableMap<String, Any?> {
        val meta = if (avgConfidence != null) mapOf("avg_confidence" to avgConfidence) else null
        return buildInferenceEvent(
            modelId = modelId,
            durationMs = 10,
            outputMeta = meta,
        )
    }

    @Test fun samplingEnvelopeAbsentWhenNoInferenceEvents() {
        val json = buildBatch(
            device = fakeDevice(),
            models = emptyMap(),
            events = listOf(mapOf("event_type" to "model_load", "model_id" to "m").toMutableMap()),
            sessionId = "s1",
            createdAt = "2026-01-01T00:00:00.000Z",
            lowConfidenceThreshold = 0.5f,
        )
        assertFalse("sampling should be absent", json.contains("\"sampling\""))
    }

    @Test fun samplingEnvelopePresentForInferenceEvents() {
        val events = listOf(inferenceEvent("m1", 0.9f), inferenceEvent("m1", 0.3f))
        val json = buildBatch(
            device = fakeDevice(),
            models = emptyMap(),
            events = events,
            sessionId = "s1",
            createdAt = "2026-01-01T00:00:00.000Z",
            lowConfidenceThreshold = 0.5f,
        )
        assertTrue(json.contains("\"sampling\""))
        assertTrue(json.contains("\"total_inference_events_seen\":2"))
        assertTrue(json.contains("\"low_confidence_seen\":1"))
    }

    @Test fun samplingCountsPerModel() {
        val events = listOf(
            inferenceEvent("m1", 0.8f),
            inferenceEvent("m2", 0.2f),
            inferenceEvent("m2", 0.9f),
        )
        val json = buildBatch(
            device = fakeDevice(),
            models = emptyMap(),
            events = events,
            sessionId = "s1",
            createdAt = "2026-01-01T00:00:00.000Z",
            lowConfidenceThreshold = 0.5f,
        )
        assertTrue(json.contains("\"m1\""))
        assertTrue(json.contains("\"m2\""))
    }

    @Test fun internalFieldsStrippedFromPayload() {
        val event = inferenceEvent("m1", 0.9f)
        val json = buildBatch(
            device = fakeDevice(),
            models = emptyMap(),
            events = listOf(event),
            sessionId = "s1",
            createdAt = "2026-01-01T00:00:00.000Z",
            lowConfidenceThreshold = 0.5f,
        )
        assertFalse("__we_ fields must not appear in payload", json.contains("__we_"))
    }
}
