package dev.wildedge.sdk

/** Sampling and decoding parameters supplied to a generative model at inference time. */
data class GenerationConfig(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val repetitionPenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val seed: Int? = null,
    val stopSequencesCount: Int? = null,
) {
    internal fun toMap(): Map<String, Any> = buildMap {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        topK?.let { put("top_k", it) }
        maxTokens?.let { put("max_tokens", it) }
        repetitionPenalty?.let { put("repetition_penalty", it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        seed?.let { put("seed", it) }
        stopSequencesCount?.let { put("stop_sequences_count", it) }
    }
}
