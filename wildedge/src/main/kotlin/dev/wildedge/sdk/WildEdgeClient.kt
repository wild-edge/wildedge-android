package dev.wildedge.sdk

interface WildEdgeClient {
    fun registerModel(modelId: String, info: ModelInfo): ModelHandle
    fun trackMemoryWarning(
        level: MemoryWarningLevel,
        memoryAvailableBytes: Long,
        activeModelIds: List<String>,
        triggeredUnload: Boolean,
        unloadedModelId: String? = null,
    )
    fun <T> trace(name: String, attributes: Map<String, Any?>? = null, block: (SpanContext) -> T): T
    fun flush(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS)
    fun close(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS)
    val pendingCount: Int

    companion object {
        fun noop(): WildEdgeClient = NoopWildEdgeClient()
    }
}
