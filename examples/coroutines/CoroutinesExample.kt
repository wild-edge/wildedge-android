package examples.coroutines

import android.content.Context
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.events.TextInputMeta
import dev.wildedge.sdk.trackSuspendInference
import dev.wildedge.sdk.trackWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CoroutinesExample(context: Context) {

    private val wildEdge: WildEdgeClient = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
        appVersion = "1.0.0"
    }

    private val classifyHandle = wildEdge.registerModel(
        "mobilenet-v3",
        ModelInfo("MobileNet V3", "3.0", "local", "tflite", quantization = "int8"),
    )

    private val llmHandle = wildEdge.registerModel(
        "gemma-3n",
        ModelInfo("Gemma 3N", "1.0", "local", "litertlm", quantization = "int4"),
    )

    // trackSuspendInference wraps any suspend block: times it, emits the inference event,
    // and records success=false + error_code if an exception is thrown.
    suspend fun classify(input: ByteArray): String =
        classifyHandle.trackSuspendInference(
            inputModality = InputModality.Image,
            outputModality = OutputModality.Detection,
        ) {
            withContext(Dispatchers.Default) { runClassifier(input) }
        }

    // Flow<String>.trackWith captures TTFT, token counts, and tokens/sec automatically.
    // Collect the returned flow as normal — the tracking event fires when it completes.
    fun streamResponse(prompt: String): Flow<String> {
        val inputMeta = WildEdge.analyzeText(prompt)
        return buildLlmFlow(prompt).trackWith(llmHandle, inputMeta = inputMeta)
    }

    private fun runClassifier(input: ByteArray): String = "label"

    private fun buildLlmFlow(prompt: String): Flow<String> =
        kotlinx.coroutines.flow.flow {
            // Replace with your actual LLM streaming call.
            emit("Hello"); emit(", "); emit("world!")
        }

    fun close() = wildEdge.close()
}
