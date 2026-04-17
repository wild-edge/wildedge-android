package dev.wildedge.sdk.integrations

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import java.io.File

class LiteRtEngineDecorator(
    private val engine: Engine,
    val handle: ModelHandle,
) : AutoCloseable {

    fun createConversation(config: ConversationConfig = ConversationConfig()): Conversation =
        engine.createConversation(config)

    override fun close() {
        handle.trackUnload()
        engine.close()
    }
}

fun WildEdge.decorate(
    engine: Engine,
    config: EngineConfig,
    loadDurationMs: Int = 0,
    modelVersion: String = "unknown",
    accelerator: String? = null,
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

fun WildEdge.decorate(
    engine: Engine,
    config: EngineConfig,
    loadDurationMs: Int = 0,
    modelId: String,
    modelVersion: String = "unknown",
    quantization: String? = null,
    accelerator: String? = null,
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
