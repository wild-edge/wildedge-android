package dev.wildedge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelHandleModalityTest {

    @Suppress("UNCHECKED_CAST")
    private fun inferenceMap(event: Map<String, Any?>): Map<String, Any?> =
        event["inference"] as Map<String, Any?>

    private fun handle(
        inputModality: InputModality? = null,
        outputModality: OutputModality? = null,
    ): Pair<ModelHandle, MutableList<Map<String, Any?>>> {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = ModelHandle(
            modelId = "m",
            info = ModelInfo(
                modelName = "M",
                modelVersion = "1.0",
                modelSource = "local",
                modelFormat = "tflite",
                inputModality = inputModality,
                outputModality = outputModality,
            ),
            publish = { events.add(it) },
            hardwareSnapshot = { null },
        )
        return handle to events
    }

    @Test fun modalityFromInfoUsedWhenNotPassedPerCall() {
        val (handle, events) = handle(InputModality.Image, OutputModality.Classification)
        handle.trackInference(durationMs = 10)
        val inf = inferenceMap(events.first())
        assertEquals("image", inf["input_modality"])
        assertEquals("classification", inf["output_modality"])
    }

    @Test fun perCallModalityOverridesInfoDefault() {
        val (handle, events) = handle(InputModality.Image, OutputModality.Classification)
        handle.trackInference(
            durationMs = 10,
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
        )
        val inf = inferenceMap(events.first())
        assertEquals("text", inf["input_modality"])
        assertEquals("generation", inf["output_modality"])
    }

    @Test fun nullModalityInInfoAndNoPerCallProducesNoModalityInEvent() {
        val (handle, events) = handle()
        handle.trackInference(durationMs = 10)
        val inf = inferenceMap(events.first())
        assertNull(inf["input_modality"])
        assertNull(inf["output_modality"])
    }
}
