package dev.wildedge.sdk

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoroutinesTrackingTest {

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

    // --- trackSuspendInference ---

    @Test fun suspendTrackingEmitsInferenceEvent() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference { "result" }
        assertEquals(1, events.count { it["event_type"] == "inference" })
    }

    @Test fun suspendTrackingReturnsBlockResult() = runBlocking {
        val (handle, _) = captureHandle()
        val result = handle.trackSuspendInference { 42 }
        assertEquals(42, result)
    }

    @Test fun suspendTrackingRecordsSuccessTrue() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference { Unit }
        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (inference["inference"] as Map<String, Any?>)["success"])
    }

    @Test fun suspendTrackingRecordsFailureOnException() = runBlocking {
        val (handle, events) = captureHandle()
        runCatching {
            handle.trackSuspendInference<Unit> { throw IllegalStateException("boom") }
        }
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val inf = inference["inference"] as Map<String, Any?>
        assertEquals(false, inf["success"])
        assertEquals("IllegalStateException", inf["error_code"])
    }

    @Test fun suspendTrackingRethrowsException() = runBlocking {
        val (handle, _) = captureHandle()
        val ex = runCatching {
            handle.trackSuspendInference<Unit> { throw RuntimeException("rethrow me") }
        }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals("rethrow me", ex!!.message)
    }

    @Test fun suspendTrackingPassesModalityToEvent() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference(
            inputModality = InputModality.Image,
            outputModality = OutputModality.Detection,
        ) { Unit }
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val inf = inference["inference"] as Map<String, Any?>
        assertEquals("image", inf["input_modality"])
        assertEquals("detection", inf["output_modality"])
    }

    @Test fun suspendTrackingOutputMetaExtractorReceivesResult() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference(
            outputMetaExtractor = { result: String -> mapOf("extracted" to result) },
        ) { "hello" }
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals("hello", outputMeta?.get("extracted"))
    }

    @Test fun suspendTrackingOutputMetaExtractorTakesPrecedenceOverStaticOutputMeta() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference(
            outputMeta = mapOf("source" to "static"),
            outputMetaExtractor = { _: String -> mapOf("source" to "extractor") },
        ) { "ignored" }
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals("extractor", outputMeta?.get("source"))
    }

    @Test fun suspendTrackingStaticOutputMetaUsedWhenNoExtractor() = runBlocking {
        val (handle, events) = captureHandle()
        handle.trackSuspendInference(
            outputMeta = mapOf("source" to "static"),
        ) { Unit }
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals("static", outputMeta?.get("source"))
    }

    // --- Flow.trackWith ---

    @Test fun flowTrackingEmitsInferenceEventOnCompletion() = runBlocking {
        val (handle, events) = captureHandle()
        val chunks = listOf("hello", " ", "world")
        flow { chunks.forEach { emit(it) } }
            .trackWith(handle)
            .collect {}
        assertEquals(1, events.count { it["event_type"] == "inference" })
    }

    @Test fun flowTrackingForwardsAllChunks() = runBlocking {
        val (handle, _) = captureHandle()
        val collected = mutableListOf<String>()
        flow { listOf("a", "b", "c").forEach { emit(it) } }
            .trackWith(handle)
            .collect { collected.add(it) }
        assertEquals(listOf("a", "b", "c"), collected)
    }

    @Test fun flowTrackingCapturesTokenCount() = runBlocking {
        val (handle, events) = captureHandle()
        // 8 chars → 8/4 = 2 tokens
        flow { listOf("hell", "o!!!").forEach { emit(it) } }
            .trackWith(handle)
            .collect {}
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(2, outputMeta?.get("tokens_out"))
    }

    @Test fun flowTrackingCapturesTimeToFirstToken() = runBlocking {
        val (handle, events) = captureHandle()
        flow {
            emit("first chunk")
            Thread.sleep(10)
            emit("second chunk")
        }.trackWith(handle).collect {}
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertNotNull(outputMeta?.get("time_to_first_token_ms"))
    }

    @Test fun flowTrackingUsesCustomTokenizer() = runBlocking {
        val (handle, events) = captureHandle()
        flow { emit("some output") }.trackWith(handle, tokenizer = { 99 }).collect {}
        val inference = events.first { it["event_type"] == "inference" }

        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(99, outputMeta?.get("tokens_out"))
    }

    @Test fun flowTrackingRecordsErrorOnException() = runBlocking {
        val (handle, events) = captureHandle()
        runCatching {
            flow<String> { throw IllegalArgumentException("stream error") }
                .trackWith(handle)
                .collect {}
        }
        val inference = events.firstOrNull { it["event_type"] == "inference" }
        assertNotNull(inference)
        @Suppress("UNCHECKED_CAST")
        val inf = inference!!["inference"] as Map<String, Any?>
        assertEquals(false, inf["success"])
        assertEquals("IllegalArgumentException", inf["error_code"])
    }
}
