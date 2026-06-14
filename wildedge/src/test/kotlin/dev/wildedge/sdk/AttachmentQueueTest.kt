package dev.wildedge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun attachmentFile() = java.io.File(tmp.newFolder("attachments"), "store.blob")

    private fun pendingBytes(
        attachmentId: String = "att-1",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        registeredAt: Long = 1_000L,
    ) = PendingAttachment(
        attachmentId = attachmentId,
        inferenceId = "inf-1",
        name = "thumbnail.png",
        role = "input",
        mimeType = "image/png",
        byteSize = bytes.size.toLong(),
        payload = AttachmentPayloadRef.Bytes(bytes),
        inferenceTimestamp = "2026-01-01T00:00:00.000Z",
        registeredAt = registeredAt,
    )

    @Test fun lengthIsZeroForEmptyQueue() {
        val queue = AttachmentQueue(attachmentFile())
        assertEquals(0, queue.length())
        assertTrue(queue.peekMany(5).isEmpty())
    }

    @Test fun appendAndPeekRoundTripsWithFileStrategy() {
        val queue = AttachmentQueue(attachmentFile(), AttachmentStorageStrategy.File)
        val attachment = pendingBytes()

        queue.append(attachment)

        assertEquals(1, queue.length())
        val peeked = queue.peekMany(5).single()
        assertEquals(attachment.attachmentId, peeked.attachmentId)
        assertEquals(attachment.inferenceId, peeked.inferenceId)
        assertEquals(attachment.name, peeked.name)
        assertEquals(attachment.role, peeked.role)
        assertEquals(attachment.mimeType, peeked.mimeType)
        assertEquals(attachment.byteSize, peeked.byteSize)
        assertEquals(attachment.inferenceTimestamp, peeked.inferenceTimestamp)
        assertEquals(attachment.registeredAt, peeked.registeredAt)

        val ref = peeked.payload as AttachmentPayloadRef.FilePath
        assertTrue(java.io.File(ref.path).exists())
        assertEquals(listOf<Byte>(1, 2, 3), java.io.File(ref.path).readBytes().toList())
    }

    @Test fun appendAndPeekRoundTripsWithInlineStrategy() {
        val queue = AttachmentQueue(attachmentFile(), AttachmentStorageStrategy.Inline)
        val attachment = pendingBytes(bytes = byteArrayOf(4, 5, 6, 7))

        queue.append(attachment)

        val peeked = queue.peekMany(5).single()
        val ref = peeked.payload as AttachmentPayloadRef.Bytes
        assertEquals(listOf<Byte>(4, 5, 6, 7), ref.data.toList())
    }

    @Test fun peekManyPreservesFifoOrder() {
        val queue = AttachmentQueue(attachmentFile())
        queue.append(pendingBytes(attachmentId = "a"))
        queue.append(pendingBytes(attachmentId = "b"))
        queue.append(pendingBytes(attachmentId = "c"))

        val ids = queue.peekMany(10).map { it.attachmentId }

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test fun peekManyRespectsLimit() {
        val queue = AttachmentQueue(attachmentFile())
        repeat(3) { queue.append(pendingBytes(attachmentId = "att-$it")) }

        assertEquals(2, queue.peekMany(2).size)
    }

    @Test fun removeFirstNDeletesEntriesAndManagedFiles() {
        val queue = AttachmentQueue(attachmentFile(), AttachmentStorageStrategy.File)
        queue.append(pendingBytes(attachmentId = "a"))
        queue.append(pendingBytes(attachmentId = "b"))

        val firstFilePath = (queue.peekMany(1).single().payload as AttachmentPayloadRef.FilePath).path

        queue.removeFirstN(1)

        assertEquals(1, queue.length())
        assertFalse(java.io.File(firstFilePath).exists())
        assertEquals("b", queue.peekMany(1).single().attachmentId)
    }

    @Test fun removeFirstNIsNoOpWhenQueueEmpty() {
        val queue = AttachmentQueue(attachmentFile())
        queue.removeFirstN(5)
        assertEquals(0, queue.length())
    }

    @Test fun appendWithUserProvidedFileDoesNotCopyBytes() {
        val sourceFile = tmp.newFile("source.bin")
        sourceFile.writeBytes(byteArrayOf(9, 8, 7))

        val queue = AttachmentQueue(attachmentFile())
        queue.append(
            pendingBytes(attachmentId = "file-attachment").copy(
                payload = AttachmentPayloadRef.FilePath(sourceFile.absolutePath),
            ),
        )

        val ref = queue.peekMany(1).single().payload as AttachmentPayloadRef.FilePath
        assertEquals(sourceFile.absolutePath, ref.path)
    }
}
