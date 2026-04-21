package dev.wildedge.sdk

/**
 * Metadata describing a registered model.
 *
 * @property modelName Human-readable model name.
 * @property modelVersion Semantic version string.
 * @property modelSource Origin of the model (e.g. "huggingface", "custom").
 * @property modelFormat Runtime format (e.g. "tflite", "onnx", "gguf").
 * @property modelFamily Optional model family (e.g. "llama", "gemma").
 * @property quantization Optional quantization scheme (e.g. "int8", "q4_k_m").
 * @property inputModality Default input modality used when not specified per call.
 * @property outputModality Default output modality used when not specified per call.
 */
data class ModelInfo(
    val modelName: String,
    val modelVersion: String,
    val modelSource: String,
    val modelFormat: String,
    val modelFamily: String? = null,
    val quantization: String? = null,
    val inputModality: InputModality? = null,
    val outputModality: OutputModality? = null,
) {
    internal fun toMap(): Map<String, Any?> = mapOf(
        "model_name" to modelName,
        "model_version" to modelVersion,
        "model_source" to modelSource,
        "model_format" to modelFormat,
        "model_family" to modelFamily,
        "quantization" to quantization,
    ).filterValues { it != null }
}
