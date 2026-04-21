package dev.wildedge.sample.cloudllm

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import dev.wildedge.sample.cloudllm.databinding.ActivityMainBinding
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.registerGoogleAiModel
import dev.wildedge.sdk.integrations.trackWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MODEL_NAME = "gemini-2.0-flash-lite"

// Setup: add to local.properties
//   google.ai.api.key=AIza...   (free key from https://aistudio.google.com)
//   wildedge.dsn=https://...    (optional, omit for noop mode)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val wildEdge = WildEdge.getInstance()

    private lateinit var model: GenerativeModel
    private var generateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        model = GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = BuildConfig.GOOGLE_AI_API_KEY,
        )

        val handle = wildEdge.registerGoogleAiModel(
            modelId = MODEL_NAME,
            modelFamily = "gemini",
        )

        val ready = if (BuildConfig.GOOGLE_AI_API_KEY.isEmpty())
            "Add google.ai.api.key to local.properties"
        else if (getString(R.string.wildedge_dsn).isEmpty())
            "noop mode - add wildedge.dsn to local.properties to enable reporting"
        else
            "Ready"
        setStatus(ready)

        binding.btnGenerate.setOnClickListener {
            val destination = binding.etDestination.text.toString().trim()
            if (destination.isEmpty()) {
                binding.etDestination.error = "Enter a destination"
                return@setOnClickListener
            }
            val days = binding.etDays.text.toString().trim().toIntOrNull()?.coerceIn(1, 30) ?: 3

            generateJob?.cancel()
            binding.tvOutput.text = ""
            setInputEnabled(false)
            setStatus("Connecting...")
            hideKeyboard()

            val prompt = buildPrompt(destination, days)
            val inputMeta = WildEdge.analyzeText(prompt)

            val start = System.currentTimeMillis()
            var firstTokenMs: Long? = null
            var charCount = 0

            generateJob = lifecycleScope.launch {
                try {
                    model.generateContentStream(prompt)
                        .trackWith(handle, inputMeta = inputMeta)
                        .collect { response ->
                            val chunk = response.text.orEmpty()
                            if (chunk.isNotEmpty()) {
                                if (firstTokenMs == null) {
                                    firstTokenMs = System.currentTimeMillis() - start
                                    setStatus("First token: ${firstTokenMs}ms")
                                }
                                charCount += chunk.length
                                appendOutput(chunk)

                                if (charCount % 200 < chunk.length) {
                                    val elapsedMs = System.currentTimeMillis() - start
                                    val approxTok = charCount / 4
                                    if (elapsedMs > 0) {
                                        setStatus("Generating: ${approxTok * 1000 / elapsedMs} tok/s")
                                    }
                                }
                            }
                        }

                    val elapsedMs = System.currentTimeMillis() - start
                    val approxTok = charCount / 4
                    val secs = elapsedMs / 1000f
                    val tokPerSec = if (elapsedMs > 0) approxTok * 1000 / elapsedMs else 0
                    setStatus("Done: ~$approxTok tokens, ${String.format("%.1f", secs)}s, $tokPerSec tok/s")
                } catch (e: Exception) {
                    setStatus("Error: ${e.message}")
                } finally {
                    setInputEnabled(true)
                }
            }
        }
    }

    private fun buildPrompt(destination: String, days: Int): String {
        val dayLabel = if (days == 1) "1-day" else "$days-day"
        return """
            Write a $dayLabel travel itinerary for $destination.
            For each day include morning, afternoon, and evening sections.
            Include specific venue names, local food tips, and one practical note per day.
        """.trimIndent()
    }

    private fun appendOutput(text: String) {
        runOnUiThread {
            binding.tvOutput.append(text)
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    private fun setInputEnabled(enabled: Boolean) {
        runOnUiThread {
            binding.etDestination.isEnabled = enabled
            binding.etDays.isEnabled = enabled
            binding.btnGenerate.isEnabled = enabled
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        generateJob?.cancel()
        wildEdge.close()
    }
}
