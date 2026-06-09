package dev.wildedge.sdk

/** Metadata returned by a cloud LLM API response. */
data class ApiMeta(
    /** The model name returned in the response. Omit when identical to the registered model ID. */
    val resolvedModelId: String? = null,
    /** Opaque backend version string from the provider. Changes on silent backend updates. */
    val systemFingerprint: String? = null,
    /** Request tier as reported by the provider (e.g. "default", "scale", "flex"). */
    val serviceTier: String? = null,
) {
    internal fun toMap(): Map<String, Any> = buildMap {
        resolvedModelId?.let { put("resolved_model_id", it) }
        systemFingerprint?.let { put("system_fingerprint", it) }
        serviceTier?.let { put("service_tier", it) }
    }
}
