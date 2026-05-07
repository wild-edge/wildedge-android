# WildEdge SDK — Performance Benchmarks

Uses [Jetpack Microbenchmark](https://developer.android.com/studio/profile/microbenchmark-overview). Requires a connected device.

## Running

```bash
./gradlew :benchmark:connectedReleaseAndroidTest
```

Results are written to `build/outputs/` and printed to logcat (`adb logcat -s Benchmark`).

For published results, lock device clocks first:

```bash
adb shell cmd powermanager set-thermal-headroom-is-fixed true
adb shell setprop debug.benchmark.frozenClocks 1
```

## Tests

**`InferenceOverheadBenchmark`**

| Test | What it measures |
|---|---|
| `baseline_Xms` | Inference work, no SDK. |
| `sdkOnly_*` | `trackInference()` call with no inference work. |
| `withSdk_Xms_*` | Inference + tracking. `withSdk - baseline = overhead`. |

**`ThroughputBenchmark`**

| Test | What it measures |
|---|---|
| `burst_100calls_sequential` | 100 calls back-to-back. Time/100 = per-call cost under queue pressure. |
| `burst_100calls_2threads` | Two concurrent inference threads. Measures queue lock contention. |
| `llmTokenStreaming_20tokensPerGeneration` | 20 per-token tracking calls per generation. |

## Results

> Pixel 9 Pro, Android 15, locked clocks, release build, WildEdge v0.1.0

### Inference overhead

| Test | Median | p99 |
|---|---|---|
| `baseline_1ms` | 1.02 ms | 1.08 ms |
| `withSdk_1ms_imageClassification` | 1.03 ms | 1.10 ms |
| **Overhead** | **~10 µs** | **~20 µs** |
| `baseline_50ms` | 50.1 ms | 50.4 ms |
| `withSdk_50ms_textGeneration` | 50.1 ms | 50.4 ms |
| **Overhead** | **~12 µs** | **~22 µs** |

Overhead is constant (~10–15 µs) regardless of inference duration. At 500 ms per generation: 0.003%.

### SDK-only cost

| Test | Median | p99 |
|---|---|---|
| `sdkOnly_imageClassification` | 10 µs | 18 µs |
| `sdkOnly_textGeneration` | 11 µs | 20 µs |

### Throughput

| Test | Total (100 calls) | Per-call |
|---|---|---|
| `burst_100calls_sequential` | 1.1 ms | 11 µs |
| `burst_100calls_2threads` | 1.3 ms | 13 µs |
| `llmTokenStreaming_20tokensPerGeneration` | 0.24 ms | 12 µs |

2 µs added per call under 2-thread contention.

### Memory

| Metric | Value |
|---|---|
| Static footprint | ~1.5 MB (queue + 2 daemon threads) |
| Per-inference allocation | ~2 KB (2 short-lived Maps, GC'd within seconds) |
| Retained heap after 1000 inferences | ~1.5 MB (events flushed) |

## Device matrix

| Device | Chip | Class |
|---|---|---|
| Pixel 9 Pro | Tensor G4 | High-end |
| Pixel 7 | Tensor G2 | Mid-range |
| Samsung Galaxy A54 | Exynos 1380 | Budget |

On budget devices, GC pauses (1–5 ms) may appear at >30 sustained inferences/sec.
