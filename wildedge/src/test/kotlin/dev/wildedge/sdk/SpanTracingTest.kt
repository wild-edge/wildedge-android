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
}
