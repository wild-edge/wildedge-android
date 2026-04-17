package dev.wildedge.sdk.analysis

import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.TextInputMeta

// Analyzes a text string and returns TextInputMeta.
// tokenizer: optional function to count tokens (e.g. your model's actual tokenizer).
//            Without it, tokenCount is estimated as wordCount / 0.75 (rough GPT heuristic).
fun WildEdge.Companion.analyzeText(
    text: String,
    promptType: String? = null,
    turnIndex: Int? = null,
    hasAttachments: Boolean? = null,
    tokenizer: ((String) -> Int)? = null,
): TextInputMeta {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val wordCount = words.size
    val tokenCount = tokenizer?.invoke(text) ?: (wordCount / 0.75).toInt()

    val language = detectLanguage(text)

    return TextInputMeta(
        charCount = text.length,
        wordCount = wordCount,
        tokenCount = tokenCount,
        language = language?.tag,
        languageConfidence = language?.confidence,
        containsCode = containsCode(text),
        promptType = promptType,
        turnIndex = turnIndex,
        hasAttachments = hasAttachments,
    )
}

private data class LanguageHint(val tag: String, val confidence: Float)

// Heuristic language detection via Unicode block distribution.
// Returns null when the script is ambiguous (e.g. mixed or Latin-only with no strong signal).
private fun detectLanguage(text: String): LanguageHint? {
    if (text.isBlank()) return null

    var cjk = 0; var arabic = 0; var cyrillic = 0; var devanagari = 0; var latin = 0

    for (ch in text) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3040'..'\u30FF' -> cjk++
            ch in '\u0600'..'\u06FF' -> arabic++
            ch in '\u0400'..'\u04FF' -> cyrillic++
            ch in '\u0900'..'\u097F' -> devanagari++
            ch in 'A'..'z' -> latin++
        }
    }

    val total = (cjk + arabic + cyrillic + devanagari + latin).takeIf { it > 0 } ?: return null
    val dominant = listOf(
        cjk to "zh", arabic to "ar", cyrillic to "ru",
        devanagari to "hi", latin to "en",
    ).maxByOrNull { it.first } ?: return null

    val confidence = dominant.first.toFloat() / total
    return if (confidence > 0.5f) LanguageHint(dominant.second, confidence) else null
}

private val codePatterns = listOf(
    Regex("""\bfun\s+\w+\s*\("""),          // Kotlin/Swift
    Regex("""\bdef\s+\w+\s*[\(:]"""),        // Python/Ruby
    Regex("""\bfunction\s+\w+\s*\("""),      // JS/TS
    Regex("""\bclass\s+\w+[\s\{:]"""),       // most languages
    Regex("""[{};]\s*\n"""),                 // C-family block structure
    Regex("""\bimport\s+[\w.]+"""),          // imports
    Regex("""->\s*\w+"""),                   // arrow types / lambdas
    Regex("""```[\w]*\n"""),                 // markdown code fences
)

private fun containsCode(text: String): Boolean =
    codePatterns.any { it.containsMatchIn(text) }
