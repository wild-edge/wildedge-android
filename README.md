<p align="center">
  <img src="docs/wildedge-logo-text.svg" alt="WildEdge" height="72"/>
</p>

<br/>

[![CI](https://github.com/wild-edge/wildedge-android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/wild-edge/wildedge-android/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-24-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

On-device ML inference monitoring for Android. Tracks latency, confidence, drift, and hardware metrics without ever sending raw inputs.

> **Pre-release:** API is unstable until v1.0.

## Try it

Run the sample app on a device or emulator to see the SDK in action:

1. Connect a device or start an emulator
2. Copy and fill in your config:
   ```bash
   cp local.properties.example local.properties
   # set sdk.dir to your Android SDK path and add your DSN
   ```
3. Install and launch:
   ```bash
   ./gradlew :sample:installDebug
   ```

The sample downloads MobileNet V1, runs 10 inferences, and shows results in a scrolling log. Without a DSN it runs in noop mode: all tracking calls work, events are discarded locally.

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
}
```

If no DSN is set, the client becomes a no-op and logs a warning. No DSN = zero overhead, so it's safe to ship in all build variants.

## Integrations

### TFLite

Pass the model `File` and `modelId`/`quantization` are inferred from the filename:

```kotlin
val modelFile = File(modelPath) // e.g. "yolo_v8_int8.tflite"
val interpreter = Interpreter(modelFile, Interpreter.Options())
val tracked = wildEdge.decorate(interpreter, modelFile, modelVersion = "8.0")
// modelId = "yolo_v8_int8", quantization = "int8"

tracked.run(inputBuffer, outputBuffer)
tracked.close()
```

Override when you need explicit control:

```kotlin
val tracked = wildEdge.decorate(
    interpreter, modelId = "yolo-v8", modelVersion = "8.0", quantization = "int8"
)
```

### ONNX Runtime

```kotlin
val modelFile = File(modelPath) // e.g. "face_detector_fp16.onnx"
val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
val tracked = wildEdge.decorate(session, modelFile, modelVersion = "1.0")
// modelId = "face_detector_fp16", quantization = "f16"

val result = tracked.run(inputs)
tracked.close()
```

### MLKit

Register a handle, then chain `.trackWith()` on any `Task<T>`:

```kotlin
val handle = wildEdge.registerMlKitModel("face-detector", modelVersion = "16.1")

faceDetector.process(image).trackWith(handle) { faces ->
    DetectionOutputMeta(numPredictions = faces.size)
    // pass avgConfidence if your detector exposes a per-detection score
}
```

### LiteRT LLM (litertlm)

Wrap the `ResultListener` before passing it to `sendMessageAsync` or `runInference`:

```kotlin
val handle = wildEdge.registerLiteRtModel(
    "gemma-3n", modelVersion = "1.0", quantization = "int4"
)

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
val tracked = wildEdge.decorate(interpreter, modelFile, modelVersion = "1.0")
// or: wildEdge.decorate(interpreter, modelId = "my-model", modelVersion = "1.0")

tracked.run(inputBuffer, outputBuffer)
tracked.close()
```

### Remote models

For apps that call a remote LLM API directly, use manual tracking. The same `ModelHandle` works — set `modelFormat` to `"remote"` and `modelSource` to `"api"`:

```kotlin
val handle = wildEdge.registerModel("gpt-4o-mini", ModelInfo(
    modelName = "GPT-4o mini",
    modelVersion = "2024-07-18",
    modelSource = "api",
    modelFormat = "remote",
))

val start = System.currentTimeMillis()
val response = callRemoteApi(prompt)
handle.trackInference(
    durationMs = (System.currentTimeMillis() - start).toInt(),
    inputModality = InputModality.Text,
    outputModality = OutputModality.Text,
    outputMeta = GenerationOutputMeta(
        tokensIn = response.usage.promptTokens,
        tokensOut = response.usage.completionTokens,
    ).toMap(),
)
```

This gives you latency, token usage, and error rates for remote calls alongside your on-device models in the same dashboard. If you call the remote model as part of a pipeline with on-device steps, wrap everything in a `trace {}` block so the events are correlated.

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

// Optional: record user feedback linked to the last inference.
handle.trackFeedback(FeedbackType.ThumbsUp)

// Use FeedbackType.Custom for domain-specific signals.
handle.trackFeedback(FeedbackType.Custom("hallucination"))
```

## Feedback types

`FeedbackType` is a sealed class with built-in values for common cases and an escape hatch for custom signals:

| Value | Meaning |
|---|---|
| `FeedbackType.ThumbsUp` | User explicitly approved the result |
| `FeedbackType.ThumbsDown` | User explicitly rejected the result |
| `FeedbackType.Accepted` | User accepted / acted on the result without editing |
| `FeedbackType.Edited` | User accepted but modified the result (pass `editDistance` if available) |
| `FeedbackType.Rejected` | User dismissed or ignored the result |
| `FeedbackType.Custom(value)` | Any domain-specific signal (e.g. `"hallucination"`, `"safety_flag"`) |

`trackFeedback` automatically links to the most recent inference on that handle. Pass `relatedInferenceId` explicitly when linking to an earlier inference:

```kotlin
val inferenceId = handle.trackInference(durationMs = ms)
// ... later, after user interacts ...
handle.trackFeedback(
    FeedbackType.Edited, relatedInferenceId = inferenceId, editDistance = 5
)
```

## Tracing

Group related inferences into a trace so the server can reconstruct the full pipeline:

```kotlin
wildEdge.trace("user-query") { trace ->
    trace.span("embed") {
        val start = System.currentTimeMillis()
        val embedding = embedModel.run(input)
        val durationMs = (System.currentTimeMillis() - start).toInt()
        embedHandle.trackInference(durationMs = durationMs)
        embedding
    }

    trace.span("classify") {
        val start = System.currentTimeMillis()
        val label = classifyModel.run(embedding)
        val durationMs = (System.currentTimeMillis() - start).toInt()
        classifyHandle.trackInference(durationMs = durationMs)
        label
    }
}
```

- `trace {}` creates a root span and emits a `span` event with the total duration when the block returns.
- `span {}` creates a child span linked via `parent_span_id`.
- Any `trackInference()` call inside a `trace` or `span` block automatically picks up `trace_id` and `parent_span_id` — no manual ID threading needed.
- Explicit `traceId`/`parentSpanId` arguments on `trackInference()` take precedence over the propagated context.
- Both `trace` and `span` return the block result, so they compose naturally with your data flow.

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
| `appVersion` | auto-detected | App version string attached to every batch. Override if you use a custom version scheme. |
| `batchSize` | `10` | Events per HTTP request |
| `maxQueueSize` | `200` | Max in-memory events; oldest dropped on overflow |
| `flushIntervalMs` | `60_000` | How often the consumer wakes to send |
| `maxEventAgeMs` | `900_000` | Events older than this go to the dead-letter store |
| `samplingIntervalMs` | `30_000` | Hardware polling interval; `null` to disable |
| `lowConfidenceThreshold` | `0.5` | Threshold for the sampling envelope |
| `debug` | `false` | Verbose logcat output (or `WILDEDGE_DEBUG=true`) |
| `strict` | `false` | Throw on queue overflow instead of dropping |

## Testing

`WildEdge.init()` returns `WildEdgeClient`. Declare your field against the interface so you can substitute a no-op in tests:

```kotlin
// Production
val client: WildEdgeClient = WildEdge.init(context) { dsn = "..." }

// Tests — zero overhead, no background threads, no DSN required
val client: WildEdgeClient = WildEdgeClient.noop()
```

The noop client executes `trace` and `span` blocks normally (so your pipeline logic runs) but discards all events. You can inject it via constructor or a DI framework:

```kotlin
class InferenceService(private val wildEdge: WildEdgeClient) {
    private val handle = wildEdge.registerModel("my-model", ...)

    fun run(input: ByteArray): Result {
        val start = System.currentTimeMillis()
        val result = model.run(input)
        handle.trackInference(durationMs = (System.currentTimeMillis() - start).toInt())
        return result
    }
}

// In tests:
val service = InferenceService(WildEdgeClient.noop())
```

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

## AI-assisted integration

Paste the prompt below into your coding agent (Claude Code, Cursor, Copilot, etc.) to get guided integration. It will scan your codebase, find all ML inference code, and wire up WildEdge with the right pattern for each framework.

```
Integrate the WildEdge Android SDK (dev.wildedge:wildedge-android) into this project.

1. Search the codebase for all ML inference code: TFLite Interpreter, ONNX OrtSession,
   Play Services InterpreterApi, LiteRT Engine/Conversation, MLKit Task calls, and any
   direct HTTP calls to remote LLM APIs (OpenAI, Gemini, etc.).

2. For each one found, wrap it with the right WildEdge integration:
   - TFLite: wildEdge.decorate(interpreter, modelFile, modelVersion = "...")
   - ONNX: wildEdge.decorate(session, modelFile, modelVersion = "...")
   - LiteRT: wildEdge.decorate(engine, config, modelVersion = "...")
   - MLKit: wildEdge.registerMlKitModel(...) and Task.trackWith(handle)
   - Remote LLM: wildEdge.registerModel(...) and handle.trackInference(...)
   Pass a real modelVersion string. Check the model filename, asset path, or any
   version constant already in the code.
   For suspend/coroutine inference code use handle.trackSuspendInference { }.
   For streaming LLM output (Flow<String>) use flow.trackWith(handle).

3. Initialise WildEdge once in Application.onCreate():
      val wildEdge: WildEdgeClient = WildEdge.init(this) {
          dsn = "YOUR_DSN"   // get yours at wildedge.dev
          // appVersion is auto-detected from PackageInfo; override if needed
      }
   Inject it via constructor, DI, or a singleton wherever inference code lives.
   In tests inject WildEdgeClient.noop() instead.

4. If multiple models run in sequence for a single user request (embed then classify,
   prefill then decode), wrap the pipeline in wildEdge.trace("name") { } so events
   are correlated on the dashboard.

5. Call wildEdge.close() in Application.onTerminate() or the appropriate lifecycle hook.

6. Pass only metadata as inputMeta (WildEdge.analyzeText / analyzeImage).
   WildEdge never transmits raw inputs.
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
