package dev.wildedge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentIntegrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun attachmentFile() = java.io.File(tmp.newFolder("attachments"), "store.blob")

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun attachment(name: String, role: InferenceAttachment.Role = InferenceAttachment.Role.Input) =
        InferenceAttachment(
            name = name,
            role = role,
            payload = InferenceAttachment.Payload.Data(byteArrayOf(1, 2, 3), "image/png"),
        )

    private fun clientWithAttachmentQueue(
        maxAttachmentsPerInference: Int = Config.DEFAULT_MAX_ATTACHMENTS_PER_INFERENCE,
        maxAttachmentSizeBytes: Long = Config.DEFAULT_MAX_ATTACHMENT_SIZE_BYTES,
        attachmentFilter: ((InferenceAttachment) -> Boolean)? = null,
    ): Pair<WildEdgeClient, AttachmentQueue> {
        val attachmentQueue = AttachmentQueue(attachmentFile())
        val client = WildEdge(
            noop = false,
            queue = EventQueue(),
            registry = ModelRegistry(),
            consumer = null,
            hardwareSampler = null,
            debug = false,
            attachmentQueue = attachmentQueue,
            attachmentConsumer = null,
            maxAttachmentsPerInference = maxAttachmentsPerInference,
            maxAttachmentSizeBytes = maxAttachmentSizeBytes,
            attachmentFilter = attachmentFilter,
        )
        return client to attachmentQueue
    }

    private fun handle(client: WildEdgeClient): ModelHandle = client.registerModel(
        modelId = "attachment-model",
        info = ModelInfo(
            modelName = "Attachment Model",
            modelVersion = "1.0",
            modelSource = "local",
            modelFormat = "tflite",
            inputModality = InputModality.Image,
            outputModality = OutputModality.Classification,
        ),
    )

    @Test fun trackInferenceWithoutAttachmentsDoesNotEnqueue() {
        val (client, queue) = clientWithAttachmentQueue()
        handle(client).trackInference(durationMs = 10)

        assertFalse(waitUntil(timeoutMs = 200) { queue.length() > 0 })
        assertEquals(0, client.diagnostics.attachmentQueueCount)
    }

    @Test fun trackInferenceEnqueuesAttachments() {
        val (client, queue) = clientWithAttachmentQueue()
        handle(client).trackInference(durationMs = 10, attachments = listOf(attachment("a"), attachment("b")))

        assertTrue(waitUntil { queue.length() == 2 })
        assertEquals(2, client.diagnostics.attachmentQueueCount)
        assertEquals(listOf("a", "b"), queue.peekMany(2).map { it.name })
    }

    @Test fun enqueuedAttachmentIsLinkedToInferenceId() {
        val (client, queue) = clientWithAttachmentQueue()
        val inferenceId = handle(client).trackInference(durationMs = 10, attachments = listOf(attachment("a")))

        assertTrue(waitUntil { queue.length() == 1 })
        assertEquals(inferenceId, queue.peekMany(1).single().inferenceId)
    }

    @Test fun maxAttachmentsPerInferenceTruncatesExcessAttachments() {
        val (client, queue) = clientWithAttachmentQueue(maxAttachmentsPerInference = 2)
        handle(client).trackInference(
            durationMs = 10,
            attachments = listOf(attachment("a"), attachment("b"), attachment("c")),
        )

        assertTrue(waitUntil { queue.length() == 2 })
        assertEquals(listOf("a", "b"), queue.peekMany(2).map { it.name })
    }

    @Test fun oversizedAttachmentsAreDropped() {
        val (client, queue) = clientWithAttachmentQueue(maxAttachmentSizeBytes = 2)
        handle(client).trackInference(durationMs = 10, attachments = listOf(attachment("too-big")))

        assertFalse(waitUntil(timeoutMs = 200) { queue.length() > 0 })
    }

    @Test fun attachmentFilterExcludesNonMatchingAttachments() {
        val (client, queue) = clientWithAttachmentQueue(
            attachmentFilter = { it.role == InferenceAttachment.Role.Input },
        )

        handle(client).trackInference(
            durationMs = 10,
            attachments = listOf(
                attachment("input", InferenceAttachment.Role.Input),
                attachment("output", InferenceAttachment.Role.Output),
            ),
        )

        assertTrue(waitUntil { queue.length() == 1 })
        assertEquals("input", queue.peekMany(1).single().name)
    }

    @Test fun trackInferenceWithFilePayloadStoresFilePath() {
        val file = tmp.newFile("attachment.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val (client, queue) = clientWithAttachmentQueue()

        handle(client).trackInference(
            durationMs = 10,
            attachments = listOf(
                InferenceAttachment(
                    name = "file-attachment",
                    payload = InferenceAttachment.Payload.File(file, "application/octet-stream"),
                ),
            ),
        )

        assertTrue(waitUntil { queue.length() == 1 })
        val pending = queue.peekMany(1).single()
        assertEquals(file.length(), pending.byteSize)
        val ref = pending.payload as AttachmentPayloadRef.FilePath
        assertTrue(java.io.File(ref.path).exists())
    }

    @Test fun noopClientDoesNotEnqueueAttachments() {
        val noop = WildEdgeClient.noop()
        val handle = noop.registerModel(
            modelId = "m",
            info = ModelInfo(
                modelName = "m",
                modelVersion = "1.0",
                modelSource = "local",
                modelFormat = "tflite",
                inputModality = InputModality.Image,
                outputModality = OutputModality.Classification,
            ),
        )

        handle.trackInference(durationMs = 10, attachments = listOf(attachment("a")))

        assertEquals(0, noop.diagnostics.attachmentQueueCount)
    }
}
