package dev.wildedge.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelHandle
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdgeClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Per-call overhead: withSdk_Xms - baseline_Xms = SDK cost.
// Run on a physical device with locked clocks for published results.
@RunWith(AndroidJUnit4::class)
class InferenceOverheadBenchmark {

    @get:Rule val benchmarkRule = BenchmarkRule()

    private lateinit var client: WildEdgeClient
    private lateinit var handle: ModelHandle

    @Before fun setUp() {
        client = benchmarkClient()
        handle = client.benchmarkHandle()
    }

    @After fun tearDown() {
        client.close(timeoutMs = 100)
    }

    // baseline

    @Test fun baseline_1ms() = benchmarkRule.measureRepeated {
        inferenceWork(1)
    }

    @Test fun baseline_10ms() = benchmarkRule.measureRepeated {
        inferenceWork(10)
    }

    @Test fun baseline_50ms() = benchmarkRule.measureRepeated {
        inferenceWork(50)
    }

    // SDK tracking only — no inference work

    @Test fun sdkOnly_imageClassification() = benchmarkRule.measureRepeated {
        handle.trackInference(
            durationMs = 1,
            inputModality = InputModality.Image,
            outputModality = OutputModality.Classification,
        )
    }

    @Test fun sdkOnly_textGeneration() = benchmarkRule.measureRepeated {
        handle.trackInference(
            durationMs = 50,
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
        )
    }

    // inference + tracking

    @Test fun withSdk_1ms_imageClassification() = benchmarkRule.measureRepeated {
        val start = System.currentTimeMillis()
        inferenceWork(1)
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = InputModality.Image,
            outputModality = OutputModality.Classification,
        )
    }

    @Test fun withSdk_10ms_imageClassification() = benchmarkRule.measureRepeated {
        val start = System.currentTimeMillis()
        inferenceWork(10)
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = InputModality.Image,
            outputModality = OutputModality.Classification,
        )
    }

    @Test fun withSdk_50ms_textGeneration() = benchmarkRule.measureRepeated {
        val start = System.currentTimeMillis()
        inferenceWork(50)
        handle.trackInference(
            durationMs = (System.currentTimeMillis() - start).toInt(),
            inputModality = InputModality.Text,
            outputModality = OutputModality.Generation,
        )
    }
}
