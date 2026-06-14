package dev.wildedge.sdk

/**
 * Controls how attachment payloads are persisted in the on-device upload queue.
 *
 * - [File]: [InferenceAttachment.Payload.Data] payloads are written to a managed file;
 *   the queue record holds only the file path. Memory usage while queueing is minimal
 *   and uploads stream directly from disk.
 * - [Inline]: [InferenceAttachment.Payload.Data] payloads are embedded directly in the
 *   queue record. No extra file is written, but the entire payload is loaded into memory
 *   when the queue is read. Best suited for small payloads (thumbnails, short audio clips,
 *   embeddings). [InferenceAttachment.Payload.File] payloads always use the file-path path
 *   regardless of strategy.
 */
enum class AttachmentStorageStrategy {
    File,
    Inline,
}
