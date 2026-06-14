package dev.wildedge.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentConsumerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun host() = server.url("/").toString().trimEnd('/')

    private fun attachmentFile() = java.io.File(tmp.newFolder("attachments"), "store.blob")

    private fun pendingBytes(
        attachmentId: String = "att-1",
        registeredAt: Long = System.currentTimeMillis(),
    ) = PendingAttachment(
        attachmentId = attachmentId,
        inferenceId = "inf-1",
        name = "thumb.png",
        role = "input",
        mimeType = "image/png",
        byteSize = 3L,
        payload = AttachmentPayloadRef.Bytes(byteArrayOf(1, 2, 3)),
        inferenceTimestamp = "2026-01-01T00:00:00.000Z",
        registeredAt = registeredAt,
    )

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    @Test fun uploadsPendingAttachmentAndRemovesFromQueue() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"upload_url":"$uploadUrl"}]"""))
        server.enqueue(MockResponse().setResponseCode(200))

        val queue = AttachmentQueue(attachmentFile())
        queue.append(pendingBytes())
        val consumer = AttachmentConsumer(
            queue = queue,
            transmitter = AttachmentTransmitter(host(), "test-secret"),
            flushIntervalMs = 20,
        )

        consumer.start()
        try {
            assertTrue(waitUntil { queue.length() == 0 })
            assertEquals(1, consumer.uploadedCount.get())
            assertEquals(0, consumer.dropCount.get())
            assertEquals(0, consumer.permanentFailureCount.get())
        } finally {
            consumer.stop()
        }
    }

    @Test fun permanentFailureDropsAttachmentAndCountsFailure() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        val queue = AttachmentQueue(attachmentFile())
        queue.append(pendingBytes())
        val consumer = AttachmentConsumer(
            queue = queue,
            transmitter = AttachmentTransmitter(host(), "test-secret"),
            flushIntervalMs = 20,
        )

        consumer.start()
        try {
            assertTrue(waitUntil { queue.length() == 0 })
            assertEquals(0, consumer.uploadedCount.get())
            assertEquals(1, consumer.dropCount.get())
            assertEquals(1, consumer.permanentFailureCount.get())
        } finally {
            consumer.stop()
        }
    }

    @Test fun transientFailureLeavesAttachmentQueuedForRetry() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val queue = AttachmentQueue(attachmentFile())
        queue.append(pendingBytes())
        val consumer = AttachmentConsumer(
            queue = queue,
            transmitter = AttachmentTransmitter(host(), "test-secret"),
            flushIntervalMs = 20,
        )

        consumer.start()
        try {
            assertTrue(waitUntil { server.requestCount >= 1 })
            Thread.sleep(100)
            assertEquals(1, queue.length())
            assertEquals(0, consumer.uploadedCount.get())
            assertEquals(0, consumer.dropCount.get())
            assertEquals(0, consumer.permanentFailureCount.get())
        } finally {
            consumer.stop()
        }
    }

    @Test fun evictsStaleAttachmentsWithoutTransmitting() {
        val queue = AttachmentQueue(attachmentFile())
        queue.append(pendingBytes(registeredAt = System.currentTimeMillis() - 10_000))
        val consumer = AttachmentConsumer(
            queue = queue,
            transmitter = AttachmentTransmitter(host(), "test-secret"),
            flushIntervalMs = 20,
            maxAttachmentAgeMs = 1_000,
        )

        consumer.start()
        try {
            assertTrue(waitUntil { queue.length() == 0 })
            assertEquals(0, consumer.uploadedCount.get())
            assertEquals(1, consumer.dropCount.get())
            assertEquals(0, consumer.permanentFailureCount.get())
            assertFalse(server.requestCount > 0)
        } finally {
            consumer.stop()
        }
    }
}
