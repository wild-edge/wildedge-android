package dev.wildedge.sdk

internal interface SpanOwner {
    fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        attributes: Map<String, Any?>?,
        block: (SpanContext) -> T,
    ): T
}

class SpanContext internal constructor(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    private val owner: SpanOwner,
) {
    fun <T> span(name: String, attributes: Map<String, Any?>? = null, block: (SpanContext) -> T): T =
        owner.runSpan(name = name, traceId = traceId, parentSpanId = spanId, attributes = attributes, block = block)
}
