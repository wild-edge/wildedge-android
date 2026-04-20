package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Test

class SpanTracingTest {

    private fun setup(): Pair<WildEdge, EventQueue> {
        val queue = EventQueue()
        val we = WildEdge(
            noop = false,
            queue = queue,
            registry = ModelRegistry(),
            consumer = null,
            hardwareSampler = null,
            debug = false,
        )
        return we to queue
    }

    private fun EventQueue.events() = peekMany(100)

    @Test fun traceEmitsSpanEvent() {
        val (we, queue) = setup()
        we.trace("my-op") { }
        val span = queue.events().single { it["event_type"] == "span" }

        @Suppress("UNCHECKED_CAST")
        val body = span["span"] as Map<String, Any?>
        assertEquals("my-op", (span["span"] as Map<*, *>)["name"])
        assertEquals("custom", body["kind"])
        assertEquals("ok", body["status"])
        assertNotNull(span["trace_id"])
        assertNotNull(span["span_id"])
        assertNull(span["parent_span_id"])
    }

    @Test fun traceUsesExplicitKind() {
        val (we, queue) = setup()
        we.trace("my-op", kind = SpanKind.Tool) { }
        val span = queue.events().single { it["event_type"] == "span" }

        @Suppress("UNCHECKED_CAST")
        val body = span["span"] as Map<String, Any?>
        assertEquals("tool", body["kind"])
    }

    @Test fun failedSpanHasErrorStatus() {
        val (we, queue) = setup()
        try {
            we.trace("my-op") {
                error("boom")
            }
            fail("Expected exception")
        } catch (_: IllegalStateException) {
            // expected
        }
        val span = queue.events().single { it["event_type"] == "span" }

        @Suppress("UNCHECKED_CAST")
        val body = span["span"] as Map<String, Any?>
        assertEquals("error", body["status"])
    }

    @Test fun nestedSpanHasCorrectParentAndTraceId() {
        val (we, queue) = setup()
        we.trace("outer") { outer ->
            outer.span("inner") { }
        }
        val spans = queue.events().filter { it["event_type"] == "span" }
        val inner = spans.first { (it["span"] as Map<*, *>)["name"] == "inner" }
        val outer = spans.first { (it["span"] as Map<*, *>)["name"] == "outer" }
        assertEquals(outer["span_id"], inner["parent_span_id"])
        assertEquals(outer["trace_id"], inner["trace_id"])
    }

    @Test fun inferenceInsideTracePicksUpTraceId() {
        val (we, queue) = setup()
        val handle = we.registerModel("m", ModelInfo("M", "1", "local", "tflite"))
        we.trace("op") { handle.trackInference(durationMs = 5) }
        val events = queue.events()
        val inf = events.first { it["event_type"] == "inference" }
        val span = events.first { it["event_type"] == "span" }
        assertEquals(span["trace_id"], inf["trace_id"])
        assertEquals(span["span_id"], inf["parent_span_id"])
    }

    @Test fun inferenceOutsideTraceHasNoTraceId() {
        val (we, queue) = setup()
        val handle = we.registerModel("m", ModelInfo("M", "1", "local", "tflite"))
        handle.trackInference(durationMs = 5)
        val inf = queue.events().first { it["event_type"] == "inference" }
        assertNull(inf["trace_id"])
        assertNull(inf["parent_span_id"])
    }

    @Test fun threadLocalClearedAfterTrace() {
        val (we, queue) = setup()
        val handle = we.registerModel("m", ModelInfo("M", "1", "local", "tflite"))
        we.trace("op") { }
        handle.trackInference(durationMs = 5)
        val inf = queue.events().last { it["event_type"] == "inference" }
        assertNull(inf["trace_id"])
        assertNull(inf["parent_span_id"])
    }

    @Test fun explicitIdsNotOverriddenByActiveSpan() {
        val (we, queue) = setup()
        val handle = we.registerModel("m", ModelInfo("M", "1", "local", "tflite"))
        we.trace("op") {
            handle.trackInference(durationMs = 5, traceId = "explicit-trace", parentSpanId = "explicit-parent")
        }
        val inf = queue.events().first { it["event_type"] == "inference" }
        assertEquals("explicit-trace", inf["trace_id"])
        assertEquals("explicit-parent", inf["parent_span_id"])
    }

    @Test fun spanAttributesAppearedInEvent() {
        val (we, queue) = setup()
        we.trace("op", attributes = mapOf("user_id" to "u1")) { }
        val span = queue.events().single { it["event_type"] == "span" }

        @Suppress("UNCHECKED_CAST")
        val body = span["span"] as Map<String, Any?>
        assertEquals(mapOf("user_id" to "u1"), body["attributes"])
    }

    @Test fun traceReturnsBlockResult() {
        val (we, _) = setup()
        val result = we.trace("op") { 42 }
        assertEquals(42, result)
    }

    // -- parent param on trace() --

    @Test fun traceWithParentInheritsTraceId() {
        val (we, queue) = setup()
        we.trace("outer") { outer ->
            we.trace("inner", parent = outer) { }
        }
        val spans = queue.events().filter { it["event_type"] == "span" }
        val outer = spans.first { (it["span"] as Map<*, *>)["name"] == "outer" }
        val inner = spans.first { (it["span"] as Map<*, *>)["name"] == "inner" }
        assertEquals(outer["trace_id"], inner["trace_id"])
        assertEquals(outer["span_id"], inner["parent_span_id"])
    }

    @Test fun traceWithParentFromDifferentTrace() {
        val (we, queue) = setup()
        val parentCtx = we.trace("session") { it }
        we.trace("turn", parent = parentCtx) { }
        val spans = queue.events().filter { it["event_type"] == "span" }
        val session = spans.first { (it["span"] as Map<*, *>)["name"] == "session" }
        val turn = spans.first { (it["span"] as Map<*, *>)["name"] == "turn" }
        assertEquals(session["trace_id"], turn["trace_id"])
        assertEquals(session["span_id"], turn["parent_span_id"])
    }

    @Test fun traceWithNullParentStartsNewTrace() {
        val (we, queue) = setup()
        we.trace("a") { }
        we.trace("b", parent = null) { }
        val spans = queue.events().filter { it["event_type"] == "span" }
        val a = spans.first { (it["span"] as Map<*, *>)["name"] == "a" }
        val b = spans.first { (it["span"] as Map<*, *>)["name"] == "b" }
        assertNotEquals(a["trace_id"], b["trace_id"])
        assertNull(b["parent_span_id"])
    }

    // -- openSpan --

    @Test fun openSpanEmitsEventOnClose() {
        val (we, queue) = setup()
        val span = we.openSpan("session", SpanKind.AgentStep)
        assertTrue(queue.events().none { it["event_type"] == "span" })
        span.close()
        val event = queue.events().single { it["event_type"] == "span" }
        assertEquals("session", (event["span"] as Map<*, *>)["name"])
        assertEquals("agent_step", (event["span"] as Map<*, *>)["kind"])
        assertNotNull(event["trace_id"])
        assertNotNull(event["span_id"])
        assertNull(event["parent_span_id"])
    }

    @Test fun openSpanWithParentInheritsTraceId() {
        val (we, queue) = setup()
        val session = we.openSpan("session", SpanKind.AgentStep)
        val turn = we.openSpan("turn", SpanKind.AgentStep, parent = session.context)
        turn.close()
        session.close()
        val spans = queue.events().filter { it["event_type"] == "span" }
        val sessionEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "session" }
        val turnEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "turn" }
        assertEquals(sessionEvt["trace_id"], turnEvt["trace_id"])
        assertEquals(sessionEvt["span_id"], turnEvt["parent_span_id"])
    }

    @Test fun traceChildOfOpenSpanSharesTraceId() {
        val (we, queue) = setup()
        val session = we.openSpan("session", SpanKind.AgentStep)
        we.trace("tool", SpanKind.Tool, parent = session.context) { }
        session.close()
        val spans = queue.events().filter { it["event_type"] == "span" }
        val sessionEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "session" }
        val toolEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "tool" }
        assertEquals(sessionEvt["trace_id"], toolEvt["trace_id"])
        assertEquals(sessionEvt["span_id"], toolEvt["parent_span_id"])
    }

    @Test fun openSpanContextHoldsCorrectIds() {
        val (we, _) = setup()
        val span = we.openSpan("s", SpanKind.Tool)
        assertNotNull(span.context.traceId)
        assertNotNull(span.context.spanId)
        assertNull(span.context.parentSpanId)
        assertEquals(SpanKind.Tool, span.context.kind)
        span.close()
    }

    @Test fun openSpanUseClosesOnBlockExit() {
        val (we, queue) = setup()
        we.openSpan("s").use { }
        assertTrue(queue.events().any { it["event_type"] == "span" })
    }

    // -- runId / agentId propagation --

    @Test fun traceWithRunIdEmitsRunIdInEvent() {
        val (we, queue) = setup()
        we.trace("op", runId = "run-1", agentId = "bot") { }
        val span = queue.events().single { it["event_type"] == "span" }
        assertEquals("run-1", span["run_id"])
        assertEquals("bot", span["agent_id"])
    }

    @Test fun openSpanWithRunIdEmitsRunIdInEvent() {
        val (we, queue) = setup()
        we.openSpan("session", runId = "run-2", agentId = "bot").use { }
        val span = queue.events().single { it["event_type"] == "span" }
        assertEquals("run-2", span["run_id"])
        assertEquals("bot", span["agent_id"])
    }

    @Test fun runIdPropagatesFromParentViaTrace() {
        val (we, queue) = setup()
        val session = we.openSpan("session", runId = "run-3", agentId = "bot")
        we.trace("tool", SpanKind.Tool, parent = session.context) { }
        session.close()
        val spans = queue.events().filter { it["event_type"] == "span" }
        val toolEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "tool" }
        assertEquals("run-3", toolEvt["run_id"])
        assertEquals("bot", toolEvt["agent_id"])
    }

    @Test fun runIdPropagatesFromParentViaOpenSpan() {
        val (we, queue) = setup()
        val session = we.openSpan("session", runId = "run-4", agentId = "bot")
        val turn = we.openSpan("turn", parent = session.context)
        turn.close()
        session.close()
        val spans = queue.events().filter { it["event_type"] == "span" }
        val turnEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "turn" }
        assertEquals("run-4", turnEvt["run_id"])
        assertEquals("bot", turnEvt["agent_id"])
    }

    @Test fun runIdPropagatesViaContextSpan() {
        val (we, queue) = setup()
        we.trace("outer", runId = "run-5") { outer ->
            outer.span("inner") { }
        }
        val spans = queue.events().filter { it["event_type"] == "span" }
        val innerEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "inner" }
        assertEquals("run-5", innerEvt["run_id"])
    }

    @Test fun explicitRunIdOverridesParentRunId() {
        val (we, queue) = setup()
        val session = we.openSpan("session", runId = "parent-run")
        we.trace("turn", parent = session.context, runId = "override-run") { }
        session.close()
        val spans = queue.events().filter { it["event_type"] == "span" }
        val turnEvt = spans.first { (it["span"] as Map<*, *>)["name"] == "turn" }
        assertEquals("override-run", turnEvt["run_id"])
    }
}
