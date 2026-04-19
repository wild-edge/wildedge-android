package dev.wildedge.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal data class IngestResponse(
    val status: String,
    val batchId: String,
    val eventsAccepted: Int,
    val eventsRejected: Int,
)

internal class TransmitError(message: String) : IOException(message)

internal class Transmitter(
    private val host: String,
    private val apiKey: String,
    private val timeoutMs: Int = Config.HTTP_TIMEOUT_MS,
) {
    @Suppress("MagicNumber")
    fun send(batchJson: String): IngestResponse {
        val url = URL("${host.trimEnd('/')}/api/ingest")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setRequestProperty("X-Project-Secret", apiKey)
            conn.setRequestProperty("User-Agent", Config.SDK_VERSION)

            GZIPOutputStream(conn.outputStream).use { gz ->
                gz.write(batchJson.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }

            handleResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("MagicNumber")
    private fun handleResponse(code: Int, body: String): IngestResponse {
        return when (code) {
            202 -> IngestResponse(
                status = body.extractJsonString("status") ?: "accepted",
                batchId = body.extractJsonString("batch_id") ?: "",
                eventsAccepted = body.extractJsonInt("events_accepted"),
                eventsRejected = body.extractJsonInt("events_rejected"),
            )
            400 -> IngestResponse("rejected", "", 0, 0)
            401 -> IngestResponse("unauthorized", "", 0, 0)
            404 -> IngestResponse("error", "", 0, 0)
            in 300..399 -> IngestResponse("error", "", 0, 0)
            429 -> throw TransmitError("HTTP $code: ${body.take(Config.ERROR_MSG_MAX_LEN)}")
            in 400..499 -> IngestResponse("rejected", "", 0, 0)
            in 500..599 -> throw TransmitError("HTTP $code: ${body.take(Config.ERROR_MSG_MAX_LEN)}")
            else -> throw TransmitError("Unexpected HTTP $code")
        }
    }
}
