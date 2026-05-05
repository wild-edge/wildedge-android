package dev.wildedge.sdk

/** Point-in-time diagnostics snapshot of the SDK's internal state. */
data class SdkDiagnostics(
    /** Estimated JVM heap bytes occupied by all queued events (ART object-graph walk). */
    val eventQueueSizeBytes: Long,
    /** Total size in bytes of all queued events serialized as UTF-8 JSON. */
    val eventQueueJsonBytes: Long,
)
