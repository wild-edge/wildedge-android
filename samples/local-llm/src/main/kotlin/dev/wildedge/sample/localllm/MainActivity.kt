package dev.wildedge.sample.localllm

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dev.wildedge.sample.localllm.databinding.ActivityMainBinding
import dev.wildedge.sdk.FeedbackType
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.WildEdgeClient
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.LiteRtEngineDecorator
import dev.wildedge.sdk.integrations.decorate
import dev.wildedge.sdk.integrations.trackWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wildEdge: WildEdgeClient

    private var engineDecorator: LiteRtEngineDecorator? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wildEdge = WildEdge.init(this) {
            dsn = BuildConfig.WILDEDGE_DSN
            debug = true
        }

        setInputEnabled(false)
        lifecycleScope.launch { prepareModel() }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    private suspend fun prepareModel() {
        // Preserve the original filename so the SDK can infer model ID and quantization from it.
        val modelFile = File(filesDir, MODEL_URL.substringAfterLast('/'))

        if (!modelFile.exists()) {
            // Register the model upfront so the download event carries the correct model ID.
            val handle = wildEdge.registerModel(
                modelFile.nameWithoutExtension,
                dev.wildedge.sdk.ModelInfo(
                    modelName = modelFile.nameWithoutExtension,
                    modelVersion = "1.0",
                    modelSource = "remote",
                    modelFormat = "litertlm",
                ),
            )
            setStatus("Downloading model...")
            val ok = downloadModel(
                handle = handle,
                url = MODEL_URL,
                dest = modelFile,
                hfToken = BuildConfig.HF_TOKEN,
                onProgress = { downloaded, total ->
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        val mb = downloaded / 1_048_576
                        setStatus("Downloading... $pct% (${mb} MB)")
                    }
                },
            )
            if (!ok) {
                setStatus("Download failed. Check your network connection.")
                return
            }
        }

        setStatus("Loading model...")
        val config = EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU())
        val loadStart = System.currentTimeMillis()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val engine = Engine(config)
                engine.initialize()
                val loadMs = (System.currentTimeMillis() - loadStart).toInt()
                // decorate(engine, config) infers model ID and quantization from the filename.
                wildEdge.decorate(engine, config, loadDurationMs = loadMs)
            }
        }

        result.onSuccess { decorator ->
            engineDecorator = decorator
            conversation = decorator.createConversation()
            setStatus(if (BuildConfig.WILDEDGE_DSN.isEmpty()) "noop mode, model ready" else "reporting enabled, model ready")
            setInputEnabled(true)
            binding.etPrompt.requestFocus()
        }.onFailure { e ->
            setStatus("Load failed: ${e.message}")
        }
    }

    private fun sendMessage() {
        val prompt = binding.etPrompt.text.toString().trim()
        if (prompt.isEmpty()) return
        val conv = conversation ?: return
        val handle = engineDecorator?.handle ?: return

        setInputEnabled(false)
        binding.etPrompt.setText("")
        appendChat("You: $prompt\n")
        appendChat("Model: ")

        val inputMeta = WildEdge.analyzeText(prompt)

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                runOnUiThread {
                    binding.tvChat.append(message.toString())
                    scrollToBottom()
                }
            }

            override fun onDone() {
                handle.trackFeedback(FeedbackType.Accepted)
                runOnUiThread {
                    binding.tvChat.append("\n\n")
                    scrollToBottom()
                    setInputEnabled(true)
                }
            }

            override fun onError(throwable: Throwable) {
                runOnUiThread {
                    binding.tvChat.append("[error: ${throwable.message}]\n\n")
                    scrollToBottom()
                    setInputEnabled(true)
                }
            }
        }.trackWith(handle, inputMeta)

        lifecycleScope.launch(Dispatchers.IO) {
            conv.sendMessageAsync(prompt, callback)
        }
    }

    private fun appendChat(text: String) {
        binding.tvChat.append(text)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    private fun setInputEnabled(enabled: Boolean) {
        runOnUiThread {
            binding.etPrompt.isEnabled = enabled
            binding.btnSend.isEnabled = enabled
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.close()
        engineDecorator?.close()
        wildEdge.close()
    }
}
