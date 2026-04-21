package dev.wildedge.sample.imageclassification

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.wildedge.sample.imageclassification.databinding.ActivityMainBinding
import dev.wildedge.sdk.FeedbackType
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.OutputModality
import dev.wildedge.sdk.WildEdge
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
    private val wildEdge = WildEdge.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = if (getString(R.string.wildedge_dsn).isEmpty())
            "noop mode: add wildedge.dsn=... to local.properties to enable reporting"
        else
            "reporting enabled"

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
                inputModality = InputModality.Image,
                outputModality = OutputModality.Classification,
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

        log("Input: ${inputShape.toList()}  Output: ${outputShape.toList()}")

        val testImages = listOf(
            "red" to Triple(220, 50, 50),
            "green" to Triple(50, 180, 50),
            "blue" to Triple(50, 100, 220),
            "yellow" to Triple(230, 210, 50),
            "orange" to Triple(230, 120, 40),
            "purple" to Triple(140, 50, 200),
            "cyan" to Triple(50, 200, 200),
            "pink" to Triple(230, 100, 160),
            "white" to Triple(240, 240, 240),
            "black" to Triple(15, 15, 15),
        )

        log("Running ${testImages.size} inferences...")
        val lines = withContext(Dispatchers.Default) {
            testImages.map { (name, rgb) ->
                val inputSize = inputHeight * inputWidth * inputChannels
                val input = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
                repeat(inputHeight * inputWidth) {
                    input.put(rgb.first.toByte())
                    input.put(rgb.second.toByte())
                    input.put(rgb.third.toByte())
                }
                input.rewind()

                val output = Array(1) { ByteArray(outputClasses) }
                decorator.run(input, output)
                "  $name"
            }
        }
        lines.forEach { log(it) }

        // Simulate user accepting the top result, links to the last inference automatically.
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
