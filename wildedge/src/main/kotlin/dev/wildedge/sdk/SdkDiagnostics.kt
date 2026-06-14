package dev.wildedge.sdk

/** Point-in-time diagnostics snapshot of the SDK's internal state. */
data class SdkDiagnostics(
    /** Estimated JVM heap bytes occupied by all queued events (ART object-graph walk). */
    val eventQueueSizeBytes: Long,
    /** Total size in bytes of all queued events serialized as UTF-8 JSON. */
    val eventQueueJsonBytes: Long,
    /** Number of attachments queued and not yet uploaded. */
    val attachmentQueueCount: Int = 0,
    /** Cumulative count of attachments uploaded successfully. */
    val attachmentUploadedCount: Int = 0,
    /** Cumulative count of attachments dropped (stale eviction or permanent failure). */
    val attachmentDropCount: Int = 0,
    /** Cumulative count of attachments dropped due to a permanent transmit failure (401/400). */
    val attachmentPermanentFailureCount: Int = 0,
)
