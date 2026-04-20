package dev.wildedge.sdk

import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class ModelRegistry(private val persistFile: File? = null) {
    private val models = mutableMapOf<String, ModelInfo>()
    private val lock = ReentrantLock()

    init {
        persistFile?.let { loadFromDisk(it) }
    }

    fun register(modelId: String, info: ModelInfo): Boolean = lock.withLock {
        if (models.containsKey(modelId)) return false
        models[modelId] = info
        persistFile?.let { saveToDisk(it) }
        true
    }

    fun snapshot(): Map<String, Map<String, Any?>> = lock.withLock {
        models.mapValues { it.value.toMap() }
    }

    private fun loadFromDisk(file: File) {
        if (!file.exists()) return
        try {
            val text = file.readText()
            parseRegistry(text)
        } catch (_: Exception) {
            // Corrupt registry, start fresh.
        }
    }

    private fun parseRegistry(json: String) {
        val root = JSONObject(json)
        val parsed = mutableMapOf<String, ModelInfo>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val modelId = keys.next()
            val obj = root.optJSONObject(modelId) ?: continue

            val modelName = obj.optStringOrNull("model_name") ?: continue
            val modelVersion = obj.optStringOrNull("model_version") ?: continue
            val modelSource = obj.optStringOrNull("model_source") ?: continue
            val modelFormat = obj.optStringOrNull("model_format") ?: continue

            parsed[modelId] = ModelInfo(
                modelName = modelName,
                modelVersion = modelVersion,
                modelSource = modelSource,
                modelFormat = modelFormat,
                modelFamily = obj.optStringOrNull("model_family"),
                quantization = obj.optStringOrNull("quantization"),
            )
        }
        models.clear()
        models.putAll(parsed)
    }

    private fun saveToDisk(file: File) {
        try {
            file.parentFile?.mkdirs()
            val json = models.mapValues { (_, info) -> info.toMap() }.toJson()
            file.writeText(json)
        } catch (_: Exception) {}
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
