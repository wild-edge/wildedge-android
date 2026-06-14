package dev.wildedge.sdk

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Background worker that periodically evicts stale attachments and uploads queued ones
// sequentially, stopping at the first transient failure so it can be retried next tick.
internal class AttachmentConsumer(
    private val queue: AttachmentQueue,
    private val transmitter: AttachmentTransmitter,
    private val flushIntervalMs: Long = Config.DEFAULT_ATTACHMENT_FLUSH_INTERVAL_MS,
    private val maxAttachmentAgeMs: Long = Config.DEFAULT_MAX_ATTACHMENT_AGE_MS,
    private val log: (String) -> Unit = {},
) {
    private companion object {
        const val BATCH_SIZE = 5
        const val EVICTION_PEEK_SIZE = 50
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wildedge-attachment-consumer").also { it.isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    val uploadedCount = AtomicInteger(0)
    val dropCount = AtomicInteger(0)
    val permanentFailureCount = AtomicInteger(0)

    fun start() {
        executor.scheduleWithFixedDelay(::tick, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun tick() {
        evictStale()

        val pending = queue.peekMany(BATCH_SIZE)
        if (pending.isEmpty()) return

        log("Attachment consumer tick pending=${pending.size}")

        var doneCount = 0
        for (attachment in pending) {
            log("Attachment upload id=${attachment.attachmentId} name=${attachment.name}")
            when (val result = transmitter.transmit(attachment)) {
                is AttachmentUploadResult.Uploaded -> {
                    uploadedCount.incrementAndGet()
                    doneCount++
                    log("Attachment uploaded id=${attachment.attachmentId}")
                }
                is AttachmentUploadResult.PermanentFailure -> {
                    permanentFailureCount.incrementAndGet()
                    dropCount.incrementAndGet()
                    doneCount++
                    log("Attachment permanent failure id=${attachment.attachmentId} error=${result.error}")
                }
                is AttachmentUploadResult.TransientFailure -> {
                    log("Attachment transient failure id=${attachment.attachmentId}, retrying next tick")
                    break
                }
            }
        }

        if (doneCount > 0) {
            queue.removeFirstN(doneCount)
            log("Attachment consumer done uploaded=${uploadedCount.get()} dropped=${dropCount.get()}")
        }
    }

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - maxAttachmentAgeMs
        val stale = queue.peekMany(EVICTION_PEEK_SIZE).takeWhile { it.registeredAt < cutoff }
        if (stale.isEmpty()) return
        queue.removeFirstN(stale.size)
        dropCount.addAndGet(stale.size)
        log("Attachment consumer evicted=${stale.size} (age > ${maxAttachmentAgeMs}ms)")
    }
}
