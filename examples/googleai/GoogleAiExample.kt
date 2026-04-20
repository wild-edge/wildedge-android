package examples.googleai

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.generateContentTracked
import dev.wildedge.sdk.integrations.registerGoogleAiModel
import dev.wildedge.sdk.integrations.trackWith

class GoogleAiExample(context: Context) {

    private val wildEdge: WildEdgeClient = WildEdge.init(context) {
        dsn = System.getenv("WILDEDGE_DSN") ?: ""
    }

    private val model = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = "<YOUR_GOOGLE_AI_API_KEY>",
    )

    // One handle per model name. Register once, reuse for every call.
    private val handle = wildEdge.registerGoogleAiModel(
        modelId = "gemini-2.0-flash",
        modelFamily = "gemini",
    )

    // generateContentStream returns Flow<GenerateContentResponse>.
    // .trackWith captures TTFT, token counts from usageMetadata, and tokens/sec.
    // Collect the returned flow as normal — the tracking event fires on completion.
    fun stream(prompt: String) = model.generateContentStream(prompt)
        .trackWith(handle, inputMeta = WildEdge.analyzeText(prompt))

    // generateContentTracked wraps the suspend unary call.
    // Token counts from usageMetadata are used when available.
    suspend fun generate(prompt: String) = model.generateContentTracked(
        handle = handle,
        prompt = prompt,
        inputMeta = WildEdge.analyzeText(prompt),
    )

    fun close() = wildEdge.close()
}
