package dev.wildedge.sdk

/** Entry point for recording on-device ML inference metrics. */
interface WildEdgeClient {
    /** Registers a model and returns a [ModelHandle] for tracking its lifecycle. */
    fun registerModel(modelId: String, info: ModelInfo): ModelHandle

    /** Records a system-level memory pressure warning. */
    fun trackMemoryWarning(
        level: MemoryWarningLevel,
        memoryAvailableBytes: Long,
        activeModelIds: List<String>,
        triggeredUnload: Boolean,
        unloadedModelId: String? = null,
    )

    /** Wraps [block] in a named trace span and records a span event on completion. */
    fun <T> trace(
        name: String,
        kind: SpanKind = SpanKind.Custom,
        attributes: Map<String, Any?>? = null,
        parent: SpanContext? = null,
        runId: String? = null,
        agentId: String? = null,
        block: (SpanContext) -> T,
    ): T

    /**
     * Opens a span that must be closed manually via [Span.close].
     *
     * Use this when the span needs to outlast a single call frame, e.g. a session span.
     * For everything else, prefer [trace].
     */
    fun openSpan(
        name: String,
        kind: SpanKind = SpanKind.Custom,
        attributes: Map<String, Any?>? = null,
        parent: SpanContext? = null,
        runId: String? = null,
        agentId: String? = null,
    ): Span

    /** Flushes buffered events to the backend, waiting up to [timeoutMs]. */
    fun flush(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS)

    /** Flushes and shuts down the client, waiting up to [timeoutMs]. */
    fun close(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS)

    /** Number of events queued and not yet delivered. */
    val pendingCount: Int

    /** Factory methods for [WildEdgeClient]. */
    companion object {
        /** Returns a no-op client that silently discards all events. */
        fun noop(): WildEdgeClient = NoopWildEdgeClient()
    }
}
