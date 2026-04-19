package dev.wildedge.sdk

import java.io.File

private const val MAX_REASON_FILENAME_CHARS = 30

internal class DeadLetterStore(
    private val dir: File?,
    private val maxBatches: Int = Config.DEFAULT_DEAD_LETTER_MAX_BATCHES,
) {
    fun write(reason: String, batchJson: String) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= maxBatches) files.firstOrNull()?.delete()
            val name = "${System.currentTimeMillis()}-${reason.take(MAX_REASON_FILENAME_CHARS)}.json"
            File(d, name).writeText(batchJson)
        } catch (_: Exception) {}
    }
}
