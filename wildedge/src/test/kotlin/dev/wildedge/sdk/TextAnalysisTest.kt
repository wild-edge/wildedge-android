package dev.wildedge.sdk

import dev.wildedge.sdk.analysis.analyzeText
import org.junit.Assert.*
import org.junit.Test

class TextAnalysisTest {

    @Test fun charAndWordCounts() {
        val meta = WildEdge.analyzeText("hello world foo")
        assertEquals(15, meta.charCount)
        assertEquals(3, meta.wordCount)
    }

    @Test fun tokenCountFallbackEstimate() {
        val meta = WildEdge.analyzeText("one two three four")
        // fallback: wordCount / 0.75 = 4 / 0.75 = 5
        assertEquals(5, meta.tokenCount)
    }

    @Test fun tokenCountFromCustomTokenizer() {
        val meta = WildEdge.analyzeText("one two three", tokenizer = { 42 })
        assertEquals(42, meta.tokenCount)
    }

    @Test fun detectsCode() {
        assertTrue(WildEdge.analyzeText("fun main() { println() }").containsCode == true)
        assertTrue(WildEdge.analyzeText("def foo(x):").containsCode == true)
        assertTrue(WildEdge.analyzeText("import android.os.Bundle").containsCode == true)
        assertFalse(WildEdge.analyzeText("hello how are you today").containsCode == true)
    }

    @Test fun detectsCodeFenceMarkdown() {
        assertTrue(WildEdge.analyzeText("```kotlin\nval x = 1\n```").containsCode == true)
    }

    @Test fun detectsCyrillicLanguage() {
        val meta = WildEdge.analyzeText("Привет мир это тест")
        assertEquals("ru", meta.language)
        assertNotNull(meta.languageConfidence)
        assertTrue(meta.languageConfidence!! > 0.5f)
    }

    @Test fun detectsArabicLanguage() {
        val meta = WildEdge.analyzeText("مرحبا بالعالم هذا اختبار")
        assertEquals("ar", meta.language)
    }

    @Test fun detectsEnglish() {
        val meta = WildEdge.analyzeText("the quick brown fox will have been running with them")
        assertEquals("en", meta.language)
    }

    @Test fun detectsGermanFromEszett() {
        val meta = WildEdge.analyzeText("Die Straße ist nicht weit von hier")
        assertEquals("de", meta.language)
    }

    @Test fun detectsGermanFromUmlauts() {
        val meta = WildEdge.analyzeText("Schön, aber ich möchte das auch wissen")
        assertEquals("de", meta.language)
    }

    @Test fun detectsSpanishFromEnye() {
        val meta = WildEdge.analyzeText("El niño no habla español en casa")
        assertEquals("es", meta.language)
    }

    @Test fun detectsSpanishFromInvertedMarks() {
        val meta = WildEdge.analyzeText("¿Cómo estás? ¡Muy bien, gracias!")
        assertEquals("es", meta.language)
    }

    @Test fun detectsPortuguese() {
        val meta = WildEdge.analyzeText("Não é possível fazer isso também agora")
        assertEquals("pt", meta.language)
    }

    @Test fun detectsFrench() {
        val meta = WildEdge.analyzeText("Les chats sont dans la maison avec vous")
        assertEquals("fr", meta.language)
    }

    @Test fun ambiguousLatinReturnsNull() {
        // No distinctive chars, no stop word matches
        val meta = WildEdge.analyzeText("hello world")
        assertNull(meta.language)
    }

    @Test fun weakSignalBelowThresholdReturnsNull() {
        // Single stop word match scores below the minimum threshold
        val meta = WildEdge.analyzeText("bonjour")
        assertNull(meta.language)
    }

    @Test fun promptTypeAndTurnIndexPassedThrough() {
        val meta = WildEdge.analyzeText("hi", promptType = "chat", turnIndex = 3)
        assertEquals("chat", meta.promptType)
        assertEquals(3, meta.turnIndex)
    }

    @Test fun emptyTextReturnsZeroCounts() {
        val meta = WildEdge.analyzeText("")
        assertEquals(0, meta.charCount)
        assertEquals(0, meta.wordCount)
    }
}
