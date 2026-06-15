package dev.wildedge.sample.executorchllm

import dev.wildedge.sdk.GenerationConfig
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.ModelHandle
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule

private const val SEQ_LEN = 512 + 128
private const val MAX_NEW_TOKENS = 512
private const val TEMPERATURE = 0.8f

class LLMRunner(private val modelPath: String, private val tokenizerPath: String) {

    private var module: LlmModule? = null
    var handle: ModelHandle? = null

    fun load(): Long {
        val start = System.currentTimeMillis()
        val m = LlmModule(modelPath, tokenizerPath, TEMPERATURE)
        val status = m.load()
        check(status == 0) { "Failed to load model (status $status)" }
        module = m
        return System.currentTimeMillis() - start
    }

    fun generate(prompt: String, onToken: (String) -> Unit, onDone: (durationMs: Int) -> Unit) {
        val m = module ?: error("Model not loaded")
        val start = System.currentTimeMillis()
        m.generate(
            prompt,
            SEQ_LEN,
            object : LlmCallback {
                override fun onResult(result: String) = onToken(result)
                override fun onStats(stats: String) = Unit
            },
            /* echo = */ false,
        )
        onDone((System.currentTimeMillis() - start).toInt())
    }

    fun stop() {
        module?.stop()
    }

    fun unload() {
        module?.resetNative()
        module = null
    }

    val generationConfig
        get() = GenerationConfig(
            temperature = TEMPERATURE.toDouble(),
            maxTokens = MAX_NEW_TOKENS,
        )

    val inputModality = InputModality.Text
    val outputModality = OutputModality.Generation
}
