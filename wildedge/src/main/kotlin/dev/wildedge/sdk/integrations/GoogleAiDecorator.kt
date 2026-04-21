package dev.wildedge.sdk.integrations

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.events.TextInputMeta
import kotlinx.coroutines.flow.Flow

/**
 * Wraps a [GenerativeModel] to automatically record inference metrics via [ModelHandle].
 *
 * Create via [WildEdgeClient.decorate] rather than constructing directly.
 * Untracked operations (e.g. [model].startChat, [model].countTokens) are available through [model].
 */
class GoogleAiDecorator(
    val model: GenerativeModel,
    val handle: ModelHandle,
) {
    /** Calls [GenerativeModel.generateContent] and records an inference event. */
    suspend fun generateContent(prompt: String, inputMeta: TextInputMeta? = null): GenerateContentResponse =
        model.generateContentTracked(handle, prompt, inputMeta)

    /** Calls [GenerativeModel.generateContent] and records an inference event. */
    suspend fun generateContent(vararg prompt: Content, inputMeta: TextInputMeta? = null): GenerateContentResponse =
        model.generateContentTracked(handle, *prompt, inputMeta = inputMeta)

    /** Calls [GenerativeModel.generateContent] and records an inference event. */
    suspend fun generateContent(prompt: Bitmap): GenerateContentResponse =
        model.generateContentTracked(handle, prompt)

    /** Calls [GenerativeModel.generateContentStream]; records an inference event when the flow completes. */
    fun generateContentStream(prompt: String, inputMeta: TextInputMeta? = null): Flow<GenerateContentResponse> =
        model.generateContentStream(prompt).trackWith(handle, inputMeta)

    /** Calls [GenerativeModel.generateContentStream]; records an inference event when the flow completes. */
    fun generateContentStream(vararg prompt: Content, inputMeta: TextInputMeta? = null): Flow<GenerateContentResponse> =
        model.generateContentStream(*prompt).trackWith(handle, inputMeta)

    /** Calls [GenerativeModel.generateContentStream]; records an inference event when the flow completes. */
    fun generateContentStream(prompt: Bitmap): Flow<GenerateContentResponse> =
        model.generateContentStream(prompt).trackWith(handle, inputModality = InputModality.Image)
}

/**
 * Wraps [model] in a [GoogleAiDecorator] and registers it for tracking.
 *
 * @param modelId Identifier for the model, e.g. `"gemini-2.0-flash"`.
 * @param modelVersion Optional version string.
 * @param modelFamily Model family label, defaults to `"gemini"`.
 */
fun WildEdgeClient.decorate(
    model: GenerativeModel,
    modelId: String,
    modelVersion: String? = null,
    modelFamily: String? = "gemini",
): GoogleAiDecorator {
    val handle = registerModel(
        modelId,
        ModelInfo(
            modelName = modelId,
            modelVersion = modelVersion,
            modelSource = "api",
            modelFormat = "api",
            modelFamily = modelFamily,
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
        ),
    )
    return GoogleAiDecorator(model, handle)
}
