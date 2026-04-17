package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Test

class EventQueueTest {

    private fun event(type: String = "inference") =
        mutableMapOf<String, Any?>("event_type" to type, "model_id" to "m1")

    @Test fun addAndPeek() {
        val q = EventQueue()
        q.add(event())
        assertEquals(1, q.length())
        val peeked = q.peekMany(10)
        assertEquals(1, peeked.size)
        assertEquals(1, q.length()) // peek doesn't remove
    }

    @Test fun removeFirstN() {
        val q = EventQueue()
        repeat(5) { q.add(event()) }
        q.removeFirstN(3)
        assertEquals(2, q.length())
    }

    @Test fun overflowDropsOldestInOpportunisticMode() {
        var dropped = 0
        val q = EventQueue(maxSize = 3, onOverflow = { dropped += it })
        repeat(4) { q.add(event()) }
        assertEquals(3, q.length())
        assertEquals(1, dropped)
    }

    @Test fun overflowThrowsInStrictMode() {
        val q = EventQueue(maxSize = 2, strict = true)
        q.add(event())
        q.add(event())
        assertThrows(IllegalStateException::class.java) { q.add(event()) }
    }

    @Test fun injectsQueuedAtAndAttempts() {
        val q = EventQueue()
        val e = event()
        val before = System.currentTimeMillis()
        q.add(e)
        val after = System.currentTimeMillis()
        val peeked = q.peekMany(1).first()
        val queuedAt = peeked["__we_queued_at"] as Long
        assertTrue(queuedAt in before..after)
        assertEquals(0, peeked["__we_attempts"])
    }

    @Test fun peekManyLimitedByQueueSize() {
        val q = EventQueue()
        repeat(3) { q.add(event()) }
        assertEquals(3, q.peekMany(100).size)
    }

    @Test fun clearEmptiesQueue() {
        val q = EventQueue()
        repeat(5) { q.add(event()) }
        q.clear()
        assertEquals(0, q.length())
    }
}
