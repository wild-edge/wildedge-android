package dev.wildedge.sdk

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal sealed class AttachmentUploadResult {
    object Uploaded : AttachmentUploadResult()
    data class PermanentFailure(val error: AttachmentTransmitError) : AttachmentUploadResult()
    object TransientFailure : AttachmentUploadResult()
}

internal enum class AttachmentTransmitError {
    BadPayload,
    Unauthorized,
}

// Two-phase upload: presign() obtains a direct-to-storage upload URL, then putToStorage() PUTs
// the attachment bytes there.
internal class AttachmentTransmitter(
    private val host: String,
    private val apiKey: String,
    private val timeoutMs: Int = Config.ATTACHMENT_HTTP_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    fun transmit(attachment: PendingAttachment): AttachmentUploadResult {
        return when (val outcome = presign(attachment)) {
            is PresignOutcome.Failure -> outcome.result
            is PresignOutcome.Success -> putToStorage(attachment, outcome.uploadUrl)
        }
    }

    private sealed class PresignOutcome {
        data class Success(val uploadUrl: String) : PresignOutcome()
        data class Failure(val result: AttachmentUploadResult) : PresignOutcome()
    }

    @Suppress("MagicNumber")
    private fun presign(attachment: PendingAttachment): PresignOutcome {
        val url = URL("${host.trimEnd('/')}/api/attachments/presign")
        val body = listOf(
            mapOf(
                "attachment_id" to attachment.attachmentId,
                "inference_id" to attachment.inferenceId,
                "role" to attachment.role,
                "content_type" to attachment.mimeType,
                "size_bytes" to attachment.byteSize,
                "inference_timestamp" to attachment.inferenceTimestamp,
            ),
        ).toJsonValue().toByteArray(Charsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Project-Secret", apiKey)
            conn.setRequestProperty("User-Agent", Config.SDK_VERSION)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val responseBody = readBody(conn, code)
            log("Presign response status=$code body=$responseBody")

            when (code) {
                200, 201 -> {
                    val uploadUrl = responseBody.extractJsonString("upload_url")
                    if (uploadUrl != null) {
                        PresignOutcome.Success(uploadUrl)
                    } else {
                        PresignOutcome.Failure(AttachmentUploadResult.TransientFailure)
                    }
                }
                400 -> PresignOutcome.Failure(
                    AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.BadPayload),
                )
                401, 403 -> PresignOutcome.Failure(
                    AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.Unauthorized),
                )
                else -> PresignOutcome.Failure(AttachmentUploadResult.TransientFailure)
            }
        } catch (_: Exception) {
            PresignOutcome.Failure(AttachmentUploadResult.TransientFailure)
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("MagicNumber")
    private fun putToStorage(attachment: PendingAttachment, uploadUrl: String): AttachmentUploadResult {
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", attachment.mimeType)

            writePayload(conn, attachment.payload)

            val code = conn.responseCode
            log("S3 upload response status=$code")

            when (code) {
                200, 201, 202, 204 -> AttachmentUploadResult.Uploaded
                400 -> AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.BadPayload)
                401, 403 -> AttachmentUploadResult.PermanentFailure(AttachmentTransmitError.Unauthorized)
                else -> AttachmentUploadResult.TransientFailure
            }
        } catch (_: Exception) {
            AttachmentUploadResult.TransientFailure
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String = try {
        (if (code in HTTP_OK_RANGE) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun writePayload(conn: HttpURLConnection, payload: AttachmentPayloadRef) {
        when (payload) {
            is AttachmentPayloadRef.Bytes -> conn.outputStream.use { it.write(payload.data) }
            is AttachmentPayloadRef.FilePath -> {
                val file = File(payload.path)
                conn.setFixedLengthStreamingMode(file.length())
                conn.outputStream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            }
        }
    }

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}
