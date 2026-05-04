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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

// Sustained and concurrent tracking load. Reported time / N = per-call cost under pressure.
@RunWith(AndroidJUnit4::class)
class ThroughputBenchmark {

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

    // 100 sequential calls. Time / 100 = per-call cost under queue pressure.
    @Test fun burst_100calls_sequential() = benchmarkRule.measureRepeated {
        repeat(100) {
            handle.trackInference(
                durationMs = 10,
                inputModality = InputModality.Text,
                outputModality = OutputModality.Generation,
            )
        }
    }

    // 2 threads × 50 calls — measures queue lock contention.
    @Test fun burst_100calls_2threads() = benchmarkRule.measureRepeated {
        val latch = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) {
            pool.execute {
                repeat(50) {
                    handle.trackInference(
                        durationMs = 10,
                        inputModality = InputModality.Text,
                        outputModality = OutputModality.Generation,
                    )
                }
                latch.countDown()
            }
        }
        latch.await()
        pool.shutdown()
    }

    // 20 per-token tracking calls per generation — models streaming LLM output.
    @Test fun llmTokenStreaming_20tokensPerGeneration() = benchmarkRule.measureRepeated {
        repeat(20) {
            handle.trackInference(
                durationMs = 100,
                inputModality = InputModality.Text,
                outputModality = OutputModality.Generation,
            )
        }
    }
}
