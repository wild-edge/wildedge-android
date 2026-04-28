package dev.wildedge.sdk

internal object Config {
    val SDK_VERSION: String get() = BuildConfig.SDK_VERSION
    const val PROTOCOL_VERSION = "1.0"

    const val ENV_DSN = "WILDEDGE_DSN"
    const val ENV_DEBUG = "WILDEDGE_DEBUG"

    const val DEFAULT_FLUSH_INTERVAL_MS = 60_000L
    const val DEFAULT_BATCH_SIZE = 10
    const val DEFAULT_MAX_QUEUE_SIZE = 200
    const val DEFAULT_MAX_EVENT_AGE_MS = 900_000L // 15 min
    const val DEFAULT_SAMPLING_INTERVAL_MS = 30_000L
    const val DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MS = 5_000L
    const val DEFAULT_DEAD_LETTER_MAX_BATCHES = 10
    const val DEFAULT_LOW_CONFIDENCE_THRESHOLD = 0.5f

    const val BACKOFF_MIN_MS = 1_000L
    const val BACKOFF_MAX_MS = 60_000L
    const val BACKOFF_MULTIPLIER = 2.0
    const val IDLE_POLL_MS = 5_000L

    const val HTTP_TIMEOUT_MS = 15_000
    const val ERROR_MSG_MAX_LEN = 200

    const val PREFS_NAME = "wildedge"
    const val PREFS_DEVICE_ID = "device_id"
}
