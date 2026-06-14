package dev.wildedge.sdk

// Reference to where an attachment's bytes live while queued for upload.
@Suppress("ArrayInDataClass")
internal sealed class AttachmentPayloadRef {
    data class Bytes(val data: ByteArray) : AttachmentPayloadRef()
    data class FilePath(val path: String) : AttachmentPayloadRef()
}

// An attachment that has been registered for an inference and is waiting to be uploaded.
internal data class PendingAttachment(
    val attachmentId: String,
    val inferenceId: String,
    val name: String,
    val role: String,
    val mimeType: String,
    val byteSize: Long,
    val payload: AttachmentPayloadRef,
    val inferenceTimestamp: String,
    val registeredAt: Long,
)
