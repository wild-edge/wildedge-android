package dev.wildedge.sdk

import dev.wildedge.sdk.events.isoNow
import java.util.UUID

internal fun buildBatch(
    device: DeviceInfo,
    models: Map<String, Map<String, Any?>>,
    events: List<Map<String, Any?>>,
    sessionId: String,
    createdAt: String,
    lowConfidenceThreshold: Float,
): String {
    val strippedEvents = events.map { event ->
        event.filterKeys { !it.startsWith("__we_") }
    }

    val batch = mutableMapOf<String, Any?>(
        "protocol_version" to Config.PROTOCOL_VERSION,
        "device" to device.toMap(),
        "models" to models,
        "session_id" to sessionId,
        "batch_id" to UUID.randomUUID().toString(),
        "created_at" to createdAt,
        "sent_at" to isoNow(),
        "events" to strippedEvents,
    )

    val sampling = buildSampling(strippedEvents, lowConfidenceThreshold)
    if (sampling != null) batch["sampling"] = sampling

    return batch.toJson()
}

private fun buildSampling(
    events: List<Map<String, Any?>>,
    threshold: Float,
): Map<String, Any?>? {
    val inferenceEvents = events.filter { it["event_type"] == "inference" }
    if (inferenceEvents.isEmpty()) return null

    val byModel = inferenceEvents.groupBy { it["model_id"] as? String ?: "unknown" }
    val result = mutableMapOf<String, Any?>("low_confidence_threshold" to threshold)

    for ((modelId, modelEvents) in byModel) {
        val lowConf = modelEvents.count { event ->
            @Suppress("UNCHECKED_CAST")
            val inferencePart = event["inference"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val outputMeta = inferencePart?.get("output_meta") as? Map<String, Any?>
            val avgConf = (outputMeta?.get("avg_confidence") as? Number)?.toFloat()
            avgConf != null && avgConf < threshold
        }
        result[modelId] = mapOf(
            "total_inference_events_seen" to modelEvents.size,
            "total_inference_events_sent" to modelEvents.size,
            "low_confidence_seen" to lowConf,
            "low_confidence_sent" to lowConf,
        )
    }
    return result
}
