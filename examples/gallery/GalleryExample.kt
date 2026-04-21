package examples.gallery

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import dev.wildedge.sdk.Accelerator
import dev.wildedge.sdk.InputModality
import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.analysis.analyzeText
import dev.wildedge.sdk.integrations.decorate
import dev.wildedge.sdk.integrations.trackWith

// Assumes WildEdge.init() has already run (manifest meta-data or Application.onCreate()).
class GalleryExample {

    private val wildEdge = WildEdge.getInstance()

    private val config = EngineConfig(modelPath = "/path/to/gemma-3n_int4.bin")

    private val trackedEngine = run {
        val start = System.currentTimeMillis()
        val engine = Engine(config).also { it.initialize() }
        wildEdge.decorate(engine, config, loadDurationMs = (System.currentTimeMillis() - start).toInt(), modelVersion = "1.0", accelerator = Accelerator.GPU)
    }

    private val conversation = trackedEngine.createConversation()

    fun generate(userInput: String, images: List<Bitmap>, onToken: (String) -> Unit, onDone: () -> Unit) {
        val inputModality = if (images.isNotEmpty()) InputModality.Multimodal else InputModality.Text
        val inputMeta = WildEdge.analyzeText(userInput)

        conversation.sendMessageAsync(
            Contents.of(userInput),
            object : MessageCallback {
                override fun onMessage(message: Message) = onToken(message.toString())
                override fun onDone() = onDone()
                override fun onError(throwable: Throwable) = onDone()
            }.trackWith(trackedEngine.handle, inputMeta, inputModality),
        )
    }

    fun close() {
        conversation.close()
        trackedEngine.close()
    }
}
