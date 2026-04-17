package dev.wildedge.sample

import dev.wildedge.sdk.ModelHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// MobileNet V1 1.0 224 quant from the TensorFlow model repository (~4 MB).
// Swap for any .tflite classification model that expects [1, 224, 224, 3] uint8 input.
const val MODEL_URL =
    "https://drive.usercontent.google.com/download?id=1xUQklFyuYFV_ZsuO8Rskc52xsuSPZCip&export=download&authuser=0"

suspend fun downloadModel(handle: ModelHandle, dest: File): Boolean =
    withContext(Dispatchers.IO) {
        if (dest.exists()) return@withContext true
        dest.parentFile?.mkdirs()

        val start = System.currentTimeMillis()
        var downloaded = 0L
        var totalSize = 0L

        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            totalSize = conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                    }
                }
            }
            handle.trackDownload(
                sourceUrl = MODEL_URL,
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
                sourceUrl = MODEL_URL,
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
