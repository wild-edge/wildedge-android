package dev.wildedge.sdk

internal interface SpanOwner {
    fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        block: (SpanContext) -> T,
    ): T
}

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

enum class SpanStatus(val value: String) {
    Ok("ok"),
    Error("error"),
}

class SpanContext internal constructor(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val kind: SpanKind,
    var status: SpanStatus = SpanStatus.Ok,
    private val owner: SpanOwner,
) {
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
        block = block,
    )
}
