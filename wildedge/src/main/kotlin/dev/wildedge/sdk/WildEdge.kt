package dev.wildedge.sdk

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.util.Log
import dev.wildedge.sdk.events.buildMemoryWarningEvent
import dev.wildedge.sdk.events.buildSpanEvent
import dev.wildedge.sdk.events.isoNow
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Live implementation of [WildEdgeClient].
 *
 * Create via [WildEdge.init] or [WildEdge.Builder]; interact through the [WildEdgeClient] interface.
 */
class WildEdge internal constructor(
    private val noop: Boolean,
    private val queue: EventQueue,
    private val registry: ModelRegistry,
    private val consumer: Consumer?,
    private val hardwareSampler: HardwareSampler?,
    private val debug: Boolean,
    private var memoryCallbackUnregister: (() -> Unit)? = null,
    private val attachmentQueue: AttachmentQueue? = null,
    private val attachmentConsumer: AttachmentConsumer? = null,
    private val maxAttachmentsPerInference: Int = Config.DEFAULT_MAX_ATTACHMENTS_PER_INFERENCE,
    private val maxAttachmentSizeBytes: Long = Config.DEFAULT_MAX_ATTACHMENT_SIZE_BYTES,
    private val attachmentFilter: ((InferenceAttachment) -> Boolean)? = null,
) : WildEdgeClient, SpanOwner {
    private val handles = mutableMapOf<String, ModelHandle>()
    private val handlesLock = ReentrantLock()

    @Volatile private var closed = false
    private val activeSpan = ThreadLocal<SpanContext?>()

    private val attachmentExecutor = if (attachmentQueue != null) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "wildedge-attachment-enqueue").also { it.isDaemon = true }
        }
    } else {
        null
    }

    override fun registerModel(modelId: String, info: ModelInfo): ModelHandle {
        if (noop || closed) return ModelHandle(modelId, info, {}, { null })
        return handlesLock.withLock {
            handles.getOrPut(modelId) {
                registry.register(modelId, info)
                if (debug) Log.d("wildedge", "registered model id=$modelId format=${info.modelFormat}")
                ModelHandle(modelId, info, ::publish, {
                    hardwareSampler?.snapshot().also { hw ->
                        if (debug) Log.d("wildedge", "hardware snapshot for model=$modelId: $hw")
                    }
                }, { activeSpan.get() }, ::enqueueAttachments)
            }
        }
    }

    internal fun publish(event: MutableMap<String, Any?>) {
        if (noop || closed) return
        if (debug) Log.d("wildedge", "queuing event type=${event["event_type"]} model=${event["model_id"]}")
        queue.add(event)
    }

    internal fun enqueueAttachments(
        inferenceId: String,
        inferenceTimestamp: String,
        attachments: List<InferenceAttachment>,
    ) {
        val aq = attachmentQueue ?: return
        if (noop || closed) return
        val registeredAt = System.currentTimeMillis()
        attachmentExecutor?.execute {
            attachments
                .asSequence()
                .filter { attachmentFilter?.invoke(it) ?: true }
                .take(maxAttachmentsPerInference)
                .forEach { attachment ->
                    val (ref, byteSize) = when (val payload = attachment.payload) {
                        is InferenceAttachment.Payload.Data ->
                            AttachmentPayloadRef.Bytes(payload.bytes) to payload.bytes.size.toLong()
                        is InferenceAttachment.Payload.File ->
                            AttachmentPayloadRef.FilePath(payload.file.absolutePath) to payload.file.length()
                    }
                    if (byteSize > maxAttachmentSizeBytes) {
                        if (debug) {
                            Log.w("wildedge", "attachment ${attachment.attachmentId} exceeds max size; dropping")
                        }
                        return@forEach
                    }
                    val mimeType = when (val payload = attachment.payload) {
                        is InferenceAttachment.Payload.Data -> payload.mimeType
                        is InferenceAttachment.Payload.File -> payload.mimeType
                    }
                    aq.append(
                        PendingAttachment(
                            attachmentId = attachment.attachmentId,
                            inferenceId = inferenceId,
                            name = attachment.name,
                            role = attachment.role.value,
                            mimeType = mimeType,
                            byteSize = byteSize,
                            payload = ref,
                            inferenceTimestamp = inferenceTimestamp,
                            registeredAt = registeredAt,
                        ),
                    )
                }
        }
    }

    override fun trackMemoryWarning(
        level: MemoryWarningLevel,
        memoryAvailableBytes: Long,
        activeModelIds: List<String>,
        triggeredUnload: Boolean,
        unloadedModelId: String?,
    ) {
        publish(
            buildMemoryWarningEvent(
                level = level.value,
                memoryAvailableBytes = memoryAvailableBytes,
                activeModelIds = activeModelIds,
                triggeredUnload = triggeredUnload,
                unloadedModelId = unloadedModelId,
            ).toMutableMap()
        )
    }

    override fun flush(timeoutMs: Long) {
        val c = consumer ?: return
        if (isMainThread()) {
            if (debug) Log.w("wildedge", "flush() called on main thread; running async flush")
            c.flushAsync(timeoutMs)
        } else {
            c.flush(timeoutMs)
        }
    }

    override fun close(timeoutMs: Long) {
        if (closed) return
        closed = true
        memoryCallbackUnregister?.invoke()
        hardwareSampler?.stop()
        attachmentConsumer?.stop()
        attachmentExecutor?.shutdown()
        val c = consumer ?: return
        val blocking = !isMainThread()
        if (!blocking && debug) {
            Log.w("wildedge", "close() called on main thread; closing asynchronously")
        }
        c.close(timeoutMs = timeoutMs, blocking = blocking)
    }

    override fun <T> trace(
        name: String,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        parent: SpanContext?,
        runId: String?,
        agentId: String?,
        block: (SpanContext) -> T,
    ): T = runSpan(
        name = name,
        traceId = parent?.traceId ?: UUID.randomUUID().toString(),
        parentSpanId = parent?.spanId,
        kind = kind,
        attributes = attributes,
        runId = runId ?: parent?.runId,
        agentId = agentId ?: parent?.agentId,
        block = block,
    )

    override fun openSpan(
        name: String,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        parent: SpanContext?,
        runId: String?,
        agentId: String?,
    ): Span {
        val resolvedRunId = runId ?: parent?.runId
        val resolvedAgentId = agentId ?: parent?.agentId
        val ctx = SpanContext(
            traceId = parent?.traceId ?: UUID.randomUUID().toString(),
            spanId = UUID.randomUUID().toString(),
            parentSpanId = parent?.spanId,
            kind = kind,
            runId = resolvedRunId,
            agentId = resolvedAgentId,
            owner = this,
        )
        return Span(ctx) { spanCtx, durationMs ->
            if (debug) Log.d("wildedge", "span name=$name trace=${spanCtx.traceId} duration=${durationMs}ms")
            publish(
                buildSpanEvent(
                    traceId = spanCtx.traceId,
                    spanId = spanCtx.spanId,
                    parentSpanId = spanCtx.parentSpanId,
                    kind = spanCtx.kind.value,
                    status = spanCtx.status.value,
                    name = name,
                    durationMs = durationMs,
                    attributes = attributes,
                    runId = spanCtx.runId,
                    agentId = spanCtx.agentId,
                ).toMutableMap()
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        kind: SpanKind,
        attributes: Map<String, Any?>?,
        runId: String?,
        agentId: String?,
        block: (SpanContext) -> T,
    ): T {
        val ctx = SpanContext(
            traceId = traceId,
            spanId = UUID.randomUUID().toString(),
            parentSpanId = parentSpanId,
            kind = kind,
            runId = runId,
            agentId = agentId,
            owner = this,
        )
        val prev = activeSpan.get()
        activeSpan.set(ctx)
        val startMs = System.currentTimeMillis()
        try {
            return block(ctx)
        } catch (t: Throwable) {
            ctx.status = SpanStatus.Error
            throw t
        } finally {
            activeSpan.set(prev)
            val durationMs = System.currentTimeMillis() - startMs
            if (debug) Log.d("wildedge", "span name=$name trace=${ctx.traceId} duration=${durationMs}ms")
            publish(
                buildSpanEvent(
                    traceId = ctx.traceId,
                    spanId = ctx.spanId,
                    parentSpanId = ctx.parentSpanId,
                    kind = ctx.kind.value,
                    status = ctx.status.value,
                    name = name,
                    durationMs = durationMs,
                    attributes = attributes,
                    runId = ctx.runId,
                    agentId = ctx.agentId,
                ).toMutableMap()
            )
        }
    }

    override val pendingCount: Int get() = queue.length()

    override val diagnostics: SdkDiagnostics
        get() = SdkDiagnostics(
            eventQueueSizeBytes = queue.estimateSizeBytes(),
            eventQueueJsonBytes = queue.estimateJsonBytes(),
            attachmentQueueCount = attachmentQueue?.length() ?: 0,
            attachmentUploadedCount = attachmentConsumer?.uploadedCount?.get() ?: 0,
            attachmentDropCount = attachmentConsumer?.dropCount?.get() ?: 0,
            attachmentPermanentFailureCount = attachmentConsumer?.permanentFailureCount?.get() ?: 0,
        )

    // DSL builder

    /** DSL builder for configuring and constructing a [WildEdgeClient]. */
    class Builder(private val context: Context) {
        var dsn: String? = System.getenv(Config.ENV_DSN)
        var appVersion: String? = detectAppVersion(context)
        var device: DeviceInfo? = null
        var batchSize: Int = Config.DEFAULT_BATCH_SIZE
        var maxQueueSize: Int = Config.DEFAULT_MAX_QUEUE_SIZE
        var flushIntervalMs: Long = Config.DEFAULT_FLUSH_INTERVAL_MS
        var maxEventAgeMs: Long = Config.DEFAULT_MAX_EVENT_AGE_MS
        var samplingIntervalMs: Long? = Config.DEFAULT_SAMPLING_INTERVAL_MS
        var lowConfidenceThreshold: Float = Config.DEFAULT_LOW_CONFIDENCE_THRESHOLD
        var deadLetterDir: File? = null
        var pendingDir: File? = null
        var registryFile: File? = null
        var debug: Boolean = System.getenv(Config.ENV_DEBUG) == "true"
        var onDeliveryFailure: ((reason: String, dropped: Int, queueDepth: Int) -> Unit)? = null
        var enableCompression: Boolean = Config.DEFAULT_ENABLE_COMPRESSION
        var strict: Boolean = false
        var autoTrackMemoryWarnings: Boolean = true
        var enableAttachments: Boolean = Config.DEFAULT_ENABLE_ATTACHMENTS
        var attachmentStorageStrategy: AttachmentStorageStrategy = AttachmentStorageStrategy.File
        var maxAttachmentsPerInference: Int = Config.DEFAULT_MAX_ATTACHMENTS_PER_INFERENCE
        var maxAttachmentSizeBytes: Long = Config.DEFAULT_MAX_ATTACHMENT_SIZE_BYTES
        var maxAttachmentAgeMs: Long = Config.DEFAULT_MAX_ATTACHMENT_AGE_MS
        var attachmentFlushIntervalMs: Long = Config.DEFAULT_ATTACHMENT_FLUSH_INTERVAL_MS
        var attachmentTransmitTimeoutMs: Int = Config.ATTACHMENT_HTTP_TIMEOUT_MS
        var attachmentStoreFile: File? = null
        var attachmentFilter: ((InferenceAttachment) -> Boolean)? = null

        /**
         * Constructs a [WildEdgeClient] from the current builder configuration.
         * Returns a no-op client if no DSN is set.
         */
        fun build(): WildEdgeClient {
            val dsn = dsn
            if (dsn.isNullOrBlank()) {
                Log.w("wildedge", "wildedge: no DSN configured; client is disabled")
                return NoopWildEdgeClient()
            }

            val (secret, host) = parseDsn(dsn)
            val resolvedDevice = device ?: DeviceInfo.detect(context, secret, appVersion)

            val filesDir = context.filesDir
            val pending = PendingBatchStore(pendingDir ?: File(filesDir, "wildedge/pending"))
            val deadLetters = DeadLetterStore(deadLetterDir ?: File(filesDir, "wildedge/dead_letters"))
            val regFile = registryFile ?: File(filesDir, "wildedge/registry.json")

            val eventQueue = EventQueue(
                maxSize = maxQueueSize,
                strict = strict,
                onOverflow = { onDeliveryFailure?.invoke("queue_overflow", it, maxQueueSize) },
            )
            val modelRegistry = ModelRegistry(regFile)
            val transmitter = Transmitter(host, secret, enableCompression)
            val sessionId = UUID.randomUUID().toString()
            val createdAt = isoNow()

            val consumer = Consumer(
                queue = eventQueue,
                transmitter = transmitter,
                device = resolvedDevice,
                registry = modelRegistry,
                sessionId = sessionId,
                createdAt = createdAt,
                pendingStore = pending,
                deadLetterStore = deadLetters,
                batchSize = batchSize,
                flushIntervalMs = flushIntervalMs,
                maxEventAgeMs = maxEventAgeMs,
                lowConfidenceThreshold = lowConfidenceThreshold,
                log = { msg -> if (debug) Log.d("wildedge", msg) },
                onDeliveryFailure = onDeliveryFailure,
            ).also { it.start() }

            val sampler = samplingIntervalMs?.let {
                HardwareSampler(context, it).also { s -> s.start() }
            }

            val (attachmentQueue, attachmentConsumer) = buildAttachmentPipeline(host, secret, filesDir)

            if (debug) Log.d("wildedge", "wildedge: initialized session=$sessionId")

            val wildEdge = WildEdge(
                noop = false,
                queue = eventQueue,
                registry = modelRegistry,
                consumer = consumer,
                hardwareSampler = sampler,
                debug = debug,
                attachmentQueue = attachmentQueue,
                attachmentConsumer = attachmentConsumer,
                maxAttachmentsPerInference = maxAttachmentsPerInference,
                maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                attachmentFilter = attachmentFilter,
            )

            if (autoTrackMemoryWarnings) {
                val appContext = context.applicationContext
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val callback = object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        val warningLevel = trimMemoryLevel(level) ?: return
                        val memInfo = ActivityManager.MemoryInfo()
                        am?.getMemoryInfo(memInfo)
                        wildEdge.trackMemoryWarning(
                            level = warningLevel,
                            memoryAvailableBytes = memInfo.availMem,
                            activeModelIds = wildEdge.handlesLock.withLock {
                                wildEdge.handles.keys.toList()
                            },
                            triggeredUnload = false,
                        )
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit

                    @Suppress("DEPRECATION")
                    override fun onLowMemory() = Unit
                }
                appContext.registerComponentCallbacks(callback)
                wildEdge.memoryCallbackUnregister = { appContext.unregisterComponentCallbacks(callback) }
            }

            return wildEdge
        }

        private fun parseDsn(dsn: String): Pair<String, String> {
            val uri = URI(dsn)
            val secret = uri.userInfo
                ?: error("DSN must include project secret: https://<secret>@ingest.wildedge.dev/<key>")
            val host = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            return secret to host
        }

        private fun buildAttachmentPipeline(
            host: String,
            secret: String,
            filesDir: File,
        ): Pair<AttachmentQueue?, AttachmentConsumer?> {
            if (!enableAttachments) return null to null

            val attachmentQueue = AttachmentQueue(
                file = attachmentStoreFile ?: File(filesDir, "wildedge/attachments.blob"),
                strategy = attachmentStorageStrategy,
            )
            val attachmentTransmitter = AttachmentTransmitter(
                host = host,
                apiKey = secret,
                timeoutMs = attachmentTransmitTimeoutMs,
                log = { msg -> if (debug) Log.d("wildedge", msg) },
            )
            val attachmentConsumer = AttachmentConsumer(
                queue = attachmentQueue,
                transmitter = attachmentTransmitter,
                flushIntervalMs = attachmentFlushIntervalMs,
                maxAttachmentAgeMs = maxAttachmentAgeMs,
                log = { msg -> if (debug) Log.d("wildedge", msg) },
            ).also { it.start() }

            return attachmentQueue to attachmentConsumer
        }
    }

    /** Factory methods for [WildEdge]. */
    companion object {
        @Volatile private var instance: WildEdgeClient? = null

        /** Initialises the SDK, stores the result as the shared instance, and returns it. */
        fun init(context: Context, block: Builder.() -> Unit = {}): WildEdgeClient =
            Builder(context).apply(block).build().also { instance = it }

        /**
         * Returns the shared [WildEdgeClient] set by [init] or the manifest provider.
         * @throws IllegalStateException if neither has run yet.
         */
        fun getInstance(): WildEdgeClient = instance
            ?: error(
                "WildEdge is not initialized. " +
                    "Add <meta-data android:name=\"dev.wildedge.dsn\" android:value=\"...\"/> " +
                    "to AndroidManifest.xml, or call WildEdge.init() in Application.onCreate()."
            )

        /** Returns the shared instance, or `null` if not yet initialized. */
        fun instanceOrNull(): WildEdgeClient? = instance

        /** Clears the shared instance. For tests only. */
        internal fun clearInstance() { instance = null }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}

// Maps ComponentCallbacks2 trim levels to WildEdge severity strings.
// Returns null for levels that are normal lifecycle events (UI hidden, background caching)
// and don't indicate memory pressure relevant to ML workloads.
private fun detectAppVersion(context: Context): String? = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
} catch (_: Exception) { null }

@Suppress("DEPRECATION")
private fun trimMemoryLevel(level: Int): MemoryWarningLevel? = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryWarningLevel.Warning
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryWarningLevel.Serious
    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryWarningLevel.Warning
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryWarningLevel.Critical
    else -> null
}
