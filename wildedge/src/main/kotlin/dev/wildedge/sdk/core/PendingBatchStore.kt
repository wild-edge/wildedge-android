package dev.wildedge.sdk

import java.io.File

// Stores formed batches that failed to transmit. On next flush, oldest files are retried first.
internal class PendingBatchStore(private val dir: File?) {

    fun write(batchJson: String): Boolean {
        val d = dir ?: return false
        return try {
            d.mkdirs()
            val name = "${System.nanoTime()}.json"
            File(d, name).writeText(batchJson)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readOldest(): Pair<File, String>? {
        val d = dir ?: return null
        return try {
            d.listFiles { f -> f.extension == "json" }
                ?.minByOrNull { it.name }
                ?.let { it to it.readText() }
        } catch (_: Exception) { null }
    }

    fun delete(file: File) {
        try { file.delete() } catch (_: Exception) {}
    }

    fun hasAny(): Boolean = try {
        dir?.listFiles { f -> f.extension == "json" }?.isNotEmpty() == true
    } catch (_: Exception) { false }
}
