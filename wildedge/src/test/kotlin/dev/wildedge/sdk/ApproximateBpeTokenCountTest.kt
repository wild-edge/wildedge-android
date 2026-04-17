package dev.wildedge.sdk

import dev.wildedge.sdk.analysis.approximateBpeTokenCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproximateBpeTokenCountTest {

    @Test fun emptyInputReturnsOne() {
        assertEquals(1, approximateBpeTokenCount(0))
    }

    @Test fun exactlyFourCharsIsOneToken() {
        assertEquals(1, approximateBpeTokenCount(4))
    }

    @Test fun exactlyEightCharsIsTwoTokens() {
        assertEquals(2, approximateBpeTokenCount(8))
    }

    @Test fun roundsHalfUp() {
        // 6 chars / 4.0 = 1.5, rounds to 2
        assertEquals(2, approximateBpeTokenCount(6))
    }

    @Test fun typicalEnglishSentence() {
        // "The quick brown fox" = 19 chars -> 19/4 = 4.75 -> 5
        assertEquals(5, approximateBpeTokenCount(19))
    }

    @Test fun largerOutputScalesLinearly() {
        // 400 chars -> 100 tokens
        assertEquals(100, approximateBpeTokenCount(400))
    }

    @Test fun singleCharReturnsOne() {
        assertEquals(1, approximateBpeTokenCount(1))
    }

    @Test fun threeCharsRoundsToOne() {
        // 3 / 4.0 = 0.75 -> rounds to 1, coerceAtLeast ensures minimum 1
        assertTrue(approximateBpeTokenCount(3) >= 1)
    }
}
