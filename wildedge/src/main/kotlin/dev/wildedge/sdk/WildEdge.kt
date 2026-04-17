package dev.wildedge.sdk

import android.content.Context
import android.os.Looper
import android.util.Log
import dev.wildedge.sdk.events.HardwareContext
import dev.wildedge.sdk.events.buildMemoryWarningEvent
import dev.wildedge.sdk.events.buildSpanEvent
import dev.wildedge.sdk.events.isoNow
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WildEdge internal constructor(
    private val noop: Boolean,
    private val queue: EventQueue,
    private val registry: ModelRegistry,
    private val consumer: Consumer?,
    private val hardwareSampler: HardwareSampler?,
    private val debug: Boolean,
) : WildEdgeClient, SpanOwner {
    private val handles = mutableMapOf<String, ModelHandle>()
    private val handlesLock = ReentrantLock()
    @Volatile private var closed = false
    private val activeSpan = ThreadLocal<SpanContext?>()

    override fun registerModel(modelId: String, info: ModelInfo): ModelHandle {
        if (noop || closed) return ModelHandle(modelId, info, {}, { null })
        return handlesLock.withLock {
            handles.getOrPut(modelId) {
                registry.register(modelId, info)
                if (debug) Log.d("wildedge", "registered model id=$modelId format=${info.modelFormat}")
                ModelHandle(modelId, info, ::publish, { hardwareSampler?.snapshot() }, { activeSpan.get() })
            }
        }
    }

    internal fun publish(event: MutableMap<String, Any?>) {
        if (noop || closed) return
        if (debug) Log.d("wildedge", "queuing event type=${event["event_type"]} model=${event["model_id"]}")
        queue.add(event)
    }

    override fun trackMemoryWarning(
        level: String,
        memoryAvailableBytes: Long,
        activeModelIds: List<String>,
        triggeredUnload: Boolean,
        unloadedModelId: String?,
    ) {
        publish(buildMemoryWarningEvent(
            level = level,
            memoryAvailableBytes = memoryAvailableBytes,
            activeModelIds = activeModelIds,
            triggeredUnload = triggeredUnload,
            unloadedModelId = unloadedModelId,
        ).toMutableMap())
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
        hardwareSampler?.stop()
        val c = consumer ?: return
        val blocking = !isMainThread()
        if (!blocking && debug) {
            Log.w("wildedge", "close() called on main thread; closing asynchronously")
        }
        c.close(timeoutMs = timeoutMs, blocking = blocking)
    }

    override fun <T> trace(name: String, attributes: Map<String, Any?>?, block: (SpanContext) -> T): T =
        runSpan(name = name, traceId = UUID.randomUUID().toString(), parentSpanId = null, attributes = attributes, block = block)

    override fun <T> runSpan(
        name: String,
        traceId: String,
        parentSpanId: String?,
        attributes: Map<String, Any?>?,
        block: (SpanContext) -> T,
    ): T {
        val ctx = SpanContext(
            traceId = traceId,
            spanId = UUID.randomUUID().toString(),
            parentSpanId = parentSpanId,
            owner = this,
        )
        val prev = activeSpan.get()
        activeSpan.set(ctx)
        val startMs = System.currentTimeMillis()
        try {
            return block(ctx)
        } finally {
            activeSpan.set(prev)
            val durationMs = System.currentTimeMillis() - startMs
            if (debug) Log.d("wildedge", "span name=$name trace=${ctx.traceId} duration=${durationMs}ms")
            publish(buildSpanEvent(
                traceId = ctx.traceId,
                spanId = ctx.spanId,
                parentSpanId = ctx.parentSpanId,
                name = name,
                durationMs = durationMs,
                attributes = attributes,
            ).toMutableMap())
        }
    }

    override val pendingCount: Int get() = queue.length()

    // DSL builder

    class Builder(private val context: Context) {
        var dsn: String? = System.getenv(Config.ENV_DSN)
        var appVersion: String? = null
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
        var strict: Boolean = false

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
            val transmitter = Transmitter(host, secret)
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

            if (debug) Log.d("wildedge", "wildedge: initialized session=$sessionId")

            return WildEdge(
                noop = false,
                queue = eventQueue,
                registry = modelRegistry,
                consumer = consumer,
                hardwareSampler = sampler,
                debug = debug,
            )
        }

        private fun parseDsn(dsn: String): Pair<String, String> {
            val uri = URI(dsn)
            val secret = uri.userInfo
                ?: error("DSN must include project secret: https://<secret>@ingest.wildedge.dev/<key>")
            val host = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            return secret to host
        }
    }

    companion object {
        fun init(context: Context, block: Builder.() -> Unit = {}): WildEdgeClient =
            Builder(context).apply(block).build()
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
