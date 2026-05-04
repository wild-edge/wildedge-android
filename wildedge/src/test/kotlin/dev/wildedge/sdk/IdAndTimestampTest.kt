package dev.wildedge.sdk

import dev.wildedge.sdk.events.newId
import dev.wildedge.sdk.events.toIsoString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdAndTimestampTest {

    // --- newId ---

    @Test fun newId_hasUuidLayout() {
        val id = newId()
        assertEquals(36, id.length)
        assertEquals('-', id[8])
        assertEquals('-', id[13])
        assertEquals('-', id[18])
        assertEquals('-', id[23])
    }

    @Test fun newId_containsOnlyHexAndDashes() {
        val id = newId()
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' || it == '-' })
    }

    @Test fun newId_isUnique() {
        val ids = (1..1000).map { newId() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test fun newId_isUniqueAcrossThreads() {
        val ids = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(1000)
        val pool = Executors.newFixedThreadPool(8)
        repeat(1000) { pool.submit { ids.add(newId()); latch.countDown() } }
        assertTrue("tasks did not finish in time", latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1000, ids.size)
    }

    // --- Long.toIsoString ---

    @Test fun toIsoString_epoch0IsUnixEpoch() {
        assertEquals("1970-01-01T00:00:00.000Z", 0L.toIsoString())
    }

    @Test fun toIsoString_knownTimestamp() {
        // 2024-01-15T11:30:00.000Z = 1705318200000 ms
        assertEquals("2024-01-15T11:30:00.000Z", 1705318200000L.toIsoString())
    }

    @Test fun toIsoString_preservesMilliseconds() {
        val result = 1705318200123L.toIsoString()
        assertTrue(result.endsWith(".123Z"))
    }

    @Test fun toIsoString_isThreadSafe() {
        val results = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(500)
        val pool = Executors.newFixedThreadPool(8)
        repeat(500) { pool.submit { results.add(1705318200000L.toIsoString()); latch.countDown() } }
        assertTrue("tasks did not finish in time", latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(setOf("2024-01-15T11:30:00.000Z"), results)
    }

    // --- Event builder: timestamp stored as Long, converts to ISO at serialization ---

    @Test fun buildInferenceEvent_timestampIsLong() {
        val before = System.currentTimeMillis()
        val event = dev.wildedge.sdk.events.buildInferenceEvent(modelId = "m", durationMs = 10)
        val after = System.currentTimeMillis()
        val ts = event["timestamp"] as Long
        assertTrue(ts in before..after)
    }

    @Test fun buildInferenceEvent_eventIdHasUuidLayout() {
        val event = dev.wildedge.sdk.events.buildInferenceEvent(modelId = "m", durationMs = 10)
        val id = event["event_id"] as String
        assertEquals(36, id.length)
        assertEquals('-', id[8])
    }

    @Test fun buildModelLoadEvent_timestampIsLong() {
        val before = System.currentTimeMillis()
        val event = dev.wildedge.sdk.events.buildModelLoadEvent(modelId = "m", durationMs = 10)
        val after = System.currentTimeMillis()
        val ts = event["timestamp"] as Long
        assertTrue(ts in before..after)
    }
}
