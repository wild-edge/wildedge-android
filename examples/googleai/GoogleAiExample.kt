package examples.googleai

import com.google.ai.client.generativeai.GenerativeModel
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.generateContentTracked
import dev.wildedge.sdk.integrations.registerGoogleAiModel
import dev.wildedge.sdk.integrations.trackWith

// Assumes WildEdge.init() has already run (manifest meta-data or Application.onCreate()).
class GoogleAiExample {

    private val wildEdge = WildEdge.getInstance()

    private val model = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = "<YOUR_GOOGLE_AI_API_KEY>",
    )

    // One handle per model name. Register once, reuse for every call.
    private val handle = wildEdge.registerGoogleAiModel(
        modelId = "gemini-2.0-flash",
        modelFamily = "gemini",
    )

    // Streaming: tracking event fires when the flow completes.
    fun stream(prompt: String) = model.generateContentStream(prompt)
        .trackWith(handle, inputMeta = WildEdge.analyzeText(prompt))

    // Unary: tracking event fires when the suspend call returns.
    suspend fun generate(prompt: String) = model.generateContentTracked(
        handle = handle,
        prompt = prompt,
        inputMeta = WildEdge.analyzeText(prompt),
    )

}
