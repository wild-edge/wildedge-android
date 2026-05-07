# Benchmark

Measures the per-call overhead WildEdge adds to an inference call.
Uses [Jetpack Microbenchmark](https://developer.android.com/studio/profile/microbenchmark-overview).

Requires a physical Android device. Emulator results are not reliable.

---

## Setup

Lock device clocks before running. Without this, thermal throttling introduces noise.

**Pixel devices (recommended):**
```bash
adb shell cmd powermanager set-thermal-headroom-is-fixed true
adb shell setprop debug.benchmark.frozenClocks 1
```

**Other devices:** pin CPU frequency via `/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor` if available, or accept ~10% variance.

---

## Running

```bash
./gradlew :benchmark:connectedReleaseAndroidTest
```

Must be `release`: debug builds disable JIT and produce inflated numbers.

Stream results to logcat:
```bash
adb logcat -s Benchmark
```

Results are written to:
```
benchmark/build/outputs/connected_android_test_additional_output/<device>/
```

---

## Output

Each test produces a JSON entry in `benchmarkData.json`:

```json
{
  "name": "withSdk_1ms_imageClassification",
  "className": "dev.wildedge.benchmark.InferenceOverheadBenchmark",
  "metrics": {
    "timeNs": {
      "minimum":   1028441,
      "maximum":   1184332,
      "median":    1041200,
      "runs": [1041200, 1038900, ...]
    }
  }
}
```

All times are in nanoseconds.

---

## Analyzing results

### SDK overhead

```
overhead = withSdk_Xms.median - baseline_Xms.median
```

Example:
```
baseline_1ms.median    = 1,021,000 ns  (1.02 ms)
withSdk_1ms.median     = 1,032,000 ns  (1.03 ms)
overhead               =    11,000 ns  (~11 µs)
```

Cross-check with `sdkOnly_*`, which measures `trackInference()` with no inference work. It should land within a few µs of the computed overhead above.

### Throughput

`burst_100calls_sequential` reports time for 100 calls as one iteration:

```
burst_100calls_sequential.median = 1,100,000 ns
per-call cost under load         = 1,100,000 / 100 = 11,000 ns (~11 µs)
```

Compare `burst_100calls_sequential` vs `burst_100calls_2threads` to quantify lock contention:

```
sequential per-call   = 11 µs
2-thread per-call     = 13 µs
contention cost       =  2 µs
```

### p99 vs median

p99 captures GC pauses and scheduling jitter. Expected range on a locked physical device:

```
median   ~10-15 µs
p99      ~20-30 µs   (2x ratio is normal)
```

A p99/median ratio above 3x on a locked device points to GC pressure.

### Comparing across devices

Pull `benchmarkData.json` from each device and compare `median` for the same test name. Overhead should stay within 2-3x from a high-end to a budget device. Larger gaps point to GC pause frequency on the budget device.

```bash
adb pull /sdcard/Download/benchmark_results/ ./results/
```

### Comparing across SDK versions

Commit `benchmarkData.json` with each release tag. To diff two versions:

```bash
jq '.benchmarks[] | {name: .name, median: .metrics.timeNs.median}' v0.1.0/benchmarkData.json > v0.1.0.txt
jq '.benchmarks[] | {name: .name, median: .metrics.timeNs.median}' v0.2.0/benchmarkData.json > v0.2.0.txt
diff v0.1.0.txt v0.2.0.txt
```

A regression is any test where `median` increases by more than 20% on the same device.

---

## What is not measured

- **Real model inference.** `inferenceWork()` is a CPU busy-wait, not an actual model. It isolates SDK overhead from model variance.
- **Network / flush cost.** The Consumer runs but the DSN points to a non-existent port. Queue drain is not in these numbers.
- **Model load time.** `trackLoad()` runs in `setUp()`, outside `measureRepeated`.
