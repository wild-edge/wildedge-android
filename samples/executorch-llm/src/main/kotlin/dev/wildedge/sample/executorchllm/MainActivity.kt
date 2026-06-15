package dev.wildedge.sample.executorchllm

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.wildedge.sample.executorchllm.databinding.ActivityMainBinding
import dev.wildedge.sdk.ModelInfo
import dev.wildedge.sdk.WildEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val wildEdge = WildEdge.getInstance()
    private var runner: LLMRunner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setInputEnabled(false)
        lifecycleScope.launch { prepareModel() }

        binding.btnGenerate.setOnClickListener { generate() }
    }

    private suspend fun prepareModel() {
        val modelFile = File(filesDir, MODEL_URL.substringAfterLast('/'))
        val tokenizerFile = File(filesDir, TOKENIZER_FILENAME)

        val handle = wildEdge.registerModel(
            "llama-3.2-1b-instruct-int4",
            ModelInfo(
                modelName = "Llama-3.2-1B-Instruct-QLORA_INT4_EO8",
                modelSource = "huggingface",
                modelFormat = "pte",
            ),
        )

        if (!modelFile.exists()) {
            setStatus("Downloading model...")
            val error = downloadFile(handle, MODEL_URL, modelFile) { downloaded, total ->
                if (total > 0) {
                    val pct = (downloaded * 100 / total).toInt()
                    setStatus("Downloading model... $pct%")
                }
            }
            if (error != null) {
                setStatus("Model download failed: $error")
                return
            }
        }

        if (!tokenizerFile.exists()) {
            setStatus("Downloading tokenizer...")
            val error = downloadFile(handle, TOKENIZER_URL, tokenizerFile) { _, _ -> }
            if (error != null) {
                setStatus("Tokenizer download failed: $error")
                return
            }
        }
        withContext(Dispatchers.IO) { patchHFTokenizerIfNeeded(tokenizerFile) }

        setStatus("Loading model...")
        val r = LLMRunner(modelFile.absolutePath, tokenizerFile.absolutePath)
        r.handle = handle

        val result = withContext(Dispatchers.IO) { runCatching { r.load() } }
        result.onSuccess { loadMs ->
            handle.trackLoad(durationMs = loadMs.toInt())
            runner = r
            setStatus("Ready")
            setInputEnabled(true)
        }.onFailure { e ->
            handle.trackError(errorCode = e.javaClass.simpleName, errorMessage = e.message)
            setStatus("Load failed: ${e.message}")
        }
    }

    private fun generate() {
        val r = runner ?: return
        val handle = r.handle ?: return
        val prompt = binding.etPrompt.text.toString().trim()
        if (prompt.isEmpty()) return

        setInputEnabled(false)
        binding.tvOutput.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                r.generate(
                    prompt = prompt,
                    onToken = { token ->
                        runOnUiThread {
                            binding.tvOutput.append(token)
                            binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                        }
                    },
                    onDone = { durationMs ->
                        handle.trackInference(
                            durationMs = durationMs,
                            inputModality = r.inputModality,
                            outputModality = r.outputModality,
                            generationConfig = r.generationConfig,
                        )
                        runOnUiThread { setInputEnabled(true) }
                    },
                )
            } catch (e: Exception) {
                handle.trackError(errorCode = e.javaClass.simpleName, errorMessage = e.message)
                runOnUiThread {
                    binding.tvOutput.append("\n[error: ${e.message}]")
                    setInputEnabled(true)
                }
            }
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    private fun setInputEnabled(enabled: Boolean) {
        runOnUiThread {
            binding.etPrompt.isEnabled = enabled
            binding.btnGenerate.isEnabled = enabled
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runner?.stop()
        runner?.unload()
        wildEdge.close()
    }
}
