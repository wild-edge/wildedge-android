package dev.wildedge.sdk

import dev.wildedge.sdk.events.*

class ModelHandle internal constructor(
    val modelId: String,
    val info: ModelInfo,
    private val publish: (MutableMap<String, Any?>) -> Unit,
    private val hardwareSnapshot: () -> HardwareContext?,
    private val activeSpanContext: () -> SpanContext? = { null },
) : AutoCloseable {

    private var loadedAt: Long = 0L
    var lastInferenceId: String? = null
        private set
    var acceleratorActual: Accelerator? = null
        internal set

    fun trackLoad(
        durationMs: Int,
        memoryBytes: Long? = null,
        accelerator: Accelerator? = null,
        success: Boolean = true,
        errorCode: String? = null,
        coldStart: Boolean? = null,
        threads: Int? = null,
    ) {
        loadedAt = System.currentTimeMillis()
        publish(buildModelLoadEvent(
            modelId = modelId,
            durationMs = durationMs,
            memoryBytes = memoryBytes,
            accelerator = accelerator,
            success = success,
            errorCode = errorCode,
            coldStart = coldStart,
            threads = threads,
        ).toMutableMap())
    }

    fun trackUnload(
        durationMs: Int = 0,
        reason: String = "explicit",
        memoryFreedBytes: Long? = null,
    ) {
        val uptimeMs = if (loadedAt > 0) System.currentTimeMillis() - loadedAt else null
        publish(buildModelUnloadEvent(
            modelId = modelId,
            durationMs = durationMs,
            reason = reason,
            memoryFreedBytes = memoryFreedBytes,
            uptimeMs = uptimeMs,
        ).toMutableMap())
    }

    fun trackDownload(
        sourceUrl: String,
        sourceType: String,
        fileSizeBytes: Long,
        downloadedBytes: Long,
        durationMs: Int,
        networkType: String = "unknown",
        resumed: Boolean = false,
        cacheHit: Boolean = false,
        success: Boolean = true,
        errorCode: String? = null,
    ) {
        publish(buildModelDownloadEvent(
            modelId = modelId,
            sourceUrl = sourceUrl,
            sourceType = sourceType,
            fileSizeBytes = fileSizeBytes,
            downloadedBytes = downloadedBytes,
            durationMs = durationMs,
            networkType = networkType,
            resumed = resumed,
            cacheHit = cacheHit,
            success = success,
            errorCode = errorCode,
        ).toMutableMap())
    }

    fun trackInference(
        durationMs: Int,
        inputModality: InputModality? = null,
        outputModality: OutputModality? = null,
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
    ): String {
        val activeCtx = activeSpanContext()
        val rawHw = hardware ?: hardwareSnapshot()
        val hw = acceleratorActual?.let { acc ->
            (rawHw ?: HardwareContext()).copy(acceleratorActual = acc)
        } ?: rawHw
        val event = buildInferenceEvent(
            modelId = modelId,
            durationMs = durationMs,
            inputModality = inputModality?.value,
            outputModality = outputModality?.value,
            success = success,
            errorCode = errorCode,
            inputMeta = inputMeta,
            outputMeta = outputMeta,
            generationConfig = generationConfig,
            hardware = hw,
            traceId = traceId ?: activeCtx?.traceId,
            spanId = spanId,
            parentSpanId = parentSpanId ?: activeCtx?.spanId,
            runId = runId,
            agentId = agentId,
        )
        val inferenceId = event["__we_inference_id"] as String
        lastInferenceId = inferenceId
        publish(event)
        return inferenceId
    }

    fun trackFeedback(
        feedbackType: String,
        relatedInferenceId: String? = null,
        delayMs: Int? = null,
        editDistance: Int? = null,
    ) {
        val inferenceId = relatedInferenceId ?: lastInferenceId ?: return
        publish(buildFeedbackEvent(
            modelId = modelId,
            relatedInferenceId = inferenceId,
            feedbackType = feedbackType,
            delayMs = delayMs,
            editDistance = editDistance,
        ).toMutableMap())
    }

    fun trackError(
        errorCode: String,
        errorMessage: String? = null,
        stackTraceHash: String? = null,
        relatedEventId: String? = null,
    ) {
        publish(buildErrorEvent(
            modelId = modelId,
            errorCode = errorCode,
            errorMessage = errorMessage,
            stackTraceHash = stackTraceHash,
            relatedEventId = relatedEventId,
        ).toMutableMap())
    }

    override fun close() = trackUnload(reason = "explicit")
}
