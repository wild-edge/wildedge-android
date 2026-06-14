package dev.wildedge.sdk

import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Persistent FIFO queue of attachments awaiting upload, backed by a BlobStore using the
// binary tag-length-value dictionary encoding.
//
// AttachmentStorageStrategy.File: Payload.Data bytes are written to a managed sidecar file
// next to the store; the record holds only the file path. Memory usage while enqueueing is
// minimal and uploads stream directly from disk.
//
// AttachmentStorageStrategy.Inline: Payload.Data bytes are embedded directly in the record.
// No extra file is written, but the entire payload is loaded into memory during peekMany.
// Payload.File attachments always use the file-path path regardless of strategy.
internal class AttachmentQueue(
    file: File,
    private val strategy: AttachmentStorageStrategy = AttachmentStorageStrategy.File,
) {
    private val store = BlobStore(file.absolutePath, BlobStore.CompactionStrategy.Rename)
    private val managedDir = file.parentFile ?: File(".")
    private val lock = ReentrantLock()
    private var count = store.count()

    fun append(attachment: PendingAttachment): Unit = lock.withLock {
        store.append(encodeBlobDictionary(encode(attachment)))
        count++
    }

    fun peekMany(size: Int): List<PendingAttachment> = lock.withLock {
        if (size <= 0) return emptyList()
        val result = ArrayList<PendingAttachment>(size)
        store.makeReader().use { reader ->
            while (result.size < size) {
                val item = reader.next() ?: break
                result.add(decode(decodeBlobDictionary(item.data)))
            }
        }
        result
    }

    fun removeFirstN(n: Int): Unit = lock.withLock {
        val drop = minOf(n, count)
        if (drop <= 0) return
        peekMany(drop).forEach { attachment ->
            if (attachment.payload is AttachmentPayloadRef.FilePath) {
                File(attachment.payload.path).delete()
            }
        }
        store.dropFirst(drop)
        count -= drop
    }

    fun length(): Int = lock.withLock { count }

    private fun encode(a: PendingAttachment): Map<String, Any?> {
        val dict = mutableMapOf<String, Any?>(
            "attachment_id" to a.attachmentId,
            "inference_id" to a.inferenceId,
            "name" to a.name,
            "role" to a.role,
            "mime_type" to a.mimeType,
            "byte_size" to a.byteSize,
            "inference_timestamp" to a.inferenceTimestamp,
            "registered_at" to a.registeredAt,
        )
        when (val payload = a.payload) {
            is AttachmentPayloadRef.Bytes -> {
                if (strategy == AttachmentStorageStrategy.Inline) {
                    dict["payload_kind"] = "inline"
                    dict["payload_data"] = payload.data
                } else {
                    managedDir.mkdirs()
                    val managedFile = File(managedDir, "${UUID.randomUUID()}.bin")
                    managedFile.writeBytes(payload.data)
                    dict["payload_kind"] = "file"
                    dict["file_path"] = managedFile.absolutePath
                }
            }
            is AttachmentPayloadRef.FilePath -> {
                dict["payload_kind"] = "file"
                dict["file_path"] = payload.path
            }
        }
        return dict
    }

    private fun decode(dict: Map<String, Any?>): PendingAttachment {
        val payload = when (dict["payload_kind"] as? String) {
            "inline" -> AttachmentPayloadRef.Bytes(dict["payload_data"] as ByteArray)
            else -> AttachmentPayloadRef.FilePath(dict["file_path"] as String)
        }
        return PendingAttachment(
            attachmentId = dict["attachment_id"] as String,
            inferenceId = dict["inference_id"] as String,
            name = dict["name"] as String,
            role = dict["role"] as String,
            mimeType = dict["mime_type"] as String,
            byteSize = dict["byte_size"] as Long,
            payload = payload,
            inferenceTimestamp = dict["inference_timestamp"] as String,
            registeredAt = dict["registered_at"] as Long,
        )
    }
}
