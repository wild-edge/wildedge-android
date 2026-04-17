package examples.tracing

import android.content.Context
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient

class TracingExample(context: Context) {

    private val wildEdge: WildEdgeClient = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
        appVersion = "1.0.0"
    }

    private val embedHandle = wildEdge.registerModel("embed", ModelInfo("Embed", "1", "local", "tflite"))
    private val classifyHandle = wildEdge.registerModel("classify", ModelInfo("Classify", "1", "local", "tflite"))

    // All inferences inside the trace block share the same trace_id.
    // Nested span() calls set parent_span_id so the server can reconstruct the tree.
    fun runPipeline(input: ByteArray) {
        wildEdge.trace("pipeline") { trace ->
            val embedding = trace.span("embed") {
                val start = System.currentTimeMillis()
                val result = runEmbedding(input)
                embedHandle.trackInference(durationMs = (System.currentTimeMillis() - start).toInt())
                result
            }

            trace.span("classify") {
                val start = System.currentTimeMillis()
                val result = runClassification(embedding)
                classifyHandle.trackInference(durationMs = (System.currentTimeMillis() - start).toInt())
                result
            }
        }
    }

    private fun runEmbedding(input: ByteArray): FloatArray = FloatArray(128)
    private fun runClassification(embedding: FloatArray): String = "label"

    fun close() {
        wildEdge.close()
    }
}
