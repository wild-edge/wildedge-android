package dev.wildedge.sdk

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// MARK: - File format
//
// The store is a flat, append-only sequence of records with no file-level header.
//
// Record layout:
//   bytes  0-15 : UUID    — 16 bytes (8 bytes most-significant, 8 bytes least-significant)
//   bytes 16-19 : Int32   — payload length, little-endian
//   bytes  20+  : bytes   — payload (arbitrary bytes)
//
// Access model:
//   Tail append  O(1)        — single write per record, serialised by a lock
//   Head peek    O(1)        — random-access read at offset 0
//   Drop first N O(remaining) — two strategies, see CompactionStrategy
//   Sequential   O(n)        — random-access read per record via BlobReader

private const val HEADER_SIZE = 20
private const val UUID_BYTES = 16
private const val LENGTH_FIELD_SIZE = 4
private const val COPY_BUFFER_SIZE = 64 * 1024

internal class BlobStoreException(message: String) : Exception(message)

/**
 * Append-only flat-file blob store backed by [RandomAccessFile].
 *
 * - **Append** is serialised with a [ReentrantLock]; concurrent callers are safe.
 * - Random access by record ID is intentionally not supported.
 */
internal class BlobStore(
    private val path: String,
    private val compaction: CompactionStrategy = CompactionStrategy.Rename,
) : AutoCloseable {

    /** Controls how [dropFirst] reclaims space. */
    enum class CompactionStrategy {
        /** Shifts remaining bytes to offset 0, then truncates. Not crash-safe. */
        InPlace,

        /** Writes remaining records to a temp file, then renames it over the store. Crash-safe. */
        Rename,
    }

    data class Item(val id: UUID, val data: ByteArray)

    private val lock = ReentrantLock()
    private var file = RandomAccessFile(path.also { File(it).parentFile?.mkdirs() }, "rw")

    /** Appends [data] as a new record and returns the assigned UUID. */
    fun append(data: ByteArray): UUID = lock.withLock {
        val id = UUID.randomUUID()
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(id.mostSignificantBits)
        header.putLong(id.leastSignificantBits)
        header.putInt(data.size)
        file.seek(file.length())
        file.write(header.array())
        file.write(data)
        id
    }

    /** Reads the first record without removing it, or `null` if the store is empty. */
    fun peekHead(): Item? = lock.withLock { readRecord(file, 0) }

    /**
     * Counts records by reading only the 20-byte header of each record and using the
     * stored payload length to skip forward — payload bytes are never loaded.
     */
    fun count(): Int = lock.withLock {
        val fileSize = file.length()
        val header = ByteArray(HEADER_SIZE)
        var offset = 0L
        var n = 0
        while (offset < fileSize) {
            file.seek(offset)
            val read = file.read(header)
            if (read < HEADER_SIZE) break
            val payloadLen = readPayloadLength(header)
            val nextOffset = offset + HEADER_SIZE + payloadLen
            if (nextOffset > fileSize) break
            offset = nextOffset
            n++
        }
        n
    }

    /** Removes the first [n] records using the configured [CompactionStrategy]. */
    fun dropFirst(n: Int) {
        if (n <= 0) return
        lock.withLock {
            val fileSize = file.length()
            val header = ByteArray(HEADER_SIZE)
            var dropOffset = 0L
            var dropped = 0
            while (dropped < n) {
                file.seek(dropOffset)
                val read = file.read(header)
                if (read == -1) break
                if (read != HEADER_SIZE) throw BlobStoreException("truncated header")
                val payloadLen = readPayloadLength(header)
                dropOffset += HEADER_SIZE + payloadLen
                dropped++
            }
            if (dropped == 0) return@withLock

            val remaining = maxOf(0L, fileSize - dropOffset)
            when (compaction) {
                CompactionStrategy.InPlace -> compactInPlace(dropOffset, remaining)
                CompactionStrategy.Rename -> compactRename(dropOffset, remaining)
            }
        }
    }

    /** Creates an independent forward-only reader starting at the first record. */
    fun makeReader(): BlobReader = BlobReader(RandomAccessFile(path, "r"))

    override fun close() {
        lock.withLock { file.close() }
    }

    // Shifts remaining bytes to offset 0, then truncates. Reuses the same file handle.
    private fun compactInPlace(dropOffset: Long, remaining: Long) {
        copyBytes(file, dropOffset, file, 0L, remaining)
        file.setLength(remaining)
    }

    // Writes remaining bytes to a `.tmp` sidecar, renames it over the store, then reopens.
    private fun compactRename(dropOffset: Long, remaining: Long) {
        val tmpPath = "$path.tmp"
        val tmpFile = File(tmpPath)
        RandomAccessFile(tmpFile, "rw").use { tmp ->
            tmp.setLength(0)
            copyBytes(file, dropOffset, tmp, 0L, remaining)
        }
        file.close()
        if (!tmpFile.renameTo(File(path))) {
            tmpFile.delete()
            throw BlobStoreException("rename failed")
        }
        file = RandomAccessFile(path, "rw")
    }

    private fun copyBytes(src: RandomAccessFile, srcOff: Long, dst: RandomAccessFile, dstOff: Long, count: Long) {
        if (count <= 0) return
        val buf = ByteArray(minOf(count, COPY_BUFFER_SIZE.toLong()).toInt())
        var done = 0L
        while (done < count) {
            val toRead = minOf(buf.size.toLong(), count - done).toInt()
            src.seek(srcOff + done)
            val read = src.read(buf, 0, toRead)
            if (read == -1) break
            dst.seek(dstOff + done)
            dst.write(buf, 0, read)
            done += read
        }
    }
}

/**
 * Stateful forward-only cursor over a [BlobStore] file.
 *
 * The reader maintains its own offset; calling [next] never affects the store or any
 * other reader. Create via [BlobStore.makeReader].
 */
internal class BlobReader(private val file: RandomAccessFile) : AutoCloseable {
    private var offset = 0L

    /** Returns the next record and advances the cursor, or `null` at EOF. */
    fun next(): BlobStore.Item? {
        val item = readRecord(file, offset) ?: return null
        offset += HEADER_SIZE + item.data.size
        return item
    }

    /** Resets the cursor to the beginning of the file. */
    fun reset() {
        offset = 0L
    }

    override fun close() = file.close()
}

// Reads one record from [file] starting at byte [offset], or `null` at clean EOF.
private fun readPayloadLength(header: ByteArray): Long =
    ByteBuffer.wrap(header, UUID_BYTES, LENGTH_FIELD_SIZE).order(ByteOrder.LITTLE_ENDIAN).int.toLong()

private fun readRecord(file: RandomAccessFile, offset: Long): BlobStore.Item? {
    if (offset >= file.length()) return null
    file.seek(offset)
    val header = ByteArray(HEADER_SIZE)
    val headerRead = file.read(header)
    if (headerRead == -1) return null
    if (headerRead != HEADER_SIZE) throw BlobStoreException("truncated header")

    val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val msb = buf.long
    val lsb = buf.long
    val payloadLen = buf.int
    val id = UUID(msb, lsb)

    if (payloadLen == 0) return BlobStore.Item(id, ByteArray(0))

    val payload = ByteArray(payloadLen)
    val payloadRead = file.read(payload)
    if (payloadRead != payloadLen) throw BlobStoreException("truncated payload")
    return BlobStore.Item(id, payload)
}
