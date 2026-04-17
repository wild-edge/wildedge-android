package dev.wildedge.sdk

import java.util.LinkedList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class EventQueue(
    private val maxSize: Int = Config.DEFAULT_MAX_QUEUE_SIZE,
    private val strict: Boolean = false,
    private val onOverflow: ((dropped: Int) -> Unit)? = null,
) {
    private val queue: LinkedList<MutableMap<String, Any?>> = LinkedList()
    private val lock = ReentrantLock()

    fun add(event: MutableMap<String, Any?>) {
        lock.withLock {
            if (queue.size >= maxSize) {
                if (strict) error("EventQueue full ($maxSize items)")
                queue.removeFirst()
                onOverflow?.invoke(1)
            }
            event["__we_queued_at"] = System.currentTimeMillis()
            event["__we_attempts"] = 0
            queue.addLast(event)
        }
    }

    fun peekMany(n: Int): List<MutableMap<String, Any?>> = lock.withLock {
        queue.take(n)
    }

    fun removeFirstN(n: Int) {
        lock.withLock {
            repeat(minOf(n, queue.size)) { queue.removeFirst() }
        }
    }

    fun length(): Int = lock.withLock { queue.size }

    fun clear() = lock.withLock { queue.clear() }
}
