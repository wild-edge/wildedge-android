package dev.wildedge.sdk

import dev.wildedge.sdk.analysis.analyzeAudio
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

class AudioAnalysisTest {

    private val sampleRate = 16000

    private fun silence(samples: Int) = ShortArray(samples)

    private fun sineWave(samples: Int, freq: Float = 440f, amplitude: Short = 16000): ShortArray {
        return ShortArray(samples) { i ->
            (amplitude * sin(2 * PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    @Test fun durationMs() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate) // 1 second
        assertEquals(1000, meta.durationMs)
    }

    @Test fun durationMsStereo() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate * 2), sampleRate, channels = 2)
        assertEquals(1000, meta.durationMs)
    }

    @Test fun sampleRateAndChannelsPassedThrough() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate = 44100, channels = 2)
        assertEquals(44100, meta.sampleRate)
        assertEquals(2, meta.channels)
    }

    @Test fun silenceIsLowVolume() {
        val meta = WildEdge.analyzeAudio(silence(sampleRate), sampleRate)
        assertEquals(-96f, meta.volumeDb)
    }

    @Test fun sineWaveVolumeIsNegativeDb() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate)
        assertNotNull(meta.volumeDb)
        assertTrue("expected negative dBFS", meta.volumeDb!! < 0f)
        assertTrue("expected > -30 dBFS for loud sine", meta.volumeDb!! > -30f)
    }

    @Test fun clippingDetectedWhenSaturated() {
        val clipped = ShortArray(100) { Short.MAX_VALUE }
        val meta = WildEdge.analyzeAudio(clipped, sampleRate)
        assertTrue(meta.clippingDetected == true)
    }

    @Test fun noClippingForNormalSignal() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate, amplitude = 1000), sampleRate)
        assertFalse(meta.clippingDetected == true)
    }

    @Test fun speechRatioBetweenZeroAndOne() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate)
        val ratio = meta.speechRatio
        assertNotNull(ratio)
        assertTrue(ratio!! in 0f..1f)
    }

    @Test fun silenceHasLowSpeechRatio() {
        val meta = WildEdge.analyzeAudio(silence(sampleRate), sampleRate)
        val ratio = meta.speechRatio ?: 0f
        assertTrue("silence should have low speech ratio", ratio < 0.2f)
    }

    @Test fun snrPresentForSufficientFrames() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate)
        assertNotNull(meta.snrDb)
    }

    @Test fun streamingFlagPassedThrough() {
        val meta = WildEdge.analyzeAudio(sineWave(sampleRate), sampleRate, isStreaming = true)
        assertTrue(meta.isStreaming == true)
    }
}
