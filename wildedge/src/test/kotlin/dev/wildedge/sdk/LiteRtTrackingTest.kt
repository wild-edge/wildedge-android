package dev.wildedge.sdk

import dev.wildedge.sdk.integrations.trackWith
import org.junit.Assert.*
import org.junit.Test

class LiteRtTrackingTest {

    private fun captureHandle(): Pair<ModelHandle, MutableList<Map<String, Any?>>> {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = ModelHandle(
            modelId = "test-model",
            info = ModelInfo("TestModel", "1.0", "local", "litertlm"),
            publish = { events.add(it) },
            hardwareSnapshot = { null },
        )
        return handle to events
    }

    private val noop: (String, Boolean, String?) -> Unit = { _, _, _ -> }

    @Test fun forwardsAllCallsToOriginalListener() {
        val (handle, _) = captureHandle()
        val received = mutableListOf<Triple<String, Boolean, String?>>()
        val original: (String, Boolean, String?) -> Unit = { result, done, thinking ->
            received.add(Triple(result, done, thinking))
        }

        val tracked = original.trackWith(handle)
        tracked("hello", false, null)
        tracked(" world", false, "thinking...")
        tracked("", true, null)

        assertEquals(3, received.size)
        assertEquals("hello", received[0].first)
        assertEquals(" world", received[1].first)
        assertTrue(received[2].second) // done=true
    }

    @Test fun tracksInferenceOnDone() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        tracked("token1", false, null)
        tracked("token2", false, null)
        tracked("", true, null)

        val inferenceEvent = events.firstOrNull { it["event_type"] == "inference" }
        assertNotNull("expected inference event on done", inferenceEvent)
    }

    @Test fun doesNotTrackBeforeDone() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        tracked("token", false, null)

        assertTrue(events.none { it["event_type"] == "inference" })
    }

    @Test fun capturesTokenCount() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        tracked("hello world", false, null)
        tracked("foo bar", false, null)
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        val tokensOut = outputMeta?.get("tokens_out") as? Int
        assertNotNull(tokensOut)
        assertTrue(tokensOut!! > 0)
    }

    @Test fun capturesTimeToFirstToken() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        tracked("first token", false, null)
        Thread.sleep(10)
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertNotNull(outputMeta?.get("time_to_first_token_ms"))
    }

    @Test fun noTtftWhenNonEmptyTokenNeverArrives() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        // done=true with no partial results ever
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertNull(outputMeta?.get("time_to_first_token_ms"))
    }

    @Test fun inputMetaTokenCountPassedToEvent() {
        val (handle, events) = captureHandle()
        val inputMeta = dev.wildedge.sdk.events.TextInputMeta(tokenCount = 17)
        val tracked = noop.trackWith(handle, inputMeta)

        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(17, outputMeta?.get("tokens_in"))
    }

    @Test fun charBasedFallbackUsedWhenNoTokenizer() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        // 8 chars total -> 8 / 4.0 = 2 tokens
        tracked("hell", false, null)
        tracked("o!!!", false, null)
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(2, outputMeta?.get("tokens_out"))
    }

    @Test fun partialWordChunksCountedByCharsNotWords() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle)

        // "Hel" + "lo" = 5 chars -> 5/4 = 1 token (not 2 words)
        tracked("Hel", false, null)
        tracked("lo", false, null)
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(1, outputMeta?.get("tokens_out"))
    }

    @Test fun customTokenizerResultUsedForTokensOut() {
        val (handle, events) = captureHandle()
        val tracked = noop.trackWith(handle, tokenizer = { 42 })

        tracked("some output", false, null)
        tracked("", true, null)

        val inference = events.first { it["event_type"] == "inference" }
        @Suppress("UNCHECKED_CAST")
        val outputMeta = (inference["inference"] as Map<String, Any?>)["output_meta"] as Map<String, Any?>?
        assertEquals(42, outputMeta?.get("tokens_out"))
    }

    @Test fun customTokenizerReceivesFullAssembledOutput() {
        val (handle, _) = captureHandle()
        var tokenizerInput: String? = null
        val tracked = noop.trackWith(handle, tokenizer = { text -> tokenizerInput = text; 1 })

        tracked("hello", false, null)
        tracked(" world", false, null)
        tracked("", true, null)

        assertEquals("hello world", tokenizerInput)
    }
}
