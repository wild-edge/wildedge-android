package dev.wildedge.sdk

import dev.wildedge.sdk.events.buildInferenceEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ConsumerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun accepted() = MockResponse()
        .setResponseCode(202)
        .setBody("""{"status":"accepted","batch_id":"b1","events_accepted":1,"events_rejected":0}""")

    private fun pendingJsonCount(dir: java.io.File): Int =
        dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    private fun makeConsumer(queue: EventQueue = EventQueue()): Consumer {
        val transmitter = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")
        return fakeConsumer(
            queue = queue,
            transmitter = transmitter,
            pendingDir = tmp.newFolder("pending"),
            deadLetterDir = tmp.newFolder("dead"),
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    @Test fun transmitsQueuedEventsOnFlush() {
        server.enqueue(accepted())
        val queue = EventQueue()
        val consumer = makeConsumer(queue)
        consumer.start()

        queue.add(buildInferenceEvent("m1", 10))
        consumer.flush(timeoutMs = 3000)

        assertEquals(0, queue.length())
        assertEquals(1, server.requestCount)
    }

    @Test fun writesToPendingStoreOnTransmitError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        val queue = EventQueue()
        val pendingDir = tmp.newFolder("pending2")
        val deadDir = tmp.newFolder("dead2")
        val transmitter = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")
        val consumer = Consumer(
            queue = queue,
            transmitter = transmitter,
            device = fakeDevice(),
            registry = ModelRegistry(),
            sessionId = "s",
            createdAt = "2026-01-01T00:00:00.000Z",
            pendingStore = PendingBatchStore(pendingDir),
            deadLetterStore = DeadLetterStore(deadDir),
        )

        queue.add(buildInferenceEvent("m1", 10))
        consumer.flush(timeoutMs = 1000)

        assertTrue("pending file should exist", pendingDir.listFiles()?.isNotEmpty() == true)
        assertEquals("events should move out of in-memory queue after spool", 0, queue.length())
    }

    @Test fun retransmitsPendingFileOnNextFlush() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(accepted())
        server.enqueue(accepted())

        val queue = EventQueue()
        val pendingDir = tmp.newFolder("pending3")
        val deadDir = tmp.newFolder("dead3")
        val transmitter = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")
        val pendingStore = PendingBatchStore(pendingDir)
        val consumer = Consumer(
            queue = queue,
            transmitter = transmitter,
            device = fakeDevice(),
            registry = ModelRegistry(),
            sessionId = "s",
            createdAt = "2026-01-01T00:00:00.000Z",
            pendingStore = pendingStore,
            deadLetterStore = DeadLetterStore(deadDir),
        )

        queue.add(buildInferenceEvent("m1", 10))
        consumer.flush(timeoutMs = 1000) // queue fail → spooled, then pending retry fail

        assertTrue(pendingStore.hasAny())
        assertEquals(0, queue.length())
        consumer.flush(timeoutMs = 3000) // retry → 202

        assertFalse("pending file should be cleared after retry", pendingStore.hasAny())
        assertEquals("logical batch should be sent only once after pending succeeds", 3, server.requestCount)
    }

    @Test fun repeatedTransientFailuresDoNotCreateDuplicatePendingFiles() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))

        val queue = EventQueue()
        val pendingDir = tmp.newFolder("pending5")
        val deadDir = tmp.newFolder("dead5")
        val transmitter = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")
        val consumer = Consumer(
            queue = queue,
            transmitter = transmitter,
            device = fakeDevice(),
            registry = ModelRegistry(),
            sessionId = "s",
            createdAt = "2026-01-01T00:00:00.000Z",
            pendingStore = PendingBatchStore(pendingDir),
            deadLetterStore = DeadLetterStore(deadDir),
        )

        queue.add(buildInferenceEvent("m1", 10))
        consumer.flush(timeoutMs = 1000)
        val afterFirstFlush = pendingJsonCount(pendingDir)

        consumer.flush(timeoutMs = 1000)
        val afterSecondFlush = pendingJsonCount(pendingDir)

        assertEquals(0, queue.length())
        assertEquals(1, afterFirstFlush)
        assertEquals("same pending batch should be retried in place", 1, afterSecondFlush)
    }

    @Test fun staleEventsWriteToDeadLetterNotTransmitted() {
        val queue = EventQueue()
        val pendingDir = tmp.newFolder("pending4")
        val deadDir = tmp.newFolder("dead4")
        val transmitter = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")
        val consumer = Consumer(
            queue = queue,
            transmitter = transmitter,
            device = fakeDevice(),
            registry = ModelRegistry(),
            sessionId = "s",
            createdAt = "2026-01-01T00:00:00.000Z",
            pendingStore = PendingBatchStore(pendingDir),
            deadLetterStore = DeadLetterStore(deadDir),
            maxEventAgeMs = 0L, // all events are immediately stale
        )

        queue.add(buildInferenceEvent("m1", 10))
        consumer.flush(timeoutMs = 2000)

        assertEquals(0, server.requestCount)
        assertEquals(0, queue.length())
        assertTrue("dead letter should exist", deadDir.listFiles()?.isNotEmpty() == true)
    }

    @Test fun flushAsyncReturnsQuicklyOnSlowNetwork() {
        server.enqueue(
            accepted()
                .setBodyDelay(1, TimeUnit.SECONDS),
        )
        val queue = EventQueue()
        val consumer = makeConsumer(queue)
        queue.add(buildInferenceEvent("m1", 10))

        val start = System.currentTimeMillis()
        consumer.flushAsync(timeoutMs = 3000)
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue("flushAsync should not block caller", elapsedMs < 200)
        assertTrue("event should be flushed in background", waitUntil(2500) { queue.length() == 0 })
        assertEquals(1, server.requestCount)
    }

    @Test fun closeAsyncReturnsQuicklyOnSlowNetwork() {
        server.enqueue(
            accepted()
                .setBodyDelay(1, TimeUnit.SECONDS),
        )
        val queue = EventQueue()
        val consumer = makeConsumer(queue)
        queue.add(buildInferenceEvent("m1", 10))

        val start = System.currentTimeMillis()
        consumer.close(timeoutMs = 3000, blocking = false)
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue("close(blocking=false) should not block caller", elapsedMs < 200)
        assertTrue("event should be flushed before shutdown completes", waitUntil(2500) { queue.length() == 0 })
        assertEquals(1, server.requestCount)
    }
}
