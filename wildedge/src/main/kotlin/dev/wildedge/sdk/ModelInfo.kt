package dev.wildedge.sdk

data class ModelInfo(
    val modelName: String,
    val modelVersion: String,
    val modelSource: String,
    val modelFormat: String,
    val modelFamily: String? = null,
    val quantization: String? = null,
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
