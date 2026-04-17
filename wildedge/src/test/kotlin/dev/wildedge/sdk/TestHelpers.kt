package dev.wildedge.sdk

internal fun testWildEdge() = WildEdge(
    noop = false,
    queue = EventQueue(),
    registry = ModelRegistry(),
    consumer = null,
    hardwareSampler = null,
    debug = false,
)

internal fun fakeDevice(
    deviceId: String = "test-device-id",
    deviceModel: String = "Test Device",
    osVersion: String = "Android 14",
) = DeviceInfo(
    deviceId = deviceId,
    deviceModel = deviceModel,
    osVersion = osVersion,
    appVersion = "1.0.0",
    locale = "en-US",
    timezone = "UTC",
)

internal fun fakeConsumer(
    queue: EventQueue = EventQueue(),
    transmitter: Transmitter,
    pendingDir: java.io.File,
    deadLetterDir: java.io.File,
    batchSize: Int = Config.DEFAULT_BATCH_SIZE,
): Consumer = Consumer(
    queue = queue,
    transmitter = transmitter,
    device = fakeDevice(),
    registry = ModelRegistry(),
    sessionId = "test-session",
    createdAt = "2026-01-01T00:00:00.000Z",
    pendingStore = PendingBatchStore(pendingDir),
    deadLetterStore = DeadLetterStore(deadLetterDir),
    batchSize = batchSize,
)
