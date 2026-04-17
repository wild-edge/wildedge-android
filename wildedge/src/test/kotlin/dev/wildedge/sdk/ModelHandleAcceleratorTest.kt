package dev.wildedge.sdk

import dev.wildedge.sdk.events.HardwareContext
import org.junit.Assert.*
import org.junit.Assert.assertNull
import org.junit.Test

class ModelHandleAcceleratorTest {

    private fun captureHandle(
        snapshot: () -> HardwareContext? = { null },
    ): Pair<ModelHandle, MutableList<Map<String, Any?>>> {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = ModelHandle(
            modelId = "m",
            info = ModelInfo("M", "1.0", "local", "tflite"),
            publish = { events.add(it) },
            hardwareSnapshot = snapshot,
        )
        return handle to events
    }

    @Suppress("UNCHECKED_CAST")
    private fun hardwareMap(event: Map<String, Any?>): Map<String, Any?>? =
        event["hardware"] as? Map<String, Any?>

    @Test fun acceleratorActualAppearsInInferenceEvent() {
        val (handle, events) = captureHandle()
        handle.acceleratorActual = Accelerator.GPU
        handle.trackInference(durationMs = 10)

        val hw = hardwareMap(events.first { it["event_type"] == "inference" })
        assertEquals(Accelerator.GPU, hw?.get("accelerator_actual"))
    }

    @Test fun acceleratorActualOverridesNullInSnapshot() {
        val (handle, events) = captureHandle(snapshot = { HardwareContext() })
        handle.acceleratorActual = Accelerator.NNAPI
        handle.trackInference(durationMs = 5)

        val hw = hardwareMap(events.first { it["event_type"] == "inference" })
        assertEquals(Accelerator.NNAPI, hw?.get("accelerator_actual"))
    }

    @Test fun acceleratorActualOverridesSnapshotValue() {
        val (handle, events) = captureHandle(snapshot = { HardwareContext(acceleratorActual = Accelerator.CPU) })
        handle.acceleratorActual = Accelerator.GPU
        handle.trackInference(durationMs = 5)

        val hw = hardwareMap(events.first { it["event_type"] == "inference" })
        assertEquals(Accelerator.GPU, hw?.get("accelerator_actual"))
    }

    @Test fun nullAcceleratorActualPreservesSnapshotValue() {
        val (handle, events) = captureHandle(snapshot = { HardwareContext(acceleratorActual = Accelerator.GPU) })
        handle.trackInference(durationMs = 5)

        val hw = hardwareMap(events.first { it["event_type"] == "inference" })
        assertEquals(Accelerator.GPU, hw?.get("accelerator_actual"))
    }

    @Test fun nullAcceleratorActualWithNullSnapshotProducesNoHardwareKey() {
        val (handle, events) = captureHandle(snapshot = { null })
        handle.trackInference(durationMs = 5)

        val event = events.first { it["event_type"] == "inference" }
        assertNull(event["hardware"])
    }

    @Test fun cpuAcceleratorAppearsInInferenceEvent() {
        val (handle, events) = captureHandle()
        handle.acceleratorActual = Accelerator.CPU
        handle.trackInference(durationMs = 1)

        val hw = hardwareMap(events.first { it["event_type"] == "inference" })
        assertEquals(Accelerator.CPU, hw?.get("accelerator_actual"))
    }
}
