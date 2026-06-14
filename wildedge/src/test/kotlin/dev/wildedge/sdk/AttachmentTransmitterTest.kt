package dev.wildedge.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentTransmitterTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun host() = server.url("/").toString().trimEnd('/')

    private fun transmitter() = AttachmentTransmitter(host(), "test-secret")

    private fun bytesAttachment(bytes: ByteArray = byteArrayOf(1, 2, 3)) = PendingAttachment(
        attachmentId = "att-1",
        inferenceId = "inf-1",
        name = "thumb.png",
        role = "input",
        mimeType = "image/png",
        byteSize = bytes.size.toLong(),
        payload = AttachmentPayloadRef.Bytes(bytes),
        inferenceTimestamp = "2026-01-01T00:00:00.000Z",
        registeredAt = 1_000L,
    )

    private fun presignResponse(uploadUrl: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""[{"upload_url":"$uploadUrl"}]""")

    @Test fun uploadsViaPresignThenPut() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(AttachmentUploadResult.Uploaded, result)

        val presignReq = server.takeRequest()
        assertEquals("/api/attachments/presign", presignReq.path)
        assertEquals("test-secret", presignReq.getHeader("X-Project-Secret"))
        assertTrue(presignReq.body.readUtf8().contains("\"attachment_id\":\"att-1\""))

        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("image/png", putReq.getHeader("Content-Type"))
        assertEquals(listOf<Byte>(1, 2, 3), putReq.body.readByteArray().toList())
    }

    @Test fun uploadsFromFilePayload() {
        val file = tmp.newFile("payload.bin")
        file.writeBytes(byteArrayOf(9, 9, 9, 9))
        val attachment = bytesAttachment().copy(
            payload = AttachmentPayloadRef.FilePath(file.absolutePath),
            byteSize = file.length(),
        )
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = transmitter().transmit(attachment)

        assertEquals(AttachmentUploadResult.Uploaded, result)
        server.takeRequest()
        val putReq = server.takeRequest()
        assertEquals(listOf<Byte>(9, 9, 9, 9), putReq.body.readByteArray().toList())
    }

    @Test fun presign400IsPermanentBadPayload() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(
            AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.BadPayload),
            result,
        )
        assertEquals(1, server.requestCount)
    }

    @Test fun presign401IsPermanentUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(
            AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.Unauthorized),
            result,
        )
    }

    @Test fun presign403IsPermanentUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(
            AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.Unauthorized),
            result,
        )
    }

    @Test fun presign500IsTransientFailure() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(AttachmentUploadResult.TransientFailure, result)
    }

    @Test fun putReturning400IsPermanentBadPayload() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(400))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(
            AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.BadPayload),
            result,
        )
    }

    @Test fun putReturning401IsPermanentUnauthorized() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(
            AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.Unauthorized),
            result,
        )
    }

    @Test fun putReturning429IsTransientFailure() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(429))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(AttachmentUploadResult.TransientFailure, result)
    }

    @Test fun putReturning500IsTransientFailure() {
        val uploadUrl = server.url("/upload/att-1").toString()
        server.enqueue(presignResponse(uploadUrl))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = transmitter().transmit(bytesAttachment())

        assertEquals(AttachmentUploadResult.TransientFailure, result)
    }
}
