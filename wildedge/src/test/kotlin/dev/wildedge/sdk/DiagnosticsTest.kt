package dev.wildedge.sdk

import dev.wildedge.sdk.events.GenerationOutputMeta
import dev.wildedge.sdk.events.HardwareContext
import dev.wildedge.sdk.events.TextInputMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    private fun clientWithQueue(maxSize: Int = Config.DEFAULT_MAX_QUEUE_SIZE): Pair<WildEdgeClient, EventQueue> {
        val queue = EventQueue(maxSize = maxSize)
        val client = WildEdge(
            noop = false,
            queue = queue,
            registry = ModelRegistry(),
            consumer = null,
            hardwareSampler = null,
            debug = false,
        )
        return client to queue
    }

    private fun richHandle(client: WildEdgeClient): ModelHandle = client.registerModel(
        modelId = "diag-model",
        info = ModelInfo(
            modelName = "Diag Model",
            modelVersion = "1.0",
            modelSource = "local",
            modelFormat = "tflite",
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
        ),
    )

    private fun trackRichInference(handle: ModelHandle) = handle.trackInference(
        durationMs = 10,
        inputModality = InputModality.Text,
        outputModality = OutputModality.Generation,
        success = true,
        inputMeta = TextInputMeta(
            charCount = 512,
            wordCount = 96,
            tokenCount = 128,
            language = "en",
            languageConfidence = 0.99f,
            containsCode = false,
            promptType = "instruction",
            turnIndex = 0,
            hasAttachments = false,
        ).toMap(),
        outputMeta = GenerationOutputMeta(
            tokensIn = 128,
            tokensOut = 64,
            cachedInputTokens = 32,
            timeToFirstTokenMs = 120,
            tokensPerSecond = 42.5f,
            stopReason = "eos",
            contextUsed = 2048,
            avgTokenEntropy = 1.3f,
            safetyTriggered = false,
        ).toMap(),
        generationConfig = mapOf("temperature" to 0.7, "top_p" to 0.9, "max_tokens" to 256),
        hardware = HardwareContext(
            thermalState = "nominal",
            thermalStateRaw = "THERMAL_STATUS_NONE",
            batteryLevel = 0.82f,
            batteryCharging = false,
            memoryAvailableBytes = 2_000_000_000L,
            cpuFreqMhz = 3200,
            cpuFreqMaxMhz = 4000,
            acceleratorActual = Accelerator.NPU,
            gpuBusyPercent = 12,
        ),
        traceId = "trace-diag",
        spanId = "span-diag",
        parentSpanId = "parent-diag",
        runId = "run-diag",
        agentId = "agent-diag",
    )

    @Test fun diagnosticsAfterFillingQueueToMax() {
        val maxSize = 1000
        val (client, _) = clientWithQueue(maxSize)
        val handle = richHandle(client)

        repeat(maxSize) { trackRichInference(handle) }

        val diag = client.diagnostics

        val mb = { bytes: Long -> "%.3f MB".format(bytes / 1_048_576.0) }
        println("SdkDiagnostics (full queue, $maxSize rich inference events):")
        println("  eventQueueSizeBytes: ${diag.eventQueueSizeBytes} (${mb(diag.eventQueueSizeBytes)})")
        println("  eventQueueJsonBytes: ${diag.eventQueueJsonBytes} (${mb(diag.eventQueueJsonBytes)})")

        assertEquals(maxSize, client.pendingCount)
        assertTrue("heap size must be positive", diag.eventQueueSizeBytes > 0)
        assertTrue("json size must be positive", diag.eventQueueJsonBytes > 0)
    }

    @Test fun diagnosticsOnEmptyQueueReturnsZero() {
        val (client, _) = clientWithQueue()

        val diag = client.diagnostics

        assertEquals(0, client.pendingCount)
        assertEquals(0L, diag.eventQueueSizeBytes)
        assertEquals(0L, diag.eventQueueJsonBytes)
    }

    @Test fun diagnosticsSizesGrowWithEachEvent() {
        val (client, _) = clientWithQueue()
        val handle = richHandle(client)

        trackRichInference(handle)
        val after1 = client.diagnostics

        trackRichInference(handle)
        val after2 = client.diagnostics

        assertTrue("heap bytes grow with queue depth", after2.eventQueueSizeBytes > after1.eventQueueSizeBytes)
        assertTrue("json bytes grow with queue depth", after2.eventQueueJsonBytes > after1.eventQueueJsonBytes)
    }

    @Test fun diagnosticsHeapBytesExceedJsonBytes() {
        val (client, _) = clientWithQueue()
        val handle = richHandle(client)
        repeat(10) { trackRichInference(handle) }

        val diag = client.diagnostics

        // Heap includes JVM object overhead (headers, map table, entry nodes, etc.)
        // which makes it larger than the compact JSON encoding.
        assertTrue(
            "heap bytes (${diag.eventQueueSizeBytes}) should exceed json bytes (${diag.eventQueueJsonBytes})",
            diag.eventQueueSizeBytes > diag.eventQueueJsonBytes,
        )
    }

    @Test fun diagnosticsReflectsQueueDrainAfterRemove() {
        val (client, queue) = clientWithQueue()
        val handle = richHandle(client)
        repeat(5) { trackRichInference(handle) }

        val before = client.diagnostics
        assertTrue(before.eventQueueSizeBytes > 0)

        queue.removeFirstN(5)

        val after = client.diagnostics
        assertEquals(0L, after.eventQueueSizeBytes)
        assertEquals(0L, after.eventQueueJsonBytes)
    }

    @Test fun noopClientDiagnosticsAlwaysReturnsZero() {
        val noop = WildEdgeClient.noop()

        val diag = noop.diagnostics

        assertEquals(0L, diag.eventQueueSizeBytes)
        assertEquals(0L, diag.eventQueueJsonBytes)
        assertFalse("noop should not have pending events", noop.pendingCount > 0)
    }
}
