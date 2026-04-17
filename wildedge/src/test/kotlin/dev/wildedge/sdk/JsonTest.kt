package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Test

class JsonTest {

    @Test fun serializesStringValues() {
        val json = mapOf("key" to "value").toJson()
        assertEquals("""{"key":"value"}""", json)
    }

    @Test fun serializesNumericValues() {
        val json = mapOf("i" to 42, "f" to 3.14).toJson()
        assertTrue(json.contains("\"i\":42"))
        assertTrue(json.contains("\"f\":3.14"))
    }

    @Test fun serializesBooleans() {
        val json = mapOf("a" to true, "b" to false).toJson()
        assertTrue(json.contains("\"a\":true"))
        assertTrue(json.contains("\"b\":false"))
    }

    @Test fun serializesNull() {
        val json = mapOf("x" to null).toJson()
        assertTrue(json.contains("\"x\":null"))
    }

    @Test fun serializesNestedMap() {
        val json = mapOf("outer" to mapOf("inner" to 1)).toJson()
        assertTrue(json.contains("\"inner\":1"))
    }

    @Test fun serializesList() {
        val json = mapOf("list" to listOf(1, 2, 3)).toJson()
        assertTrue(json.contains("[1,2,3]"))
    }

    @Test fun escapesSpecialCharacters() {
        val json = mapOf("s" to "a\"b\\c\nd").toJson()
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
    }

    @Test fun extractJsonStringFindsValue() {
        val s = """{"status":"accepted","batch_id":"b1"}"""
        assertEquals("accepted", s.extractJsonString("status"))
        assertEquals("b1", s.extractJsonString("batch_id"))
    }

    @Test fun extractJsonStringReturnsNullForMissing() {
        assertNull("""{"other":"val"}""".extractJsonString("missing"))
    }

    @Test fun extractJsonIntFindsValue() {
        val s = """{"events_accepted":42}"""
        assertEquals(42, """{"events_accepted":42}""".extractJsonInt("events_accepted"))
    }
}
