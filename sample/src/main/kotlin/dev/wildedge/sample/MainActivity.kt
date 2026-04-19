package dev.wildedge.sample

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.wildedge.sample.databinding.ActivityMainBinding
import dev.wildedge.sdk.FeedbackType
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.integrations.decorate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wildEdge: WildEdgeClient

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

        if (true) { // !modelFile.exists()) {
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
        val (decorator, inputShape, outputShape) = withContext(Dispatchers.IO) {
            val interpreter = Interpreter(modelFile, Interpreter.Options().apply { numThreads = 2 })
            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            Triple(
                wildEdge.decorate(
                    interpreter,
                    modelId = "mobilenet-v1",
                    modelVersion = "1.0",
                    quantization = "uint8",
                ),
                inShape,
                outShape,
            )
        }

        if (inputShape.size != 4 || inputShape[0] != 1 || inputShape[3] != 3) {
            log("Unsupported input shape: ${inputShape.joinToString(prefix = "[", postfix = "]")}")
            decorator.close()
            return
        }

        val inputHeight = inputShape[1]
        val inputWidth = inputShape[2]
        val inputChannels = inputShape[3]
        val outputClasses = outputShape.lastOrNull() ?: 1001

        log("Input shape: ${inputShape.joinToString(prefix = "[", postfix = "]")}")
        log("Output shape: ${outputShape.joinToString(prefix = "[", postfix = "]")}")

        log("Running 10 inferences on synthetic input...")
        val lines = withContext(Dispatchers.Default) {
            wildEdge.trace("demo-batch") { trace ->
                (1..10).map { i ->
                    trace.span("inference-$i") {
                        val inputSize = inputHeight * inputWidth * inputChannels
                        val input = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
                        repeat(inputSize) { idx ->
                            input.put(((i * 17 + idx) % 256).toByte())
                        }
                        input.rewind()

                        val output = Array(1) { ByteArray(outputClasses) }
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
