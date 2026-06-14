package dev.wildedge.sdk

import java.util.UUID

/** A file or in-memory data attachment associated with an inference event. */
@Suppress("ArrayInDataClass")
data class InferenceAttachment(
    val name: String,
    val payload: Payload,
    val role: Role = Role.Input,
    val attachmentId: String = UUID.randomUUID().toString(),
) {
    /** Whether the attachment was provided to, or produced by, the model. */
    enum class Role(internal val value: String) {
        Input("input"),
        Output("output"),
    }

    /** The attachment's content, either as in-memory bytes or a reference to a file on disk. */
    sealed class Payload {
        /** Raw bytes held in memory, e.g. a small image or audio clip. */
        data class Data(val bytes: ByteArray, val mimeType: String) : Payload()

        /** A file on disk that will be streamed during upload. */
        data class File(val file: java.io.File, val mimeType: String) : Payload()
    }
}
