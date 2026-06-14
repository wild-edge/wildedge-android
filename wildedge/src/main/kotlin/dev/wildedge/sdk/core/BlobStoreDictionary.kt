package dev.wildedge.sdk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// MARK: - Binary dictionary encoding
//
// Compact tag-length-value binary format used to persist Map<String, Any?> records
// in a BlobStore. ~30-50% smaller than JSON for typical event/attachment dicts and
// avoids needing a JSON parser on the read path.
//
// Format — all integers little-endian:
//
//   dict  ::= count:u32  pair*count
//   pair  ::= keyLen:u16  keyBytes  value
//   value ::= tag:u8  payload
//
//   tag  payload
//   0x00  —              null
//   0x01  —              Bool false
//   0x02  —              Bool true
//   0x03  i64:8          Int64
//   0x04  u64:8          Double (bit pattern)
//   0x05  len:u32 bytes  String (UTF-8)
//   0x06  dict           nested Map<String, Any?>
//   0x07  count:u32 value*count  List<Any?>
//   0x08  len:u32 bytes  ByteArray

internal class BlobStoreDictionaryException(message: String) : Exception(message)

private const val TAG_NULL: Int = 0x00
private const val TAG_FALSE: Int = 0x01
private const val TAG_TRUE: Int = 0x02
private const val TAG_INT64: Int = 0x03
private const val TAG_DOUBLE: Int = 0x04
private const val TAG_STRING: Int = 0x05
private const val TAG_DICT: Int = 0x06
private const val TAG_ARRAY: Int = 0x07
private const val TAG_DATA: Int = 0x08

private const val MAX_UINT16 = 65_535
private const val BYTE_MASK = 0xFF

/** Serialises [dict] using the binary tag-length-value format. */
internal fun encodeBlobDictionary(dict: Map<String, Any?>): ByteArray {
    val out = ByteArrayOutputStream()
    encodeDict(dict, out)
    return out.toByteArray()
}

/** Deserialises a binary tag-length-value record produced by [encodeBlobDictionary]. */
@Suppress("UNCHECKED_CAST")
internal fun decodeBlobDictionary(data: ByteArray): Map<String, Any?> {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    return decodeDict(buf) as Map<String, Any?>
}

private fun encodeDict(dict: Map<String, Any?>, out: ByteArrayOutputStream) {
    appendLeInt(dict.size, out)
    for ((key, value) in dict) {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size > MAX_UINT16) throw BlobStoreDictionaryException("key too long: $key")
        appendLeShort(keyBytes.size, out)
        out.write(keyBytes)
        encodeValue(value, out)
    }
}

@Suppress("UNCHECKED_CAST")
private fun encodeValue(value: Any?, out: ByteArrayOutputStream) {
    when (value) {
        null -> out.write(TAG_NULL)
        is Boolean -> out.write(if (value) TAG_TRUE else TAG_FALSE)
        is Double -> {
            out.write(TAG_DOUBLE)
            appendLeLong(java.lang.Double.doubleToRawLongBits(value), out)
        }
        is Float -> {
            out.write(TAG_DOUBLE)
            appendLeLong(java.lang.Double.doubleToRawLongBits(value.toDouble()), out)
        }
        is Long -> {
            out.write(TAG_INT64)
            appendLeLong(value, out)
        }
        is Int -> {
            out.write(TAG_INT64)
            appendLeLong(value.toLong(), out)
        }
        is String -> {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(TAG_STRING)
            appendLeInt(bytes.size, out)
            out.write(bytes)
        }
        is Map<*, *> -> {
            out.write(TAG_DICT)
            encodeDict(value as Map<String, Any?>, out)
        }
        is List<*> -> {
            out.write(TAG_ARRAY)
            appendLeInt(value.size, out)
            for (elem in value) encodeValue(elem, out)
        }
        is ByteArray -> {
            out.write(TAG_DATA)
            appendLeInt(value.size, out)
            out.write(value)
        }
        else -> {
            // Best-effort fallback: serialise as a string description.
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            out.write(TAG_STRING)
            appendLeInt(bytes.size, out)
            out.write(bytes)
        }
    }
}

private fun decodeDict(buf: ByteBuffer): Map<String, Any?> {
    val count = buf.int
    val dict = LinkedHashMap<String, Any?>(count)
    repeat(count) {
        val keyLen = readLeShort(buf)
        val keyBytes = ByteArray(keyLen)
        buf.get(keyBytes)
        val key = String(keyBytes, Charsets.UTF_8)
        dict[key] = decodeValue(buf)
    }
    return dict
}

private fun decodeValue(buf: ByteBuffer): Any? {
    val tag = buf.get().toInt() and BYTE_MASK
    return when (tag) {
        TAG_NULL -> null
        TAG_FALSE -> false
        TAG_TRUE -> true
        TAG_INT64 -> buf.long
        TAG_DOUBLE -> java.lang.Double.longBitsToDouble(buf.long)
        TAG_STRING -> {
            val len = buf.int
            val bytes = ByteArray(len)
            buf.get(bytes)
            String(bytes, Charsets.UTF_8)
        }
        TAG_DICT -> decodeDict(buf)
        TAG_ARRAY -> {
            val count = buf.int
            val list = ArrayList<Any?>(count)
            repeat(count) { list.add(decodeValue(buf)) }
            list
        }
        TAG_DATA -> {
            val len = buf.int
            val bytes = ByteArray(len)
            buf.get(bytes)
            bytes
        }
        else -> throw BlobStoreDictionaryException("unknown tag: $tag")
    }
}

private fun appendLeInt(value: Int, out: ByteArrayOutputStream) {
    out.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
}

private fun appendLeShort(value: Int, out: ByteArrayOutputStream) {
    out.write(ByteBuffer.allocate(Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
}

private fun appendLeLong(value: Long, out: ByteArrayOutputStream) {
    out.write(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
}

private fun readLeShort(buf: ByteBuffer): Int = buf.short.toInt() and MAX_UINT16
