package dev.wildedge.sample

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.wildedge.sample.databinding.ActivityMainBinding
import dev.wildedge.sdk.FeedbackType
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.integrations.decorate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wildEdge: WildEdge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wildEdge = WildEdge.init(this) {
            dsn = BuildConfig.WILDEDGE_DSN
            debug = true
        }

        if (BuildConfig.WILDEDGE_DSN.isEmpty()) {
            binding.tvStatus.text = "noop mode -- add wildedge.dsn=... to local.properties to enable reporting"
        } else {
            binding.tvStatus.text = "reporting enabled"
        }

        binding.btnRun.setOnClickListener {
            binding.btnRun.isEnabled = false
            lifecycleScope.launch {
                try {
                    runDemo()
                } finally {
                    binding.btnRun.isEnabled = true
                }
            }
        }
    }

    private suspend fun runDemo() {
        val modelFile = File(filesDir, "mobilenet_v1_quant.tflite")

        // Register the model upfront so the download event carries the correct model ID.
        val handle = wildEdge.registerModel(
            "mobilenet-v1",
            ModelInfo(
                modelName = "MobileNet V1",
                modelVersion = "1.0",
                modelSource = "remote",
                modelFormat = "tflite",
                quantization = "uint8",
            ),
        )

        if (!modelFile.exists()) {
            log("Downloading MobileNet V1 quant (~4 MB)...")
            val ok = downloadModel(handle, modelFile)
            if (!ok) {
                log("Download failed. Check network connection.")
                return
            }
            log("Download complete.")
        } else {
            log("Model already cached.")
        }

        log("Loading interpreter...")
        val decorator = withContext(Dispatchers.IO) {
            wildEdge.decorate(
                Interpreter(modelFile, Interpreter.Options().apply { numThreads = 2 }),
                modelId = "mobilenet-v1",
                modelVersion = "1.0",
                quantization = "uint8",
            )
        }

        log("Running 10 inferences on synthetic input...")
        val lines = withContext(Dispatchers.Default) {
            wildEdge.trace("demo-batch") { trace ->
                (1..10).map { i ->
                    trace.span("inference-$i") {
                        val input = ByteArray(1 * 224 * 224 * 3) { ((i * 17 + it) % 256).toByte() }
                        val output = Array(1) { ByteArray(1001) }
                        decorator.run(input, output)
                        val top = output[0].indices.maxByOrNull { output[0][it].toInt() and 0xFF } ?: 0
                        val score = (output[0][top].toInt() and 0xFF) / 2.55f
                        "  run $i  class $top  score ${"%.1f".format(score)}%"
                    }
                }
            }
        }
        lines.forEach { log(it) }

        // Simulate user accepting the top result — links to the last inference automatically.
        handle.trackFeedback(FeedbackType.Accepted)
        log("Tracked feedback: accepted")

        decorator.close()
        log("Pending events: ${wildEdge.pendingCount}")

        withContext(Dispatchers.IO) { wildEdge.flush(timeoutMs = 5_000L) }
        log("Flushed. Pending after flush: ${wildEdge.pendingCount}")
    }

    private fun log(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wildEdge.close()
    }
}
