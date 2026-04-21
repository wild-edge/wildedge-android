package examples.googleai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.GoogleAiDecorator
import dev.wildedge.sdk.integrations.decorate

// Assumes WildEdge.init() has already run (manifest meta-data or Application.onCreate()).
class GoogleAiExample {

    private val wildEdge = WildEdge.getInstance()

    private val gemini: GoogleAiDecorator = wildEdge.decorate(
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = "<YOUR_GOOGLE_AI_API_KEY>",
        ),
        modelId = "gemini-2.0-flash",
        modelFamily = "gemini",
    )

    // Streaming: tracking event fires when the flow completes.
    fun stream(prompt: String) = gemini.generateContentStream(prompt, inputMeta = WildEdge.analyzeText(prompt))

    // Unary: tracking event fires when the suspend call returns.
    suspend fun generate(prompt: String) = gemini.generateContent(prompt, inputMeta = WildEdge.analyzeText(prompt))

    // Untracked operations go through .model directly.
    fun chat(history: List<Content>) = gemini.model.startChat(history)

}
