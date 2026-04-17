package dev.wildedge.sdk

import dev.wildedge.sdk.integrations.trackInferenceExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceExecutionTest {

    private fun handle(events: MutableList<Map<String, Any?>>): ModelHandle = ModelHandle(
        modelId = "m1",
        info = ModelInfo("m1", "1", "local", "tflite"),
        publish = { events.add(it) },
        hardwareSnapshot = { null },
    )

    @Test fun successTracksInferenceAndReturnsResult() {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = handle(events)

        val result = trackInferenceExecution(
            handle = handle,
            inputModality = InputModality.Tensor,
            outputModality = OutputModality.Tensor,
        ) { 42 }

        assertEquals(42, result)
        assertEquals(1, events.size)
        @Suppress("UNCHECKED_CAST")
        val inference = events.first()["inference"] as Map<String, Any?>
        assertEquals(true, inference["success"])
    }

    @Test fun failureTracksInferenceAndRethrowsOriginalException() {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = handle(events)

        val thrown = assertThrows(IllegalStateException::class.java) {
            trackInferenceExecution(
                handle = handle,
                inputModality = InputModality.Tensor,
                outputModality = OutputModality.Tensor,
            ) {
                throw IllegalStateException("model_failed")
            }
        }

        assertEquals("model_failed", thrown.message)
        assertEquals(1, events.size)
        @Suppress("UNCHECKED_CAST")
        val inference = events.first()["inference"] as Map<String, Any?>
        assertEquals(false, inference["success"])
        assertEquals("IllegalStateException", inference["error_code"])
    }

    @Test fun telemetryFailureDoesNotMaskModelFailure() {
        val handle = ModelHandle(
            modelId = "m1",
            info = ModelInfo("m1", "1", "local", "tflite"),
            publish = { throw RuntimeException("telemetry_failed") },
            hardwareSnapshot = { null },
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            trackInferenceExecution(
                handle = handle,
                inputModality = InputModality.Tensor,
                outputModality = OutputModality.Tensor,
            ) {
                throw IllegalArgumentException("model_failed")
            }
        }

        assertEquals("model_failed", thrown.message)
    }

    @Test fun telemetryFailureOnSuccessDoesNotBreakCaller() {
        val handle = ModelHandle(
            modelId = "m1",
            info = ModelInfo("m1", "1", "local", "tflite"),
            publish = { throw RuntimeException("telemetry_failed") },
            hardwareSnapshot = { null },
        )

        val result = trackInferenceExecution(
            handle = handle,
            inputModality = InputModality.Tensor,
            outputModality = OutputModality.Tensor,
        ) { "ok" }

        assertEquals("ok", result)
    }

    @Test fun errorCodeFallsBackWhenSimpleNameIsEmpty() {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = handle(events)
        val anonymous = object : Throwable("anon") {}

        val thrown = assertThrows(Throwable::class.java) {
            trackInferenceExecution(
                handle = handle,
                inputModality = InputModality.Tensor,
                outputModality = OutputModality.Tensor,
            ) {
                throw anonymous
            }
        }

        assertNotNull(thrown)
        @Suppress("UNCHECKED_CAST")
        val inference = events.first()["inference"] as Map<String, Any?>
        val errorCode = inference["error_code"] as String
        assertTrue(errorCode.isNotBlank())
        assertTrue(errorCode == anonymous.javaClass.name)
        assertFalse(errorCode == anonymous.javaClass.simpleName)
    }
}
