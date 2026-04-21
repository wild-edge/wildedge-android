<p align="center">
  <img src="docs/wildedge-logo-text.svg" alt="WildEdge" height="72"/>
</p>

<br/>

[![CI](https://github.com/wild-edge/wildedge-android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/wild-edge/wildedge-android/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-24-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

On-device ML inference monitoring for Android. Tracks latency, confidence, drift, and hardware metrics without ever sending raw inputs.

> **Pre-release:** API is unstable until v1.0.

## Quick start

Add your DSN to `AndroidManifest.xml`:

```xml
<application ...>
    <meta-data
        android:name="dev.wildedge.dsn"
        android:value="@string/wildedge_dsn" />
</application>
```

Then wrap your TFLite interpreter:

```kotlin
val wildEdge = WildEdge.getInstance()
val interpreter = wildEdge.decorate(
    Interpreter(modelFile, Interpreter.Options()), modelFile
)

interpreter.run(inputBuffer, outputBuffer)
interpreter.close()
```

For other frameworks, see [Integrations](#integrations) below.

No DSN? The client runs in noop mode: all calls work, events are discarded. Safe to ship in all build variants.

## Install

> Not yet published. Watch [Releases](https://github.com/wild-edge/wildedge-android/releases) for the first published version.

```kotlin
dependencies {
    implementation("dev.wildedge:wildedge-android:0.1.0")
}
```

With a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
wildedge = "0.1.0"

[libraries]
wildedge = { group = "dev.wildedge", name = "wildedge-android", version.ref = "wildedge" }
```

```kotlin
dependencies {
    implementation(libs.wildedge)
}
```

## Setup

### Option A: manifest

WildEdge initializes itself before `Application.onCreate()` runs. Add to `AndroidManifest.xml`:

```xml
<application ...>
    <meta-data
        android:name="dev.wildedge.dsn"
        android:value="@string/wildedge_dsn" />
</application>
```

```xml
<!-- keep out of source control -->
<resources>
    <string name="wildedge_dsn">https://<pubkey>@ingest.wildedge.dev/<project-id></string>
</resources>
```

```kotlin
val wildEdge = WildEdge.getInstance()
```

### Option B: manual init

```kotlin
val wildEdge: WildEdgeClient = WildEdge.init(applicationContext) {
    dsn = "https://<pubkey>@ingest.wildedge.dev/<project-id>" // or WILDEDGE_DSN env var
}
```

`init()` sets the shared instance, so `WildEdge.getInstance()` works after this call.

## Integrations

### TFLite

`modelId` and `quantization` are inferred from the filename:

```kotlin
val modelFile = File(modelPath) // e.g. "yolo_v8_int8.tflite"
val interpreter = wildEdge.decorate(
    Interpreter(modelFile, Interpreter.Options()), modelFile, modelVersion = "8.0"
)
// modelId = "yolo_v8_int8", quantization = "int8"

interpreter.run(inputBuffer, outputBuffer)
interpreter.close()
```

Explicit override:

```kotlin
val interpreter = wildEdge.decorate(
    Interpreter(modelFile, Interpreter.Options()),
    modelId = "yolo-v8", modelVersion = "8.0", quantization = "int8"
)
```

### ONNX Runtime

```kotlin
val modelFile = File(modelPath) // e.g. "face_detector_fp16.onnx"
val session = wildEdge.decorate(
    env.createSession(modelFile.absolutePath, OrtSession.SessionOptions()),
    modelFile
)
// modelId = "face_detector_fp16", quantization = "f16"

val result = session.run(inputs)
session.close()
```

### MLKit

```kotlin
val handle = wildEdge.registerMlKitModel("face-detector", modelVersion = "16.1")

faceDetector.process(image).trackWith(handle) { faces ->
    DetectionOutputMeta(numPredictions = faces.size)
}
```

### LiteRT LLM (litertlm)

```kotlin
val engineConfig = EngineConfig(modelPath = modelPath)
val engine = wildEdge.decorate(Engine(engineConfig), engineConfig)
val conversation = engine.createConversation()

val listener = resultListener.trackWith(engine.handle, WildEdge.analyzeText(userInput))
conversation.sendMessageAsync(contents, listener)
engine.close()
```

Captures total duration, time to first token, tokens/sec, and estimated tokens in/out. Works identically for AICore.

### Play Services TFLite

```kotlin
val interpreter = wildEdge.decorate(
    InterpreterApi.create(modelFile, InterpreterApi.Options()),
    modelFile
)

interpreter.run(inputBuffer, outputBuffer)
interpreter.close()
```

### Remote models

```kotlin
val handle = wildEdge.registerModel("gpt-4o-mini", ModelInfo(
    modelName = "GPT-4o mini",
    modelVersion = "2024-07-18",
    modelSource = "api",
    modelFormat = "remote",
    inputModality = InputModality.Text,
    outputModality = OutputModality.Generation,
))

val response = handle.trackSuspendInference {
    callRemoteApi(prompt)
}
```

To include token counts from the response:

```kotlin
val response = handle.trackSuspendInference(
    outputMetaExtractor = { r ->
        GenerationOutputMeta(
            tokensIn = r.usage.promptTokens,
            tokensOut = r.usage.completionTokens,
        ).toMap()
    },
) {
    callRemoteApi(prompt)
}
```

### Manual tracking

```kotlin
val handle = wildEdge.registerModel("my-model", ModelInfo(
    modelName = "MobileNet",
    modelVersion = "v3",
    modelSource = "local",
    modelFormat = "custom",
    inputModality = InputModality.Image,
    outputModality = OutputModality.Detection,
))

handle.trackLoad(durationMs = loadMs, accelerator = Accelerator.CPU, coldStart = true)
val output = handle.trackInference { model.run(input) }
handle.trackUnload()
```

## Feedback

```kotlin
handle.trackFeedback(FeedbackType.ThumbsUp)
handle.trackFeedback(FeedbackType.Custom("hallucination"))
```

`trackFeedback` links to the most recent inference on the handle. Pass `relatedInferenceId` to link to an earlier one:

```kotlin
val inferenceId = handle.trackInference(durationMs = ms)
handle.trackFeedback(FeedbackType.Edited, relatedInferenceId = inferenceId, editDistance = 5)
```

| Value | Meaning |
|---|---|
| `FeedbackType.ThumbsUp` | User approved the result |
| `FeedbackType.ThumbsDown` | User rejected the result |
| `FeedbackType.Accepted` | User acted on the result without editing |
| `FeedbackType.Edited` | User accepted but modified the result |
| `FeedbackType.Rejected` | User dismissed or ignored the result |
| `FeedbackType.Custom(value)` | Domain-specific signal (e.g. `"hallucination"`, `"safety_flag"`) |

## Tracing

Group related inferences so the server can reconstruct the full pipeline:

```kotlin
wildEdge.trace("user-query") { trace ->
    val embedding = trace.span("embed") { embedHandle.trackInference { embedModel.run(input) } }
    trace.span("classify") { classifyHandle.trackInference { classifyModel.run(embedding) } }
}
```

- `trace {}` creates a root span and emits a `span` event when the block returns.
- `span {}` creates a child span linked via `parent_span_id`.
- `trackInference()` inside a `trace` or `span` block picks up `trace_id` and `parent_span_id` automatically.
- Explicit `traceId`/`parentSpanId` arguments on `trackInference()` take precedence.

## Output metadata

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
| `dsn` | - | `https://<pubkey>@ingest.wildedge.dev/<project-id>` (or `WILDEDGE_DSN`) |
| `appVersion` | auto-detected | App version string attached to every batch |
| `batchSize` | `10` | Events per HTTP request |
| `maxQueueSize` | `200` | Max in-memory events; oldest dropped on overflow |
| `flushIntervalMs` | `60_000` | How often the consumer wakes to send |
| `maxEventAgeMs` | `900_000` | Events older than this go to the dead-letter store |
| `samplingIntervalMs` | `30_000` | Hardware polling interval; `null` to disable |
| `lowConfidenceThreshold` | `0.5` | Threshold for the sampling envelope |
| `debug` | `false` | Verbose logcat output (or `WILDEDGE_DEBUG=true`) |
| `strict` | `false` | Throw on queue overflow instead of dropping |

## Testing

Declare your field against `WildEdgeClient` and inject `WildEdgeClient.noop()` in tests:

```kotlin
class InferenceService(private val wildEdge: WildEdgeClient) {
    private val handle = wildEdge.registerModel("my-model", ...)

    fun run(input: ByteArray): Result = handle.trackInference { model.run(input) }
}

// In tests:
val service = InferenceService(WildEdgeClient.noop())
```

The noop client runs `trace` and `span` blocks normally but discards all events. No background threads, no DSN required.

## Lifecycle

Call `close()` before your process exits to drain remaining events:

```kotlin
override fun onTerminate() {
    wildEdge.close()
    super.onTerminate()
}
```

`close()` is main-thread safe. On the main thread it flushes asynchronously; off the main thread it blocks until the flush timeout (default 5 seconds).

## AI-assisted integration

Paste the prompt below into your coding agent (Claude Code, Cursor, Copilot, etc.) to wire up WildEdge across your codebase automatically.

```
Integrate the WildEdge Android SDK (dev.wildedge:wildedge-android) into this project.

1. Search the codebase for all ML inference code: TFLite Interpreter, ONNX OrtSession,
   Play Services InterpreterApi, LiteRT Engine/Conversation, MLKit Task calls, and any
   direct HTTP calls to remote LLM APIs (OpenAI, Gemini, etc.).

2. For each one found, wrap it with the right WildEdge integration:
   - TFLite: val interpreter = wildEdge.decorate(Interpreter(modelFile, ...), modelFile, modelVersion = "...")
   - ONNX: val session = wildEdge.decorate(env.createSession(...), modelFile, modelVersion = "...")
   - LiteRT: val engine = wildEdge.decorate(Engine(config), config, modelVersion = "...")
   - MLKit: wildEdge.registerMlKitModel(...) and Task.trackWith(handle)
   - Remote LLM: wildEdge.registerModel("id", ModelInfo(
         inputModality = InputModality.Text, outputModality = OutputModality.Generation, ...))
     Use handle.trackSuspendInference { } for suspend calls. Pass outputMetaExtractor to
     capture token counts from the response:
       outputMetaExtractor = { r ->
           GenerationOutputMeta(tokensIn = r.usage.promptTokens, tokensOut = r.usage.completionTokens).toMap()
       }
   For decorator integrations, assign the result to the same variable name as the original
   so call sites don't change. Only pass modelVersion if you find a real version string
   in the model filename, asset path, or an existing version constant.
   For streaming LLM output (Flow<String>) use flow.trackWith(handle).

3. Set up WildEdge (pick one):
   Option A (zero code): add to AndroidManifest.xml inside <application>:
      <meta-data android:name="dev.wildedge.dsn" android:value="YOUR_DSN" />
   Then call WildEdge.getInstance() wherever inference code lives.
   Option B (manual): call WildEdge.init() in Application.onCreate():
      val wildEdge: WildEdgeClient = WildEdge.init(this) {
          dsn = "YOUR_DSN"   // get yours at wildedge.dev
      }
   Either way, inject WildEdgeClient.noop() in tests instead of the real client.

4. If multiple models run in sequence for a single user request (embed then classify,
   prefill then decode), wrap the pipeline in wildEdge.trace("name") { } so events
   are correlated on the dashboard.

5. Call wildEdge.close() in Application.onTerminate() or the appropriate lifecycle hook.

6. Pass only metadata as inputMeta (WildEdge.analyzeText / analyzeImage).
   WildEdge never transmits raw inputs.
```

## Samples

| Sample | What it shows |
|---|---|
| [image-classification](samples/image-classification) | TFLite image classifier with inference tracking and feedback |
| [local-llm](samples/local-llm) | On-device LLM chat using LiteRT with token metrics |
| [local-llm-agent](samples/local-llm-agent) | LLM agent with tool calling, session spans, and per-turn tracing |
| [cloud-llm](samples/cloud-llm) | Streaming travel itinerary generator using Google AI (Gemini) with TTFT and token tracking |

To run a sample:

1. Connect a device or start an emulator.
2. Copy and fill in your config:
   ```bash
   cp local.properties.example local.properties
   # set sdk.dir and optionally add your DSN
   ```
3. Install:
   ```bash
   ./gradlew :samples:image-classification:installDebug
   ./gradlew :samples:local-llm:installDebug
   ./gradlew :samples:local-llm-agent:installDebug
   ./gradlew :samples:cloud-llm:installDebug
   ```
   `cloud-llm` also requires a Google AI API key (free at https://aistudio.google.com):
   ```
   google.ai.api.key=AIza...
   ```

Without a DSN the samples run in noop mode.

## Development

**Requirements:** JDK 17+, Android SDK with `compileSdk 35`, Gradle 9.4+ (the wrapper downloads it automatically).

```bash
# Unit tests (JVM, no emulator needed)
./gradlew :wildedge:testDebugUnitTest

# Single test class
./gradlew :wildedge:testDebugUnitTest --tests "dev.wildedge.sdk.EventQueueTest"

# Lint
./gradlew :wildedge:lint

# Detekt
./gradlew detekt

# Build AAR
./gradlew :wildedge:assembleRelease
# Output: wildedge/build/outputs/aar/wildedge-release.aar

# Publish to local Maven (for local app integration testing)
./gradlew :wildedge:publishToMavenLocal
```

Local Maven usage:

```kotlin
repositories { mavenLocal() }
dependencies { implementation("dev.wildedge:wildedge-android:0.1.0") }
```

**Runtime requirements:** minSdk 24 (Android 7.0), no required transitive dependencies. TFLite / ONNX Runtime are `compileOnly`; bring your own version.
