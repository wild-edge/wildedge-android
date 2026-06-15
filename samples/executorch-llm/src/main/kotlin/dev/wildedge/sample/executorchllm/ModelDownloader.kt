package dev.wildedge.sample.executorchllm

import dev.wildedge.sdk.ModelHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Llama 3.2 1B Instruct, INT4 quantized for XNNPACK, ~1.1 GB.
const val MODEL_URL =
    "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-QLORA_INT4_EO8-ET/resolve/main/Llama-3.2-1B-Instruct-QLORA_INT4_EO8.pte"

const val TOKENIZER_URL =
    "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct/resolve/main/tokenizer.json"

const val TOKENIZER_FILENAME = "llama32-tokenizer.json"

/** Downloads [url] to [dest]. Returns `null` on success, or a detailed error message on failure. */
suspend fun downloadFile(
    handle: ModelHandle,
    url: String,
    dest: File,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): String? = withContext(Dispatchers.IO) {
    if (dest.exists()) return@withContext null
    dest.parentFile?.mkdirs()

    val start = System.currentTimeMillis()
    var downloaded = 0L
    var totalSize = 0L

    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connect()
        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500)
            error("HTTP $responseCode ${conn.responseMessage}".trim() + (errorBody?.let { ": $it" } ?: ""))
        }
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
            sourceType = "huggingface",
            fileSizeBytes = totalSize,
            downloadedBytes = downloaded,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            success = true,
        )
        null
    } catch (e: Exception) {
        dest.delete()
        val message = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"} (url=$url, downloaded=$downloaded/$totalSize bytes)"
        handle.trackDownload(
            sourceUrl = url,
            sourceType = "huggingface",
            fileSizeBytes = totalSize,
            downloadedBytes = downloaded,
            durationMs = (System.currentTimeMillis() - start).toInt(),
            success = false,
            errorCode = e.javaClass.simpleName,
        )
        message
    }
}

/**
 * ExecuTorch's tokenizer parser (RE2-based) chokes on a couple of constructs that
 * HuggingFace tokenizer.json files commonly use. Patch them in place after download.
 */
fun patchHFTokenizerIfNeeded(file: File) {
    if (!file.name.endsWith(".json") || !file.exists()) return
    var content = file.readText()
    if (!content.contains("(?i:") && !content.contains("(?!") && !content.contains("\"Isolated\"")) return

    // (?i:'s|'t|...) — inline case-insensitive group, unsupported by RE2.
    // JSON-escaped, so the literal file content has doubled backslashes.
    content = content.replace(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)",
        "(?:'[sS]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])",
    )

    // \\s+(?!\\S)|\\s+ — negative lookahead, unsupported by RE2; simplifies to \\s+.
    // The JSON file stores regex backslashes doubled (\\s, \\S), so match that literally.
    content = content.replace("\\\\s+(?!\\\\S)|\\\\s+", "\\\\s+")

    // Split pre-tokenizer with behavior "Isolated" — unsupported by ExecuTorch's
    // tokenizer, which only supports "MergedWithPrevious" for Split.
    content = content.replace("\"behavior\":\"Isolated\"", "\"behavior\":\"MergedWithPrevious\"")
    content = content.replace("\"behavior\": \"Isolated\"", "\"behavior\": \"MergedWithPrevious\"")

    file.writeText(content)
}
