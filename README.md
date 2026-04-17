# WildEdge Android SDK

[![CI](https://github.com/wild-edge/wildedge-kotlin/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/wild-edge/wildedge-kotlin/actions/workflows/ci.yml)

On-device ML inference monitoring for Android. Tracks latency, confidence, drift, and hardware metrics without ever sending raw inputs.

> **Pre-release:** API is unstable until v1.0.

## Install

Add to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.wildedge:wildedge-android:<version>")
}
```

## Setup

```kotlin
val wildEdge = WildEdge.init(applicationContext) {
    dsn = "https://<secret>@ingest.wildedge.dev/<key>" // or WILDEDGE_DSN env var
    appVersion = BuildConfig.VERSION_NAME
}
```

If no DSN is set, the client becomes a no-op and logs a warning. No DSN = zero overhead, so it's safe to ship in all build variants.

## Integrations

### TFLite

Pass the model `File` and `modelId`/`quantization` are inferred from the filename:

```kotlin
val modelFile = File(modelPath) // e.g. "yolo_v8_int8.tflite"
val interpreter = Interpreter(modelFile, Interpreter.Options())
val tracked = wildEdge.decorate(interpreter, modelFile)
// modelId = "yolo_v8_int8", quantization = "int8"

tracked.run(inputBuffer, outputBuffer)
tracked.close()
```

Override when you need explicit control:

```kotlin
val tracked = wildEdge.decorate(interpreter, modelId = "yolo-v8", quantization = "int8")
```

### ONNX Runtime

```kotlin
val modelFile = File(modelPath) // e.g. "face_detector_fp16.onnx"
val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
val tracked = wildEdge.decorate(session, modelFile)
// modelId = "face_detector_fp16", quantization = "f16"

val result = tracked.run(inputs)
tracked.close()
```

### MLKit

Register a handle, then chain `.trackWith()` on any `Task<T>`:

```kotlin
val handle = wildEdge.registerMlKitModel("face-detector")

faceDetector.process(image).trackWith(handle) { faces ->
    DetectionOutputMeta(numPredictions = faces.size)
    // pass avgConfidence if your detector exposes a per-detection score
}
```

### LiteRT LLM (litertlm)

Wrap the `ResultListener` before passing it to `sendMessageAsync` or `runInference`:

```kotlin
val handle = wildEdge.registerLiteRtModel("gemma-3n", quantization = "int4")

// At inference time — wrap the listener, pass inputMeta for token counts
val inputMeta = WildEdge.analyzeText(userInput)
val trackedListener = resultListener.trackWith(handle, inputMeta)

conversation.sendMessageAsync(contents, trackedListenerAsCallback)
```

Captures: total duration, time to first token, tokens/sec, estimated tokens in/out.

Works identically for AICore — same `ResultListener` shape, same wrapper.

### Play Services TFLite

```kotlin
val interpreter = InterpreterApi.create(modelFile, InterpreterApi.Options())
val tracked = wildEdge.decorate(interpreter, modelFile)
// or: wildEdge.decorate(interpreter, modelId = "my-model")

tracked.run(inputBuffer, outputBuffer)
tracked.close()
```

### Manual tracking

For any other framework:

```kotlin
val handle = wildEdge.registerModel("my-model", ModelInfo(
    modelName = "MobileNet",
    modelVersion = "v3",
    modelSource = "local",
    modelFormat = "custom",
))

handle.trackLoad(durationMs = loadMs, accelerator = Accelerator.CPU, coldStart = true)

val start = System.currentTimeMillis()
val output = model.run(input)
handle.trackInference(
    durationMs = (System.currentTimeMillis() - start).toInt(),
    inputModality = InputModality.Image,
    outputModality = OutputModality.Detection,
)

handle.trackUnload()
```

## Output metadata

Pass structured output metadata to enrich analytics:

```kotlin
handle.trackInference(
    durationMs = ms,
    outputMeta = DetectionOutputMeta(
        numPredictions = result.size,
        avgConfidence = result.map { it.score }.average().toFloat(),
    ).toMap(),
)
```

Available types: `DetectionOutputMeta`, `GenerationOutputMeta`, `EmbeddingOutputMeta`.

## Configuration

| Parameter | Default | Description |
|---|---|---|
| `dsn` | - | `https://<secret>@ingest.wildedge.dev/<key>` (or `WILDEDGE_DSN`) |
| `appVersion` | `null` | App version string attached to every batch |
| `batchSize` | `10` | Events per HTTP request |
| `maxQueueSize` | `200` | Max in-memory events; oldest dropped on overflow |
| `flushIntervalMs` | `60_000` | How often the consumer wakes to send |
| `maxEventAgeMs` | `900_000` | Events older than this go to the dead-letter store |
| `samplingIntervalMs` | `30_000` | Hardware polling interval; `null` to disable |
| `lowConfidenceThreshold` | `0.5` | Threshold for the sampling envelope |
| `debug` | `false` | Verbose logcat output (or `WILDEDGE_DEBUG=true`) |
| `strict` | `false` | Throw on queue overflow instead of dropping |

## Lifecycle

Call `flush()` before your process exits to drain remaining events.
`close()` is main-thread safe:

- Off main thread: blocks until flush timeout, then stops background worker.
- On main thread: flushes asynchronously to avoid ANR risk.

Default shutdown flush timeout is 5 seconds.

```kotlin
override fun onTerminate() {
    wildEdge.close()
    super.onTerminate()
}
```

## Development

### Requirements

- JDK 17+
- Gradle 9.4+ (via wrapper — `./gradlew` downloads it automatically)
- Android SDK with `compileSdk 35` installed

### Run unit tests

```bash
./gradlew :wildedge:testDebugUnitTest
```

Tests run on the JVM via Robolectric — no emulator or device needed.

### Run a single test class

```bash
./gradlew :wildedge:testDebugUnitTest --tests "dev.wildedge.sdk.EventQueueTest"
```

### Build the library

```bash
./gradlew :wildedge:assembleRelease
```

Output: `wildedge/build/outputs/aar/wildedge-release.aar`

### Run lint

```bash
./gradlew :wildedge:lint
```

### Publish to local Maven (for local app integration testing)

```bash
./gradlew :wildedge:publishToMavenLocal
```

Then in your app's `build.gradle.kts`:

```kotlin
repositories { mavenLocal() }
dependencies { implementation("dev.wildedge:wildedge-android:<version>") }
```

## Requirements

- minSdk 24 (Android 7.0)
- No required transitive dependencies
- TFLite / ONNX Runtime declared as `compileOnly`; bring your own version
