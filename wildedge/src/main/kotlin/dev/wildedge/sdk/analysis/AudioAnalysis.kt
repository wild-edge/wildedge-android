package dev.wildedge.sdk.analysis

import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.AudioInputMeta
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// Analyzes a PCM audio buffer and returns AudioInputMeta.
// buffer: 16-bit PCM samples (e.g. from AudioRecord)
// channels: 1 = mono, 2 = stereo
fun WildEdge.Companion.analyzeAudio(
    buffer: ShortArray,
    sampleRate: Int,
    channels: Int = 1,
    isStreaming: Boolean = false,
    source: String? = null,
): AudioInputMeta {
    val durationMs = (buffer.size.toFloat() / (sampleRate * channels) * 1000).toInt()

    val rms = sqrt(buffer.sumOf { (it.toFloat() * it.toFloat()).toDouble() } / buffer.size).toFloat()
    val volumeDb = if (rms > 0) 20f * log10(rms / Short.MAX_VALUE) else -96f

    val clippingDetected = buffer.any { abs(it.toInt()) >= Short.MAX_VALUE - 1 }

    // SNR estimate: ratio of RMS of loudest 10% frames to quietest 10% frames.
    val frameSize = sampleRate / 100 // 10ms frames
    val frameRms = buffer.toList()
        .chunked(frameSize)
        .map { frame -> sqrt(frame.sumOf { (it.toFloat() * it.toFloat()).toDouble() } / frame.size).toFloat() }
        .sorted()
    val snrDb = if (frameRms.size >= 10) {
        val noiseFloor = frameRms.take(frameRms.size / 10).average().toFloat()
        val signal = frameRms.takeLast(frameRms.size / 10).average().toFloat()
        if (noiseFloor > 0) 20f * log10(signal / noiseFloor) else null
    } else null

    // Speech ratio: fraction of frames above a simple energy threshold.
    val speechRatio = if (frameRms.isNotEmpty()) {
        val threshold = frameRms.max() * 0.1f
        frameRms.count { it > threshold }.toFloat() / frameRms.size
    } else null

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
