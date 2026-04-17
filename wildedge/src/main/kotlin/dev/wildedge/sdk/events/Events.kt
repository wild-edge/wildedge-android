package dev.wildedge.sdk.events

import dev.wildedge.sdk.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun isoNow(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}

internal fun newEventId() = UUID.randomUUID().toString()

data class HardwareContext(
    val thermalState: String? = null,
    val thermalStateRaw: String? = null,
    val cpuTempCelsius: Float? = null,
    val batteryLevel: Float? = null,
    val batteryCharging: Boolean? = null,
    val memoryAvailableBytes: Long? = null,
    val cpuFreqMhz: Int? = null,
    val cpuFreqMaxMhz: Int? = null,
    val acceleratorActual: String? = null,
    val gpuBusyPercent: Int? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "thermal" to mapOf(
            "state" to thermalState,
            "state_raw" to thermalStateRaw,
            "cpu_temp_celsius" to cpuTempCelsius,
        ).filterValues { it != null }.ifEmpty { null },
        "battery_level" to batteryLevel,
        "battery_charging" to batteryCharging,
        "memory_available_bytes" to memoryAvailableBytes,
        "cpu_freq_mhz" to cpuFreqMhz,
        "cpu_freq_max_mhz" to cpuFreqMaxMhz,
        "accelerator_actual" to acceleratorActual,
        "gpu_busy_percent" to gpuBusyPercent,
    ).filterValues { it != null }
}

data class ImageInputMeta(
    val width: Int? = null,
    val height: Int? = null,
    val channels: Int? = null,
    val format: String? = null,
    val source: String? = null,
    val brightnessMean: Float? = null,
    val brightnessStddev: Float? = null,
    val brightnessBuckets: List<Int>? = null,
    val contrast: Float? = null,
    val saturationMean: Float? = null,
    val blurScore: Float? = null,
    val noiseScore: Float? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("width", width); put("height", height); put("channels", channels)
        put("format", format); put("source", source)
        val hist = mapOf(
            "brightness_mean" to brightnessMean,
            "brightness_stddev" to brightnessStddev,
            "brightness_buckets" to brightnessBuckets,
            "contrast" to contrast,
            "saturation_mean" to saturationMean,
            "blur_score" to blurScore,
            "noise_score" to noiseScore,
        ).filterValues { it != null }
        if (hist.isNotEmpty()) put("histogram_summary", hist)
    }.filterValues { it != null }
}

data class AudioInputMeta(
    val durationMs: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val format: String? = null,
    val source: String? = null,
    val isStreaming: Boolean? = null,
    val volumeDb: Float? = null,
    val snrDb: Float? = null,
    val speechRatio: Float? = null,
    val clippingDetected: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "duration_ms" to durationMs, "sample_rate" to sampleRate,
        "channels" to channels, "format" to format, "source" to source,
        "is_streaming" to isStreaming, "volume_db" to volumeDb,
        "snr_db" to snrDb, "speech_ratio" to speechRatio,
        "clipping_detected" to clippingDetected,
    ).filterValues { it != null }
}

data class TextInputMeta(
    val charCount: Int? = null,
    val wordCount: Int? = null,
    val tokenCount: Int? = null,
    val language: String? = null,
    val languageConfidence: Float? = null,
    val containsCode: Boolean? = null,
    val promptType: String? = null,
    val turnIndex: Int? = null,
    val hasAttachments: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "char_count" to charCount, "word_count" to wordCount,
        "token_count" to tokenCount, "language" to language,
        "language_confidence" to languageConfidence,
        "contains_code" to containsCode, "prompt_type" to promptType,
        "turn_index" to turnIndex, "has_attachments" to hasAttachments,
    ).filterValues { it != null }
}

data class TopPrediction(val label: String, val confidence: Float? = null)

data class DetectionOutputMeta(
    val numPredictions: Int? = null,
    val topK: List<TopPrediction>? = null,
    val avgConfidence: Float? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "task" to "detection",
        "num_predictions" to numPredictions,
        "top_k" to topK?.map { p ->
            mapOf("label" to p.label, "confidence" to p.confidence).filterValues { it != null }
        },
        "avg_confidence" to avgConfidence,
    ).filterValues { it != null }
}

data class GenerationOutputMeta(
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val cachedInputTokens: Int? = null,
    val timeToFirstTokenMs: Int? = null,
    val tokensPerSecond: Float? = null,
    val stopReason: String? = null,
    val contextUsed: Int? = null,
    val avgTokenEntropy: Float? = null,
    val safetyTriggered: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "task" to "generation",
        "tokens_in" to tokensIn, "tokens_out" to tokensOut,
        "cached_input_tokens" to cachedInputTokens,
        "time_to_first_token_ms" to timeToFirstTokenMs,
        "tokens_per_second" to tokensPerSecond,
        "stop_reason" to stopReason, "context_used" to contextUsed,
        "avg_token_entropy" to avgTokenEntropy,
        "safety_triggered" to safetyTriggered,
    ).filterValues { it != null }
}

data class EmbeddingOutputMeta(val dimensions: Int) {
    fun toMap(): Map<String, Any?> = mapOf("task" to "embedding", "dimensions" to dimensions)
}

fun buildInferenceEvent(
    modelId: String,
    durationMs: Int,
    inputModality: String? = null,
    outputModality: String? = null,
    success: Boolean = true,
    errorCode: String? = null,
    inputMeta: Map<String, Any?>? = null,
    outputMeta: Map<String, Any?>? = null,
    generationConfig: Map<String, Any?>? = null,
    hardware: HardwareContext? = null,
    traceId: String? = null,
    spanId: String? = null,
    parentSpanId: String? = null,
    runId: String? = null,
    agentId: String? = null,
    stepIndex: Int? = null,
    conversationId: String? = null,
    attributes: Map<String, Any?>? = null,
): MutableMap<String, Any?> {
    val inferenceId = newEventId()
    return mutableMapOf(
        "event_id" to newEventId(),
        "event_type" to "inference",
        "timestamp" to isoNow(),
        "model_id" to modelId,
        "trace_id" to traceId,
        "span_id" to spanId,
        "parent_span_id" to parentSpanId,
        "run_id" to runId,
        "agent_id" to agentId,
        "step_index" to stepIndex,
        "conversation_id" to conversationId,
        "attributes" to attributes,
        "inference" to mapOf(
            "inference_id" to inferenceId,
            "duration_ms" to durationMs,
            "input_modality" to inputModality,
            "output_modality" to outputModality,
            "input_meta" to inputMeta,
            "output_meta" to outputMeta,
            "generation_config" to generationConfig,
            "success" to success,
            "error_code" to errorCode,
        ).filterValues { it != null },
        "hardware" to hardware?.toMap()?.ifEmpty { null },
        "__we_inference_id" to inferenceId,
    ).also { it.values.removeAll { v -> v == null } } as MutableMap<String, Any?>
}

fun buildModelLoadEvent(
    modelId: String,
    durationMs: Int,
    memoryBytes: Long? = null,
    accelerator: String? = null,
    success: Boolean = true,
    errorCode: String? = null,
    coldStart: Boolean? = null,
    threads: Int? = null,
    gpuLayers: Int? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "model_load",
    "timestamp" to isoNow(),
    "model_id" to modelId,
    "load" to mapOf(
        "duration_ms" to durationMs,
        "memory_bytes" to memoryBytes,
        "accelerator" to accelerator,
        "success" to success,
        "error_code" to errorCode,
        "cold_start" to coldStart,
        "threads" to threads,
        "gpu_layers" to gpuLayers,
    ).filterValues { it != null },
).filterValues { it != null }

fun buildModelUnloadEvent(
    modelId: String,
    durationMs: Int,
    reason: String,
    memoryFreedBytes: Long? = null,
    uptimeMs: Long? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "model_unload",
    "timestamp" to isoNow(),
    "model_id" to modelId,
    "unload" to mapOf(
        "duration_ms" to durationMs,
        "reason" to reason,
        "memory_freed_bytes" to memoryFreedBytes,
        "uptime_ms" to uptimeMs,
    ).filterValues { it != null },
).filterValues { it != null }

fun buildModelDownloadEvent(
    modelId: String,
    sourceUrl: String,
    sourceType: String,
    fileSizeBytes: Long,
    downloadedBytes: Long,
    durationMs: Int,
    networkType: String,
    resumed: Boolean,
    cacheHit: Boolean,
    success: Boolean,
    errorCode: String? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "model_download",
    "timestamp" to isoNow(),
    "model_id" to modelId,
    "download" to mapOf(
        "source_url" to sourceUrl,
        "source_type" to sourceType,
        "file_size_bytes" to fileSizeBytes,
        "downloaded_bytes" to downloadedBytes,
        "duration_ms" to durationMs,
        "network_type" to networkType,
        "resumed" to resumed,
        "cache_hit" to cacheHit,
        "success" to success,
        "error_code" to errorCode,
    ).filterValues { it != null },
).filterValues { it != null }

fun buildFeedbackEvent(
    modelId: String,
    relatedInferenceId: String,
    feedbackType: String,
    delayMs: Int? = null,
    editDistance: Int? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "feedback",
    "timestamp" to isoNow(),
    "model_id" to modelId,
    "feedback" to mapOf(
        "related_inference_id" to relatedInferenceId,
        "feedback_type" to feedbackType,
        "delay_ms" to delayMs,
        "edit_distance" to editDistance,
    ).filterValues { it != null },
).filterValues { it != null }

fun buildErrorEvent(
    modelId: String?,
    errorCode: String,
    errorMessage: String? = null,
    stackTraceHash: String? = null,
    relatedEventId: String? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "error",
    "timestamp" to isoNow(),
    "model_id" to modelId,
    "error" to mapOf(
        "error_code" to errorCode,
        "error_message" to errorMessage?.take(Config.ERROR_MSG_MAX_LEN),
        "stack_trace_hash" to stackTraceHash,
        "related_event_id" to relatedEventId,
    ).filterValues { it != null },
).filterValues { it != null }

fun buildMemoryWarningEvent(
    level: String,
    memoryAvailableBytes: Long,
    activeModelIds: List<String>,
    triggeredUnload: Boolean,
    unloadedModelId: String? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "memory_warning",
    "timestamp" to isoNow(),
    "model_id" to null,
    "memory_warning" to mapOf(
        "level" to level,
        "memory_available_bytes" to memoryAvailableBytes,
        "active_model_ids" to activeModelIds,
        "triggered_unload" to triggeredUnload,
        "unloaded_model_id" to unloadedModelId,
    ).filterValues { it != null },
).filterValues { it != null }
