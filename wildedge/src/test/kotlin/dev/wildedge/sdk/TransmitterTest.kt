package dev.wildedge.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class TransmitterTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun transmitter() = Transmitter(server.url("/").toString().trimEnd('/'), "test-secret")

    private fun recordedBody(): String {
        val req = server.takeRequest()
        return GZIPInputStream(ByteArrayInputStream(req.body.readByteArray())).bufferedReader().readText()
    }

    @Test fun sends202AndParsesResponse() {
        server.enqueue(MockResponse()
            .setResponseCode(202)
            .setBody("""{"status":"accepted","batch_id":"b1","events_accepted":5,"events_rejected":0}"""))

        val resp = transmitter().send("""{"events":[]}""")
        assertEquals("accepted", resp.status)
        assertEquals("b1", resp.batchId)
        assertEquals(5, resp.eventsAccepted)
        assertEquals(0, resp.eventsRejected)
    }

    @Test fun compressesBodyWithGzip() {
        server.enqueue(MockResponse()
            .setResponseCode(202)
            .setBody("""{"status":"accepted","batch_id":"b1","events_accepted":1,"events_rejected":0}"""))

        transmitter().send("""{"hello":"world"}""")

        val req = server.takeRequest()
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        val body = GZIPInputStream(ByteArrayInputStream(req.body.readByteArray())).bufferedReader().readText()
        assertEquals("""{"hello":"world"}""", body)
    }

    @Test fun setsProjectSecretHeader() {
        server.enqueue(MockResponse()
            .setResponseCode(202)
            .setBody("""{"status":"accepted","batch_id":"b1","events_accepted":0,"events_rejected":0}"""))

        transmitter().send("{}")
        assertEquals("test-secret", server.takeRequest().getHeader("X-Project-Secret"))
    }

    @Test fun throwsTransmitErrorOn500() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        assertThrows(TransmitError::class.java) { transmitter().send("{}") }
    }

    @Test fun throwsTransmitErrorOn429() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        assertThrows(TransmitError::class.java) { transmitter().send("{}") }
    }

    @Test fun returns400AsPermanentError() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))
        val resp = transmitter().send("{}")
        assertEquals("rejected", resp.status)
    }

    @Test fun returns401AsUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val resp = transmitter().send("{}")
        assertEquals("unauthorized", resp.status)
    }
}
