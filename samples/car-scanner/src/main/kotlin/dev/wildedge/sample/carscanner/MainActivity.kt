package dev.wildedge.sample.carscanner

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.wildedge.sample.carscanner.databinding.ActivityMainBinding
import dev.wildedge.sample.carscanner.databinding.DialogDetailBinding
import dev.wildedge.sample.carscanner.databinding.DialogSettingsBinding
import dev.wildedge.sdk.FeedbackType
import dev.wildedge.sdk.WildEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: JobsAdapter
    private val jobs = mutableListOf<ScanJob>()

    private var imageCapture: ImageCapture? = null
    private var provider: RecognitionProvider = RecognitionProvider.OpenRouter

    private val prefs by lazy { getSharedPreferences("car_scanner_settings", MODE_PRIVATE) }

    private var uploadImageSize: Int = 512
        set(value) {
            field = value
            prefs.edit().putInt(PREF_UPLOAD_IMAGE_SIZE, value).apply()
        }
    private var compressionQuality: Float = 0.7f
        set(value) {
            field = value
            prefs.edit().putFloat(PREF_COMPRESSION_QUALITY, value).apply()
        }

    private var detailJobId: UUID? = null
    private var detailDialogBinding: DialogDetailBinding? = null

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showError("Camera access denied")
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.openInputStream(uri)?.use { stream ->
            analyzeSelectedPhoto(stream.readBytes())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uploadImageSize = prefs.getInt(PREF_UPLOAD_IMAGE_SIZE, uploadImageSize)
        compressionQuality = prefs.getFloat(PREF_COMPRESSION_QUALITY, compressionQuality)

        WildEdge.init(this) {
            dsn = getString(R.string.wildedge_dsn)
            debug = true
            enableAttachments = true
            enableCompression = true
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val providerParams = binding.providerGroup.layoutParams as android.view.ViewGroup.MarginLayoutParams
            providerParams.topMargin = bars.top + 8.dp
            binding.providerGroup.layoutParams = providerParams
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                bars.bottom + 16.dp,
            )
            insets
        }

        adapter = JobsAdapter(onClick = { job -> showDetail(job) })
        binding.jobsRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(
            this,
            4,
            androidx.recyclerview.widget.GridLayoutManager.VERTICAL,
            false,
        )
        binding.jobsRecyclerView.adapter = adapter

        binding.providerGroup.setOnCheckedChangeListener { _, checkedId ->
            provider = when (checkedId) {
                R.id.radioGemini -> RecognitionProvider.Gemini
                R.id.radioBoth -> RecognitionProvider.Both
                else -> RecognitionProvider.OpenRouter
            }
        }

        binding.scanButton.isClickable = true
        binding.scanButton.setOnClickListener { scan() }
        binding.settingsButton.setOnClickListener { showSettings() }
        binding.galleryButton.setOnClickListener { pickImage.launch("image/*") }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WildEdge.getInstance().close()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = binding.cameraPreview.surfaceProvider
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                imageCapture = null
                showError("Cannot access camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scan() {
        val capture = imageCapture
        if (capture == null) {
            showError("Camera not ready yet")
            return
        }
        val job = ScanJob(provider = provider)
        jobs.add(0, job)
        refreshJobs()

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    val bytes = imageProxyToJpeg(image)
                    image.close()
                    processCapturedImage(job, bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    updateJob(job.id, ScanJobStatus.Failed(exception.message ?: "Capture failed"))
                }
            },
        )
    }

    private fun imageProxyToJpeg(image: androidx.camera.core.ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, output)
        return output.toByteArray()
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun analyzeSelectedPhoto(bytes: ByteArray) {
        val job = ScanJob(provider = provider)
        jobs.add(0, job)
        refreshJobs()
        processCapturedImage(job, bytes)
    }

    private fun processCapturedImage(job: ScanJob, fullImageBytes: ByteArray) {
        lifecycleScope.launch {
            val (uploadData, meta, thumbnail) = withContext(Dispatchers.Default) {
                val full = BitmapFactory.decodeByteArray(fullImageBytes, 0, fullImageBytes.size)
                val thumb = makeThumbnail(full, 400)
                val (data, photoMeta) = makeUploadDataAndMeta(full)
                Triple(data, photoMeta, thumb)
            }

            job.imageBytes = uploadData
            job.thumbnail = thumbnail
            job.photoMetadata = meta
            refreshJobs()

            try {
                val results = withContext(Dispatchers.IO) { analyzeImage(uploadData, meta, job.provider) }
                updateJob(job.id, ScanJobStatus.Completed(results))
            } catch (e: Exception) {
                updateJob(job.id, ScanJobStatus.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    private fun makeThumbnail(bitmap: Bitmap, maxSize: Int): Bitmap {
        val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun makeUploadDataAndMeta(bitmap: Bitmap): Pair<ByteArray, PhotoMetadata> {
        val targetWidth = uploadImageSize.toFloat()
        val scale = if (bitmap.width > targetWidth) targetWidth / bitmap.width else 1f
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val output = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, (compressionQuality * 100).roundToInt(), output)
        val bytes = output.toByteArray()
        return bytes to PhotoMetadata(fileSizeBytes = bytes.size, width = width, height = height)
    }

    private fun analyzeImage(uploadData: ByteArray, meta: PhotoMetadata, provider: RecognitionProvider): List<ScanResult> = when (provider) {
        RecognitionProvider.OpenRouter -> listOf(analyzeWithOpenRouter(uploadData, meta))
        RecognitionProvider.Gemini -> listOf(analyzeWithGemini(uploadData, meta))
        RecognitionProvider.Both -> analyzeWithBoth(uploadData, meta)
    }

    private fun analyzeWithOpenRouter(uploadData: ByteArray, meta: PhotoMetadata, runId: String? = null): ScanResult {
        val result = OpenRouterClient.analyze(uploadData, CAR_PROMPT, runId)
        return toScanResult("OpenRouter", result, meta, OpenRouterClient.handle)
    }

    private fun analyzeWithGemini(uploadData: ByteArray, meta: PhotoMetadata, runId: String? = null): ScanResult {
        val result = GeminiClient.analyze(uploadData, CAR_PROMPT, runId)
        return toScanResult("Gemini", result, meta, GeminiClient.handle)
    }

    private fun analyzeWithBoth(uploadData: ByteArray, meta: PhotoMetadata): List<ScanResult> {
        val sharedRunId = "both-${UUID.randomUUID()}"
        val results = mutableListOf<ScanResult>()
        var firstError: Exception? = null

        try {
            results.add(analyzeWithOpenRouter(uploadData, meta, sharedRunId))
        } catch (e: Exception) {
            firstError = e
        }
        try {
            results.add(analyzeWithGemini(uploadData, meta, sharedRunId))
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }

        if (results.isEmpty() && firstError != null) throw firstError
        return results
    }

    private fun toScanResult(
        providerName: String,
        result: AnalysisResult,
        meta: PhotoMetadata,
        handle: dev.wildedge.sdk.ModelHandle,
    ): ScanResult {
        val inferenceDate = System.currentTimeMillis()
        return ScanResult(
            provider = providerName,
            info = result.carInfo,
            photo = meta,
            rawJson = result.rawJson,
            httpStats = result.httpStats,
            inferenceId = result.inferenceId,
            sendFeedback = { feedbackType ->
                val delayMs = (System.currentTimeMillis() - inferenceDate).toInt()
                handle.trackFeedback(feedbackType, relatedInferenceId = result.inferenceId, delayMs = delayMs)
            },
        )
    }

    private fun updateJob(id: UUID, status: ScanJobStatus) {
        runOnUiThread {
            val index = jobs.indexOfFirst { it.id == id }
            if (index >= 0) {
                jobs[index].status = status
                refreshJobs()
                val dialogBinding = detailDialogBinding
                if (dialogBinding != null && detailJobId == id) {
                    renderDetail(dialogBinding, jobs[index])
                }
            }
        }
    }

    private fun refreshJobs() {
        adapter.submit(jobs.toList())
    }

    private fun showError(message: String) {
        runOnUiThread {
            binding.errorText.text = message
            binding.errorText.visibility = android.view.View.VISIBLE
        }
    }

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val sizes = listOf(256, 512, 1024, 2048)
        dialogBinding.imageSizeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sizes.map { "${it}px" },
        )
        dialogBinding.imageSizeSpinner.setSelection(sizes.indexOf(uploadImageSize).coerceAtLeast(0))

        dialogBinding.compressionSeekBar.progress = ((compressionQuality - 0.1f) / 0.05f).roundToInt()
        fun updateLabels() {
            dialogBinding.compressionLabel.text = "JPEG quality: ${(compressionQuality * 100).roundToInt()}%"
        }
        updateLabels()
        dialogBinding.compressionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                compressionQuality = 0.1f + progress * 0.05f
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(dialogBinding.root)
            .setPositiveButton("Done") { _, _ ->
                uploadImageSize = sizes[dialogBinding.imageSizeSpinner.selectedItemPosition]
            }
            .show()
    }

    private fun showDetail(job: ScanJob) {
        val dialogBinding = DialogDetailBinding.inflate(layoutInflater)
        renderDetail(dialogBinding, job)

        detailJobId = job.id
        detailDialogBinding = dialogBinding

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton("Close", null)
            .setNeutralButton("Remove") { _, _ ->
                jobs.removeAll { it.id == job.id }
                refreshJobs()
            }
            .show()

        dialog.setOnDismissListener {
            detailJobId = null
            detailDialogBinding = null
        }
    }

    private fun renderDetail(dialogBinding: DialogDetailBinding, job: ScanJob) {
        val imageBytes = job.imageBytes
        if (imageBytes != null) {
            dialogBinding.detailImage.setImageBitmap(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size))
        }

        val dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        )
        dialogBinding.detailDate.text = dateFormat.format(java.util.Date(job.createdAt))

        val results = (job.status as? ScanJobStatus.Completed)?.results.orEmpty()
        val container = dialogBinding.resultsContainer
        container.removeAllViews()

        when (val status = job.status) {
            is ScanJobStatus.Scanning -> {
                addBodyText(container, "Analyzing image...")
            }
            is ScanJobStatus.Failed -> {
                addBodyText(container, "Error: ${status.message}", colorRes = "#D32F2F")
            }
            is ScanJobStatus.Completed -> {
                dialogBinding.boundingBoxOverlay.boxes = results.flatMap { result ->
                    result.info.candidates.orEmpty().mapNotNull { c ->
                        val bbox = c.bbox ?: return@mapNotNull null
                        val label = listOfNotNull(c.brand, c.model).joinToString(" ")
                        val prefixed = if (results.size > 1) "${result.provider}: $label" else label
                        bbox to prefixed
                    }
                }

                results.forEachIndexed { index, result ->
                    addResultSection(container, result, results.size > 1)
                    if (results.size > 1 && index < results.size - 1) {
                        addDivider(container)
                    }
                }
            }
        }

        val photo = results.firstOrNull()?.photo ?: job.photoMetadata
        if (photo != null) {
            dialogBinding.photoSection.removeAllViews()
            addSectionHeader(dialogBinding.photoSection, "Photo")
            addDetailRow(dialogBinding.photoSection, "Uploaded", photo.fileSizeFormatted)
            val dims = if (photo.width != null && photo.height != null) "${photo.width}x${photo.height}" else "—"
            addDetailRow(dialogBinding.photoSection, "Res", dims)
            dialogBinding.photoSection.visibility = android.view.View.VISIBLE
        } else {
            dialogBinding.photoSection.visibility = android.view.View.GONE
        }
    }

    private fun addSectionHeader(container: android.widget.LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
        view.textSize = 12f
        view.setTextColor(android.graphics.Color.parseColor("#888888"))
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = 12.dp
        view.layoutParams = params
        container.addView(view)
    }

    private fun addBodyText(container: android.widget.LinearLayout, text: String, colorRes: String? = null) {
        val view = TextView(this)
        view.text = text
        view.textSize = 14f
        if (colorRes != null) view.setTextColor(android.graphics.Color.parseColor(colorRes))
        container.addView(view)
    }

    private fun addDivider(container: android.widget.LinearLayout) {
        val view = View(this)
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            1.dp,
        )
        params.topMargin = 12.dp
        params.bottomMargin = 12.dp
        view.layoutParams = params
        view.setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"))
        container.addView(view)
    }

    private fun addDetailRow(container: android.widget.LinearLayout, label: String, value: String) {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        val rowParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rowParams.topMargin = 4.dp
        row.layoutParams = rowParams

        val labelView = TextView(this)
        labelView.text = label
        labelView.textSize = 12f
        labelView.setTextColor(android.graphics.Color.parseColor("#888888"))
        labelView.layoutParams = android.widget.LinearLayout.LayoutParams(80.dp, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(labelView)

        val valueView = TextView(this)
        valueView.text = value
        valueView.textSize = 14f
        valueView.setTypeface(valueView.typeface, android.graphics.Typeface.BOLD)
        row.addView(valueView)

        container.addView(row)
    }

    private fun addFeedbackRow(container: android.widget.LinearLayout, label: String, result: ScanResult) {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val rowParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        row.layoutParams = rowParams

        val labelView = TextView(this)
        labelView.text = label
        labelView.textSize = 12f
        labelView.setTextColor(android.graphics.Color.parseColor("#888888"))
        labelView.layoutParams = android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
        row.addView(labelView)

        val upButton = android.widget.Button(this)
        upButton.text = "👍"
        val downButton = android.widget.Button(this)
        downButton.text = "👎"
        upButton.setOnClickListener {
            result.sendFeedback(FeedbackType.ThumbsUp)
            upButton.isEnabled = false
            downButton.isEnabled = false
        }
        downButton.setOnClickListener {
            result.sendFeedback(FeedbackType.ThumbsDown)
            upButton.isEnabled = false
            downButton.isEnabled = false
        }
        row.addView(upButton)
        row.addView(downButton)

        container.addView(row)
    }

    private fun addJsonSection(container: android.widget.LinearLayout, json: String) {
        val header = TextView(this)
        header.text = "JSON Response ▸"
        header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
        header.textSize = 12f
        val headerParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        headerParams.topMargin = 12.dp
        header.layoutParams = headerParams
        container.addView(header)

        val body = TextView(this)
        body.text = json
        body.typeface = android.graphics.Typeface.MONOSPACE
        body.textSize = 11f
        body.setTextIsSelectable(true)
        body.visibility = android.view.View.GONE
        val bodyParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        bodyParams.topMargin = 4.dp
        body.layoutParams = bodyParams
        container.addView(body)

        header.setOnClickListener {
            val expanded = body.visibility == android.view.View.VISIBLE
            body.visibility = if (expanded) android.view.View.GONE else android.view.View.VISIBLE
            header.text = if (expanded) "JSON Response ▸" else "JSON Response ▾"
        }
    }

    private fun addResultSection(container: android.widget.LinearLayout, result: ScanResult, multipleResults: Boolean) {
        if (multipleResults) {
            val header = android.widget.LinearLayout(this)
            header.orientation = android.widget.LinearLayout.HORIZONTAL
            header.gravity = android.view.Gravity.CENTER_VERTICAL
            val providerLabel = TextView(this)
            providerLabel.text = result.provider
            providerLabel.setTypeface(providerLabel.typeface, android.graphics.Typeface.BOLD)
            providerLabel.textSize = 12f
            providerLabel.layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
            header.addView(providerLabel)
            container.addView(header)
            addFeedbackRow(header, "", result)
        } else {
            addFeedbackRow(container, "Helpful?", result)
        }

        val candidates = result.info.candidates.orEmpty()
        if (result.info.found && candidates.isNotEmpty()) {
            val ordinals = listOf("1st", "2nd", "3rd")
            candidates.forEachIndexed { idx, c ->
                val title = TextView(this)
                title.text = "${ordinals.getOrElse(idx) { "#${idx + 1}" }} - ${result.provider}"
                title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
                title.textSize = 12f
                val titleParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                titleParams.topMargin = 8.dp
                title.layoutParams = titleParams
                container.addView(title)

                c.brand?.let { addDetailRow(container, "Brand", it) }
                c.model?.let { addDetailRow(container, "Model", it) }
                c.color?.let { addDetailRow(container, "Color", it) }
                c.year?.let { addDetailRow(container, "Year", it) }
                c.confidence?.let { addDetailRow(container, "Confidence", "$it%") }
                c.bbox?.let { b ->
                    addDetailRow(
                        container,
                        "BBox",
                        String.format("(%.2f, %.2f) %.2fx%.2f", b.x, b.y, b.width, b.height),
                    )
                }
                if (idx < candidates.size - 1) addDivider(container)
            }
        } else {
            addBodyText(container, "No car detected")
        }

        addDivider(container)
        addJsonSection(container, result.rawJson)
        addDivider(container)

        addSectionHeader(container, "HTTP")
        addDetailRow(container, "Status", "${result.httpStats.statusCode}")
        addDetailRow(container, "Duration", "${result.httpStats.durationMs} ms")
        addDetailRow(container, "Response", formatBytes(result.httpStats.responseSize))
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREF_UPLOAD_IMAGE_SIZE = "upload_image_size"
        const val PREF_COMPRESSION_QUALITY = "compression_quality"
    }
}
