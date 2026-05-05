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

    /** Returns the total UTF-8 byte length of all queued events serialized as JSON. */
    fun estimateJsonBytes(): Long = lock.withLock {
        queue.sumOf { event ->
            event.filterKeys { !it.startsWith("__we_") }.toJson()
                .toByteArray(Charsets.UTF_8).size.toLong()
        }
    }

    /**
     * Estimates the JVM heap bytes consumed by all queued events.
     *
     * Walks the object graph of each event using ART object layout:
     * 8-byte object header, 4-byte compressed references, 8-byte alignment.
     * Includes each LinkedList.Node (24 bytes) that holds the event reference.
     */
    fun estimateSizeBytes(): Long = lock.withLock {
        queue.sumOf { event -> LINKED_LIST_NODE_BYTES + artHeapSize(event) }
    }
}

// ART (Android 5+, 64-bit, compressed references) object layout constants.
// Object header:  klass(4) + monitor(4) = 8 bytes. All objects 8-byte aligned.
private const val OBJECT_HEADER = 8L
private const val REF_FIELD = 4L         // compressed object reference
private const val ARRAY_HEADER = 16L     // header(8) + length(4) + padding(4)

// LinkedList.Node: header(8) + item_ref(4) + next_ref(4) + prev_ref(4) = 20 → pad to 24
private const val LINKED_LIST_NODE_BYTES = 24L

@Suppress("UNCHECKED_CAST")
private fun artHeapSize(obj: Any?): Long = when (obj) {
    null -> 0L

    is String -> {
        // String object: header(8) + count(4) + hash(4) + value_ref(4) = 20 → pad to 24
        // char[]: array_header(16) + 2 bytes per char (conservative; ART compact strings
        //         use 1 byte for ASCII on API 26+, but 2 bytes is the safe upper bound)
        24L + ARRAY_HEADER + obj.length * 2L
    }

    // Primitive wrappers: header(8) + field ≤ 8 bytes → all pad to 16
    is Boolean, is Byte, is Short, is Char, is Int, is Float -> 16L
    is Long, is Double -> 16L

    is Map<*, *> -> {
        // LinkedHashMap object: header(8) + HashMap fields(table_ref,entrySet_ref,size,
        //   modCount,threshold,loadFactor = 4+4+4+4+4+4) + LinkedHashMap fields(head_ref,
        //   tail_ref,accessOrder = 4+4+1) = 43 → pad to 48 bytes
        // table Node[]: array_header(16) + capacity * ref(4)
        //   capacity = next power-of-2 ≥ size/0.75 (min 16 for default initial capacity)
        // LinkedHashMap.Entry per mapping: header(8) + hash(4) + key_ref(4) + value_ref(4)
        //   + next_ref(4) + before_ref(4) + after_ref(4) = 32 bytes (already aligned)
        var cap = 16
        while (cap * 3 / 4 < obj.size) cap = cap shl 1
        val mapObject = 48L
        val tableArray = ARRAY_HEADER + cap * REF_FIELD
        val entries = obj.size * 32L
        val content = (obj as Map<String, Any?>).entries.sumOf { (k, v) ->
            artHeapSize(k) + artHeapSize(v)
        }
        mapObject + tableArray + entries + content
    }

    is List<*> -> {
        // ArrayList: header(8) + elementData_ref(4) + size(4) + modCount(4) = 20 → pad to 24
        // Object[]: array_header(16) + size * ref(4)
        val listObject = 24L
        val backingArray = ARRAY_HEADER + obj.size * REF_FIELD
        val content = obj.sumOf { artHeapSize(it) }
        listObject + backingArray + content
    }

    else -> OBJECT_HEADER  // unknown boxed type; count at least its header
}
