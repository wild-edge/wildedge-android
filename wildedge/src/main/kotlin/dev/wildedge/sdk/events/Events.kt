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

/**
 * Snapshot of device hardware state at inference time.
 *
 * All fields are optional; omit anything not available on the device.
 *
 * @property thermalState Normalised thermal state string (e.g. "nominal", "fair", "serious", "critical").
 * @property thermalStateRaw Raw platform thermal status value.
 * @property cpuTempCelsius CPU temperature in degrees Celsius.
 * @property batteryLevel Battery charge level in the range [0.0, 1.0].
 * @property batteryCharging Whether the device is currently charging.
 * @property memoryAvailableBytes Available system RAM in bytes.
 * @property cpuFreqMhz Current CPU frequency in MHz.
 * @property cpuFreqMaxMhz Maximum CPU frequency in MHz.
 * @property acceleratorActual Accelerator actually used for inference (may differ from requested).
 * @property gpuBusyPercent GPU utilisation percentage.
 */
data class HardwareContext(
    val thermalState: String? = null,
    val thermalStateRaw: String? = null,
    val cpuTempCelsius: Float? = null,
    val batteryLevel: Float? = null,
    val batteryCharging: Boolean? = null,
    val memoryAvailableBytes: Long? = null,
    val cpuFreqMhz: Int? = null,
    val cpuFreqMaxMhz: Int? = null,
    val acceleratorActual: dev.wildedge.sdk.Accelerator? = null,
    val gpuBusyPercent: Int? = null,
) {
    /** Serialises this context to a wire-format map, omitting null fields. */
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
        "accelerator_actual" to acceleratorActual?.value,
        "gpu_busy_percent" to gpuBusyPercent,
    ).filterValues { it != null }
}

/**
 * Image-specific input metadata for an inference event.
 *
 * @property width Image width in pixels.
 * @property height Image height in pixels.
 * @property channels Number of colour channels.
 * @property format Pixel format (e.g. "rgb", "yuv420").
 * @property source Origin of the image (e.g. "camera", "gallery").
 * @property brightnessMean Mean pixel brightness, normalised to [0.0, 1.0].
 * @property brightnessStddev Standard deviation of pixel brightness.
 * @property brightnessBuckets Histogram bucket counts for brightness distribution.
 * @property contrast Image contrast score.
 * @property saturationMean Mean colour saturation.
 * @property blurScore Blur detection score (higher = blurrier).
 * @property noiseScore Noise detection score (higher = noisier).
 */
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
    /** Serialises this metadata to a wire-format map, omitting null fields. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("width", width)
        put("height", height)
        put("channels", channels)
        put("format", format)
        put("source", source)
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

/**
 * Audio-specific input metadata for an inference event.
 *
 * @property durationMs Clip duration in milliseconds.
 * @property sampleRate Sample rate in Hz.
 * @property channels Number of audio channels.
 * @property format Audio encoding format (e.g. "pcm16", "opus").
 * @property source Origin of the audio (e.g. "microphone", "file").
 * @property isStreaming Whether the audio is being streamed rather than buffered.
 * @property volumeDb RMS volume in dBFS.
 * @property snrDb Signal-to-noise ratio in dB.
 * @property speechRatio Fraction of the clip containing speech, in [0.0, 1.0].
 * @property clippingDetected Whether audio clipping was detected.
 */
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
    /** Serialises this metadata to a wire-format map, omitting null fields. */
    fun toMap(): Map<String, Any?> = mapOf(
        "duration_ms" to durationMs, "sample_rate" to sampleRate,
        "channels" to channels, "format" to format, "source" to source,
        "is_streaming" to isStreaming, "volume_db" to volumeDb,
        "snr_db" to snrDb, "speech_ratio" to speechRatio,
        "clipping_detected" to clippingDetected,
    ).filterValues { it != null }
}

/**
 * Text-specific input metadata for an inference event.
 *
 * @property charCount Number of characters in the input.
 * @property wordCount Number of words in the input.
 * @property tokenCount Number of tokens (if pre-tokenised).
 * @property language BCP-47 language tag of the detected language.
 * @property languageConfidence Confidence of language detection, in [0.0, 1.0].
 * @property containsCode Whether the input contains source code.
 * @property promptType Type of prompt (e.g. "instruction", "chat", "completion").
 * @property turnIndex Conversation turn index, for multi-turn sessions.
 * @property hasAttachments Whether the input includes file or image attachments.
 */
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
    /** Serialises this metadata to a wire-format map, omitting null fields. */
    fun toMap(): Map<String, Any?> = mapOf(
        "char_count" to charCount, "word_count" to wordCount,
        "token_count" to tokenCount, "language" to language,
        "language_confidence" to languageConfidence,
        "contains_code" to containsCode, "prompt_type" to promptType,
        "turn_index" to turnIndex, "has_attachments" to hasAttachments,
    ).filterValues { it != null }
}

/**
 * A single ranked prediction from a detection or classification model.
 *
 * @property label Predicted class label.
 * @property confidence Confidence score in [0.0, 1.0].
 */
data class TopPrediction(val label: String, val confidence: Float? = null)

/**
 * Output metadata for detection and classification inference events.
 *
 * @property numPredictions Total number of predictions returned.
 * @property topK Ranked list of top predictions.
 * @property avgConfidence Average confidence across all predictions.
 */
data class DetectionOutputMeta(
    val numPredictions: Int? = null,
    val topK: List<TopPrediction>? = null,
    val avgConfidence: Float? = null,
) {
    /** Serialises this metadata to a wire-format map, omitting null fields. */
    fun toMap(): Map<String, Any?> = mapOf(
        "task" to "detection",
        "num_predictions" to numPredictions,
        "top_k" to topK?.map { p ->
            mapOf("label" to p.label, "confidence" to p.confidence).filterValues { it != null }
        },
        "avg_confidence" to avgConfidence,
    ).filterValues { it != null }
}

/**
 * Output metadata for text generation inference events.
 *
 * @property tokensIn Number of input (prompt) tokens.
 * @property tokensOut Number of generated output tokens.
 * @property cachedInputTokens Number of input tokens served from a KV cache.
 * @property timeToFirstTokenMs Latency to the first generated token in milliseconds.
 * @property tokensPerSecond Generation throughput in tokens per second.
 * @property stopReason Reason generation stopped (e.g. "eos", "max_tokens", "stop_sequence").
 * @property contextUsed Number of context slots consumed.
 * @property avgTokenEntropy Mean per-token entropy of the output distribution.
 * @property safetyTriggered Whether a safety filter was triggered during generation.
 */
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
    /** Serialises this metadata to a wire-format map, omitting null fields. */
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

/**
 * Output metadata for embedding inference events.
 *
 * @property dimensions Number of dimensions in the output embedding vector.
 */
data class EmbeddingOutputMeta(val dimensions: Int) {
    /** Serialises this metadata to a wire-format map. */
    fun toMap(): Map<String, Any?> = mapOf("task" to "embedding", "dimensions" to dimensions)
}

/** Builds a wire-format inference event map. */
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
    ).also { it.values.removeAll { v -> v == null } }
}

/** Builds a wire-format model load event map. */
fun buildModelLoadEvent(
    modelId: String,
    durationMs: Int,
    memoryBytes: Long? = null,
    accelerator: dev.wildedge.sdk.Accelerator? = null,
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
        "accelerator" to accelerator?.value,
        "success" to success,
        "error_code" to errorCode,
        "cold_start" to coldStart,
        "threads" to threads,
        "gpu_layers" to gpuLayers,
    ).filterValues { it != null },
)

/** Builds a wire-format model unload event map. */
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
)

/** Builds a wire-format model download event map. */
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
)

/** Builds a wire-format user feedback event map. */
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
)

/** Builds a wire-format error event map. */
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

/** Builds a wire-format distributed tracing span event map. */
fun buildSpanEvent(
    traceId: String,
    spanId: String,
    parentSpanId: String? = null,
    kind: String,
    status: String,
    name: String,
    durationMs: Long,
    attributes: Map<String, Any?>? = null,
): Map<String, Any?> = mapOf(
    "event_id" to newEventId(),
    "event_type" to "span",
    "timestamp" to isoNow(),
    "trace_id" to traceId,
    "span_id" to spanId,
    "parent_span_id" to parentSpanId,
    "span" to mapOf(
        "kind" to kind,
        "status" to status,
        "name" to name,
        "duration_ms" to durationMs,
        "attributes" to attributes,
    ).filterValues { it != null },
).filterValues { it != null }

/** Builds a wire-format memory warning event map. */
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
