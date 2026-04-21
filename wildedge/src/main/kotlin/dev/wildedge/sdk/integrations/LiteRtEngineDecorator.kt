package dev.wildedge.sdk.integrations

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdgeClient
import java.io.File

/**
 * Wraps a LiteRT [Engine] to automatically record lifecycle events via [ModelHandle].
 *
 * Create via [WildEdgeClient.decorate] rather than constructing directly.
 */
class LiteRtEngineDecorator(
    private val engine: Engine,
    val handle: ModelHandle,
) : AutoCloseable {

    /** Creates a new conversation on the underlying engine. */
    fun createConversation(config: ConversationConfig = ConversationConfig()): Conversation =
        engine.createConversation(config)

    /** Records an unload event and closes the underlying engine. */
    override fun close() {
        handle.trackUnload()
        engine.close()
    }
}

/** Creates a [LiteRtEngineDecorator], inferring model metadata from [config]. */
fun WildEdgeClient.decorate(
    engine: Engine,
    config: EngineConfig,
    loadDurationMs: Int = 0,
    modelVersion: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): LiteRtEngineDecorator {
    val modelFile = File(config.modelPath)
    val handle = registerModel(
        inferModelId(modelFile),
        ModelInfo(
            modelName = inferModelId(modelFile),
            modelVersion = modelVersion,
            modelSource = "local",
            modelFormat = "litertlm",
            quantization = inferQuantization(modelFile),
        ),
    ).also {
        it.trackLoad(durationMs = loadDurationMs, accelerator = accelerator, coldStart = true)
        it.acceleratorActual = accelerator
    }
    return LiteRtEngineDecorator(engine, handle)
}

/** Creates a [LiteRtEngineDecorator] with explicit model metadata. */
fun WildEdgeClient.decorate(
    engine: Engine,
    loadDurationMs: Int = 0,
    modelId: String,
    modelVersion: String? = null,
    quantization: String? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
): LiteRtEngineDecorator {
    val handle = registerModel(
        modelId,
        ModelInfo(
            modelName = modelId,
            modelVersion = modelVersion,
            modelSource = "local",
            modelFormat = "litertlm",
            quantization = quantization,
        ),
    ).also {
        it.trackLoad(durationMs = loadDurationMs, accelerator = accelerator, coldStart = true)
        it.acceleratorActual = accelerator
    }
    return LiteRtEngineDecorator(engine, handle)
}
