package dev.wildedge.sdk

import com.google.ai.client.generativeai.type.Candidate
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.UsageMetadata
import dev.wildedge.sdk.events.TextInputMeta
import dev.wildedge.sdk.integrations.trackWith
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoogleAiTrackingTest {

    private fun captureHandle(): Pair<ModelHandle, MutableList<Map<String, Any?>>> {
        val events = mutableListOf<Map<String, Any?>>()
        val handle = ModelHandle(
            modelId = "gemini-test",
            info = ModelInfo("Gemini", "2.0", "api", "api"),
            publish = { events.add(it.toMap()) },
            hardwareSnapshot = { null },
        )
        return handle to events
    }

    private fun candidateWithText(text: String): Candidate {
        val ctor = Candidate::class.java.declaredConstructors.first()
        ctor.isAccessible = true
        return ctor.newInstance(
            Content(role = "model", parts = listOf(TextPart(text))),
            emptyList<Any>(),
            emptyList<Any>(),
            null,
        ) as Candidate
    }

    private fun response(text: String = "", usageMetadata: UsageMetadata? = null) =
        GenerateContentResponse(listOf(candidateWithText(text)), null, usageMetadata)

    private fun usage(tokensIn: Int, tokensOut: Int) =
        UsageMetadata(tokensIn, tokensOut, tokensIn + tokensOut)

    @Suppress("UNCHECKED_CAST")
    private fun outputMeta(events: List<Map<String, Any?>>): Map<String, Any?>? {
        val inf = events.first { it["event_type"] == "inference" }["inference"] as Map<String, Any?>
        return inf["output_meta"] as Map<String, Any?>?
    }

    @Suppress("UNCHECKED_CAST")
    private fun inferenceMap(events: List<Map<String, Any?>>): Map<String, Any?> =
        events.first { it["event_type"] == "inference" }["inference"] as Map<String, Any?>

    // --- emission ---

    @Test fun emitsInferenceEventOnCompletion() = runBlocking {
        val (handle, events) = captureHandle()
        flow { emit(response("hello")) }.trackWith(handle).collect {}
        assertEquals(1, events.count { it["event_type"] == "inference" })
    }

    @Test fun forwardsAllResponses() = runBlocking {
        val (handle, _) = captureHandle()
        val collected = mutableListOf<GenerateContentResponse>()
        val r1 = response("chunk one")
        val r2 = response("chunk two")
        flow {
            emit(r1)
            emit(r2)
        }.trackWith(handle).collect { collected.add(it) }
        assertEquals(listOf(r1, r2), collected)
    }

    // --- token counts ---

    @Test fun usageMetadataTokensTakePrecedenceOverCharEstimate() = runBlocking {
        val (handle, events) = captureHandle()
        // "hi" → 2 chars → char estimate = 1 token; usageMetadata says 50 out, 10 in
        flow {
            emit(response("hi", usage(tokensIn = 10, tokensOut = 50)))
        }.trackWith(handle).collect {}
        val meta = outputMeta(events)
        assertEquals(10, meta?.get("tokens_in"))
        assertEquals(50, meta?.get("tokens_out"))
    }

    @Test fun charEstimateUsedWhenUsageMetadataAbsent() = runBlocking {
        val (handle, events) = captureHandle()
        // 400 chars → approximateBpeTokenCount = 100
        flow { emit(response("a".repeat(400))) }.trackWith(handle).collect {}
        assertEquals(100, outputMeta(events)?.get("tokens_out"))
    }

    @Test fun zeroUsageMetadataCountsAreIgnored() = runBlocking {
        val (handle, events) = captureHandle()
        // Zero counts must not override the char-based estimate
        flow { emit(response("a".repeat(400), usage(tokensIn = 0, tokensOut = 0))) }.trackWith(handle).collect {}
        assertEquals(100, outputMeta(events)?.get("tokens_out"))
    }

    @Test fun usageMetadataFromFinalChunkWins() = runBlocking {
        val (handle, events) = captureHandle()
        flow {
            emit(response("first"))
            emit(response("second", usage(tokensIn = 20, tokensOut = 30)))
        }.trackWith(handle).collect {}
        val meta = outputMeta(events)
        assertEquals(20, meta?.get("tokens_in"))
        assertEquals(30, meta?.get("tokens_out"))
    }

    @Test fun inputMetaTokenCountUsedForTokensInWhenUsageMetadataAbsent() = runBlocking {
        val (handle, events) = captureHandle()
        val inputMeta = TextInputMeta(charCount = 10, wordCount = 2, tokenCount = 7)
        flow { emit(response("output")) }.trackWith(handle, inputMeta = inputMeta).collect {}
        assertEquals(7, outputMeta(events)?.get("tokens_in"))
    }

    // --- error ---

    @Test fun recordsErrorOnException() = runBlocking {
        val (handle, events) = captureHandle()
        runCatching {
            flow<GenerateContentResponse> { throw IllegalStateException("api error") }
                .trackWith(handle).collect {}
        }
        val inf = inferenceMap(events)
        assertEquals(false, inf["success"])
        assertEquals("IllegalStateException", inf["error_code"])
    }

    @Test fun rethrowsException() = runBlocking {
        val (handle, _) = captureHandle()
        val ex = runCatching {
            flow<GenerateContentResponse> { throw RuntimeException("rethrow") }
                .trackWith(handle).collect {}
        }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals("rethrow", ex!!.message)
    }
}
