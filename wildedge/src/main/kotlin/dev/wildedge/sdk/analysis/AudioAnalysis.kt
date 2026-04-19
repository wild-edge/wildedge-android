package dev.wildedge.sdk.analysis

import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.AudioInputMeta
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

private const val MS_PER_SECOND = 1000
private const val DB_COEFFICIENT = 20f
private const val SILENCE_DB = -96f
private const val FRAMES_PER_SECOND = 100
private const val SPEECH_ENERGY_THRESHOLD = 0.1f

/**
 * Analyzes a PCM audio buffer and returns [AudioInputMeta] for use with [dev.wildedge.sdk.ModelHandle.trackInference].
 *
 * @param buffer 16-bit PCM samples (e.g. from `AudioRecord`).
 * @param sampleRate Sample rate in Hz.
 * @param channels Number of channels; 1 = mono, 2 = stereo.
 * @param isStreaming Whether audio is being streamed rather than buffered.
 * @param source Origin label (e.g. "microphone", "file").
 */
fun WildEdge.Companion.analyzeAudio(
    buffer: ShortArray,
    sampleRate: Int,
    channels: Int = 1,
    isStreaming: Boolean = false,
    source: String? = null,
): AudioInputMeta {
    val durationMs = (buffer.size.toFloat() / (sampleRate * channels) * MS_PER_SECOND).toInt()

    val rms = sqrt(buffer.sumOf { (it.toFloat() * it.toFloat()).toDouble() } / buffer.size).toFloat()
    val volumeDb = if (rms > 0) DB_COEFFICIENT * log10(rms / Short.MAX_VALUE) else SILENCE_DB

    val clippingDetected = buffer.any { abs(it.toInt()) >= Short.MAX_VALUE - 1 }

    // SNR estimate: ratio of RMS of loudest 10% frames to quietest 10% frames.
    val frameSize = sampleRate / FRAMES_PER_SECOND
    val frameRms = buffer.toList()
        .chunked(frameSize)
        .map { frame -> sqrt(frame.sumOf { (it.toFloat() * it.toFloat()).toDouble() } / frame.size).toFloat() }
        .sorted()
    val snrDb = if (frameRms.size >= 10) {
        val noiseFloor = frameRms.take(frameRms.size / 10).average().toFloat()
        val signal = frameRms.takeLast(frameRms.size / 10).average().toFloat()
        if (noiseFloor > 0) DB_COEFFICIENT * log10(signal / noiseFloor) else null
    } else {
        null
    }

    // Speech ratio: fraction of frames above a simple energy threshold.
    val speechRatio = if (frameRms.isNotEmpty()) {
        val threshold = frameRms.max() * SPEECH_ENERGY_THRESHOLD
        frameRms.count { it > threshold }.toFloat() / frameRms.size
    } else {
        null
    }

    return AudioInputMeta(
        durationMs = durationMs,
        sampleRate = sampleRate,
        channels = channels,
        source = source,
        isStreaming = isStreaming,
        volumeDb = volumeDb,
        snrDb = snrDb,
        speechRatio = speechRatio,
        clippingDetected = clippingDetected,
    )
}
