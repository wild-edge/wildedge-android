package dev.wildedge.sdk

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class Consumer(
    private val queue: EventQueue,
    private val transmitter: Transmitter,
    private val device: DeviceInfo,
    private val registry: ModelRegistry,
    private val sessionId: String,
    private val createdAt: String,
    private val pendingStore: PendingBatchStore,
    private val deadLetterStore: DeadLetterStore,
    private val batchSize: Int = Config.DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = Config.DEFAULT_FLUSH_INTERVAL_MS,
    private val maxEventAgeMs: Long = Config.DEFAULT_MAX_EVENT_AGE_MS,
    private val lowConfidenceThreshold: Float = Config.DEFAULT_LOW_CONFIDENCE_THRESHOLD,
    private val log: (String) -> Unit = {},
    private val onDeliveryFailure: ((reason: String, droppedCount: Int, queueDepth: Int) -> Unit)? = null,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wildedge-consumer").also {
            it.isDaemon = true
            workerThread = it
        }
    }
    private val drainLock = ReentrantLock()

    @Volatile private var workerThread: Thread? = null
    private val closed = AtomicBoolean(false)

    @Volatile private var lastFlushAt = System.currentTimeMillis()

    @Volatile private var backoffMs = Config.BACKOFF_MIN_MS

    fun start() {
        executor.scheduleWithFixedDelay(
            ::tick,
            0L,
            Config.IDLE_POLL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun flush(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS) {
        if (closed.get()) return
        if (isWorkerThread()) {
            flushLoop(timeoutMs)
            return
        }
        try {
            executor.submit { flushLoop(timeoutMs) }.get()
        } catch (_: RejectedExecutionException) {
            // If executor is already shutting down, do a best-effort inline flush.
            flushLoop(timeoutMs)
        } catch (e: java.util.concurrent.ExecutionException) {
            // Network or other delivery errors are best-effort; don't crash the caller.
            log("flush failed: ${e.cause?.message ?: e.message}")
        }
    }

    fun flushAsync(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS) {
        if (closed.get()) return
        try {
            executor.execute { flushLoop(timeoutMs) }
        } catch (_: RejectedExecutionException) {
            // Already shutting down; ignore.
        }
    }

    fun close(timeoutMs: Long = Config.DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS, blocking: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        if (blocking) {
            if (isWorkerThread()) {
                flushLoop(timeoutMs)
                stopInternal(await = false)
                return
            }
            try {
                executor.submit { flushLoop(timeoutMs) }.get()
            } catch (_: RejectedExecutionException) {
                flushLoop(timeoutMs)
            }
            stopInternal(await = true)
        } else {
            try {
                executor.execute { flushLoop(timeoutMs) }
            } catch (_: RejectedExecutionException) {
                // Executor already closed; nothing else we can do.
            } finally {
                stopInternal(await = false)
            }
        }
    }

    private fun flushLoop(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var localBackoff = Config.BACKOFF_MIN_MS
        while (queue.length() > 0 || pendingStore.hasAny()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val sent = drainLock.withLock { drainOnce() }
            if (sent) {
                localBackoff = Config.BACKOFF_MIN_MS
            } else {
                Thread.sleep(minOf(localBackoff, remaining))
                localBackoff = minOf((localBackoff * Config.BACKOFF_MULTIPLIER).toLong(), Config.BACKOFF_MAX_MS)
            }
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        stopInternal(await = !isWorkerThread())
    }

    private fun stopInternal(await: Boolean) {
        executor.shutdown()
        if (await) executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun isWorkerThread(): Boolean = Thread.currentThread() == workerThread

    private fun tick() {
        val now = System.currentTimeMillis()
        val timeSinceFlush = now - lastFlushAt
        if (queue.length() > 0 || timeSinceFlush >= flushIntervalMs || pendingStore.hasAny()) {
            val sent = drainLock.withLock { drainOnce() }
            if (sent) {
                lastFlushAt = System.currentTimeMillis()
                backoffMs = Config.BACKOFF_MIN_MS
            } else {
                backoffMs = minOf((backoffMs * Config.BACKOFF_MULTIPLIER).toLong(), Config.BACKOFF_MAX_MS)
            }
        }
    }

    private fun drainOnce(): Boolean {
        // Retry pending files first
        val pending = pendingStore.readOldest()
        if (pending != null) {
            val (file, json) = pending
            return try {
                val response = transmitter.send(json)
                if (response.status in listOf("accepted", "partial")) {
                    pendingStore.delete(file)
                    log("pending batch retransmitted ok")
                    true
                } else {
                    deadLetterStore.write("permanent_${response.status}", json)
                    pendingStore.delete(file)
                    true
                }
            } catch (_: TransmitError) { false }
        }

        // Age-expire stale events
        val now = System.currentTimeMillis()
        val events = queue.peekMany(batchSize)
        if (events.isEmpty()) return false

        val stale = events.takeWhile { e ->
            val queuedAt = e["__we_queued_at"] as? Long ?: now
            (now - queuedAt) > maxEventAgeMs
        }
        if (stale.isNotEmpty()) {
            val staleJson = buildBatch(device, registry.snapshot(), stale, sessionId, createdAt, lowConfidenceThreshold)
            deadLetterStore.write("event_age_exceeded", staleJson)
            queue.removeFirstN(stale.size)
            onDeliveryFailure?.invoke("event_age_exceeded", stale.size, queue.length())
            log("dropped ${stale.size} stale event(s)")
            return true
        }

        val batchJson = buildBatch(device, registry.snapshot(), events, sessionId, createdAt, lowConfidenceThreshold)

        log("transmitting ${events.size} event(s)")

        return try {
            val response = transmitter.send(batchJson)
            when (response.status) {
                "accepted", "partial" -> {
                    queue.removeFirstN(events.size)
                    log("accepted=${response.eventsAccepted}")
                    true
                }
                "rejected", "unauthorized", "error" -> {
                    deadLetterStore.write("permanent_${response.status}", batchJson)
                    queue.removeFirstN(events.size)
                    onDeliveryFailure?.invoke(response.status, events.size, queue.length())
                    true
                }
                else -> false
            }
        } catch (_: TransmitError) {
            val spooled = pendingStore.write(batchJson)
            if (spooled) {
                queue.removeFirstN(events.size)
                log("transmit failed; moved ${events.size} event(s) to pending")
                true
            } else {
                log("transmit failed; unable to persist pending batch")
                false
            }
        }
    }
}
