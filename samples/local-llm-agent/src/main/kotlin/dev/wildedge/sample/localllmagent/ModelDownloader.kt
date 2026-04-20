package dev.wildedge.sample.localllmagent

import dev.wildedge.sdk.ModelHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Qwen2.5 1.5B Instruct, int8, ~1.6 GB. Recommended for reliable tool calling.
const val MODEL_URL =
    "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

// Smaller alternative: Qwen2.5 0.5B Instruct, int8, ~547 MB. Tool calling unreliable.
// const val MODEL_URL =
//     "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

suspend fun downloadModel(
    handle: ModelHandle,
    url: String,
    dest: File,
    hfToken: String = "",
    onProgress: (downloaded: Long, total: Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    if (dest.exists()) return@withContext true
    dest.parentFile?.mkdirs()

    val start = System.currentTimeMillis()
    var downloaded = 0L
    var totalSize = 0L

    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (hfToken.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $hfToken")
        totalSize = conn.contentLengthLong.coerceAtLeast(0)
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(65_536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, totalSize)
                }
            }
        }
        handle.trackDownload(
            sourceUrl = url,
            sourceType = "https",
            fileSizeBytes = totalSize,
            downloadedBytes = downloaded,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            success = true,
        )
        true
    } catch (e: Exception) {
        dest.delete()
        handle.trackDownload(
            sourceUrl = url,
            sourceType = "https",
            fileSizeBytes = totalSize,
            downloadedBytes = downloaded,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            success = false,
            errorCode = e.javaClass.simpleName,
        )
        false
    }
}
