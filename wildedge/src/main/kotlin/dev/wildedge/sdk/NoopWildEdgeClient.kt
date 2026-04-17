package dev.wildedge.sdk

import java.util.UUID

internal class NoopWildEdgeClient : WildEdgeClient, SpanOwner {
    override fun registerModel(modelId: String, info: ModelInfo): ModelHandle =
        ModelHandle(modelId, info, {}, { null })

    override fun trackMemoryWarning(
        level: MemoryWarningLevel,
        memoryAvailableBytes: Long,
        activeModelIds: List<String>,
        triggeredUnload: Boolean,
        unloadedModelId: String?,
    ) = Unit

    override fun <T> trace(
        name: String,
        attributes: Map<String, Any?>?,
        block: (SpanContext) -> T,
    ): T = runSpan(
        name = name,
        traceId = UUID.randomUUID().toString(),
        parentSpanId = null,
        attributes = attributes,
        block = block,
    )

    override fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        attributes: Map<String, Any?>?,
        block: (SpanContext) -> T,
    ): T = block(SpanContext(
        traceId = traceId,
        spanId = UUID.randomUUID().toString(),
        parentSpanId = parentSpanId,
        owner = this,
    ))

    override fun flush(timeoutMs: Long) = Unit
    override fun close(timeoutMs: Long) = Unit
    override val pendingCount: Int get() = 0
}
