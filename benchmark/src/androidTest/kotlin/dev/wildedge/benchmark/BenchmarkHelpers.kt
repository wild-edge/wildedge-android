package dev.wildedge.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient

// DSN points to a non-existent local port — Consumer fails and backs off,
// inference-thread measurements are unaffected.
internal fun benchmarkClient(): WildEdgeClient {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return WildEdge.Builder(context).apply {
        dsn = "http://bench:key@127.0.0.1:19999/1"
    }.build()
}

internal fun WildEdgeClient.benchmarkHandle(modelId: String = "bench-model") =
    registerModel(modelId, ModelInfo(modelId, "1.0", "local", "onnx"))

// Busy-wait instead of Thread.sleep() — sleep releases the CPU and skews
// allocation pressure relative to real inference.
@Suppress("MagicNumber")
internal fun inferenceWork(targetMs: Long): Float {
    val endNs = System.nanoTime() + targetMs * 1_000_000L
    var result = 0f
    while (System.nanoTime() < endNs) {
        result += 0.001f
    }
    return result
}
