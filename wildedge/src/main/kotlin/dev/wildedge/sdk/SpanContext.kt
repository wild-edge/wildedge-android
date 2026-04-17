package dev.wildedge.sdk

class SpanContext internal constructor(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    private val owner: WildEdge,
) {
    fun <T> span(name: String, attributes: Map<String, Any?>? = null, block: (SpanContext) -> T): T =
        owner.runSpan(name = name, traceId = traceId, parentSpanId = spanId, attributes = attributes, block = block)
}
