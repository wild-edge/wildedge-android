package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Test

class FeedbackTypeTest {

    private fun captureHandle(): Pair<ModelHandle, MutableList<Map<String, Any?>>> {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = ModelHandle(
            modelId = "test-model",
            info = ModelInfo("TestModel", "1.0", "local", "tflite"),
            publish = { events.add(it.toMap()) },
            hardwareSnapshot = { null },
        )
        return handle to events
    }

    @Test fun thumbsUpSerializesCorrectly() {
        val (handle, events) = captureHandle()
        handle.trackInference(durationMs = 10)
        handle.trackFeedback(FeedbackType.ThumbsUp)

        val feedback = events.first { it["event_type"] == "feedback" }
        assertEquals("thumbs_up", (feedback["feedback"] as Map<*, *>)["feedback_type"])
    }

    @Test fun thumbsDownSerializesCorrectly() {
        val (handle, events) = captureHandle()
        handle.trackInference(durationMs = 10)
        handle.trackFeedback(FeedbackType.ThumbsDown)

        val feedback = events.first { it["event_type"] == "feedback" }
        assertEquals("thumbs_down", (feedback["feedback"] as Map<*, *>)["feedback_type"])
    }

    @Test fun allBuiltInTypesHaveExpectedValues() {
        assertEquals("thumbs_up", FeedbackType.ThumbsUp.value)
        assertEquals("thumbs_down", FeedbackType.ThumbsDown.value)
        assertEquals("accepted", FeedbackType.Accepted.value)
        assertEquals("edited", FeedbackType.Edited.value)
        assertEquals("rejected", FeedbackType.Rejected.value)
    }

    @Test fun customTypePreservesArbitraryValue() {
        val (handle, events) = captureHandle()
        handle.trackInference(durationMs = 10)
        handle.trackFeedback(FeedbackType.Custom("hallucination"))

        val feedback = events.first { it["event_type"] == "feedback" }
        assertEquals("hallucination", (feedback["feedback"] as Map<*, *>)["feedback_type"])
    }

    @Test fun editedTypeCarriesEditDistance() {
        val (handle, events) = captureHandle()
        handle.trackInference(durationMs = 10)
        handle.trackFeedback(FeedbackType.Edited, editDistance = 12)

        val feedback = events.first { it["event_type"] == "feedback" }
        assertEquals(12, (feedback["feedback"] as Map<*, *>)["edit_distance"])
    }

    @Test fun feedbackLinksToLastInferenceId() {
        val (handle, events) = captureHandle()
        handle.trackInference(durationMs = 10)
        val lastId = handle.lastInferenceId
        handle.trackFeedback(FeedbackType.ThumbsUp)

        val feedback = events.first { it["event_type"] == "feedback" }
        assertEquals(lastId, (feedback["feedback"] as Map<*, *>)["related_inference_id"])
    }

    @Test fun noFeedbackEmittedWithoutPriorInference() {
        val (handle, events) = captureHandle()
        handle.trackFeedback(FeedbackType.ThumbsUp)

        assertTrue(events.none { it["event_type"] == "feedback" })
    }
}
