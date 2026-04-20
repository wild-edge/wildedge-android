package dev.wildedge.sdk

internal interface SpanOwner {
    fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        runId: String? = null,
        agentId: String? = null,
        block: (SpanContext) -> T,
    ): T
}

/** Type of work a span represents. */
enum class SpanKind(val value: String) {
    AgentStep("agent_step"),
    Tool("tool"),
    Retrieval("retrieval"),
    Memory("memory"),
    Router("router"),
    Guardrail("guardrail"),
    Cache("cache"),
    Eval("eval"),
    Custom("custom"),
}

/** Outcome of a completed span. */
enum class SpanStatus(val value: String) {
    Ok("ok"),
    Error("error"),
}

/** Open-ended span returned by [WildEdgeClient.openSpan]. Must be closed manually. */
class Span internal constructor(
    val context: SpanContext,
    private val onClose: (SpanContext, Long) -> Unit,
) : AutoCloseable {
    private val startMs = System.currentTimeMillis()

    override fun close() = onClose(context, System.currentTimeMillis() - startMs)
}

/** Context of an active span. Obtain via [WildEdgeClient.trace] or [WildEdgeClient.openSpan]. */
class SpanContext internal constructor(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val kind: SpanKind,
    var status: SpanStatus = SpanStatus.Ok,
    val runId: String? = null,
    val agentId: String? = null,
    private val owner: SpanOwner,
) {
    /** Creates a child span nested within this span. */
    fun <T> span(
        name: String,
        kind: SpanKind = SpanKind.Custom,
        attributes: Map<String, Any?>? = null,
        block: (SpanContext) -> T,
    ): T = owner.runSpan(
        name = name,
        traceId = traceId,
        parentSpanId = spanId,
        kind = kind,
        attributes = attributes,
        runId = runId,
        agentId = agentId,
        block = block,
    )
}
