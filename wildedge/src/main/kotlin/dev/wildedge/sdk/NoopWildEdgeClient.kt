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
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        parent: SpanContext?,
        runId: String?,
        agentId: String?,
        block: (SpanContext) -> T,
    ): T = runSpan(
        name = name,
        traceId = parent?.traceId ?: UUID.randomUUID().toString(),
        parentSpanId = parent?.spanId,
        kind = kind,
        attributes = attributes,
        runId = runId ?: parent?.runId,
        agentId = agentId ?: parent?.agentId,
        block = block,
    )

    override fun openSpan(
        name: String,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        parent: SpanContext?,
        runId: String?,
        agentId: String?,
    ): Span = Span(
        SpanContext(
            traceId = parent?.traceId ?: UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
            parentSpanId = parent?.spanId,
            kind = kind,
            runId = runId ?: parent?.runId,
            agentId = agentId ?: parent?.agentId,
            owner = this,
        ),
        onClose = { _, _ -> },
    )

    override fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        runId: String?,
        agentId: String?,
        block: (SpanContext) -> T,
    ): T = block(
        SpanContext(
            traceId = traceId,
            spanId = UUID.randomUUID().toString(),
            parentSpanId = parentSpanId,
            kind = kind,
            runId = runId,
            agentId = agentId,
            owner = this,
        )
    )

    override fun flush(timeoutMs: Long) = Unit
    override fun close(timeoutMs: Long) = Unit
    override val pendingCount: Int get() = 0
}
