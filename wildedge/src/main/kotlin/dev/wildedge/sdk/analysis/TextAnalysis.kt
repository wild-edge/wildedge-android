package dev.wildedge.sdk.analysis

import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.TextInputMeta
import kotlin.math.roundToInt

// Estimates BPE token count from a character count.
// OpenAI rule of thumb: ~4 chars per token for common English text.
// See: https://platform.openai.com/tokenizer
// Accumulate charCount across streaming chunks and call once at the end.
internal fun approximateBpeTokenCount(charCount: Int): Int =
    (charCount / 4.0).roundToInt().coerceAtLeast(1)

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

// Script-level detection first. When Latin dominates, delegates to detectLatinLanguage
// for finer-grained discrimination. Returns null when the signal is too weak to commit.
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

    if (latin.toFloat() / total > 0.5f) return detectLatinLanguage(text)

    val dominant = listOf(
        cjk to "zh", arabic to "ar", cyrillic to "ru", devanagari to "hi",
    ).maxByOrNull { it.first } ?: return null

    val confidence = dominant.first.toFloat() / total
    return if (confidence > 0.5f) LanguageHint(dominant.second, confidence) else null
}

private data class LatinProfile(
    val tag: String,
    val exclusive: Set<Char>,  // chars unique (or near-unique) to this language
    val strong: Set<Char>,     // chars strongly associated but occasionally shared
    val stopWords: Set<String>,
)

// Profiles ordered so that languages with exclusive chars are matched first.
// Stop words are chosen to be high-frequency AND discriminating across the set.
private val latinProfiles = listOf(
    LatinProfile(
        "de",
        exclusive = setOf('ß'),
        strong = setOf('ä', 'ö', 'ü', 'Ä', 'Ö', 'Ü'),
        stopWords = setOf("der", "die", "das", "und", "nicht", "eine", "aber", "oder", "auch", "wird"),
    ),
    LatinProfile(
        "es",
        exclusive = setOf('ñ', 'Ñ', '¡', '¿'),
        strong = emptySet(),
        stopWords = setOf("que", "los", "las", "con", "por", "una", "pero", "como", "todo", "este"),
    ),
    LatinProfile(
        "pt",
        exclusive = setOf('ã', 'õ', 'Ã', 'Õ'),
        strong = emptySet(),
        stopWords = setOf("não", "para", "mais", "mas", "seu", "sua", "também", "isso", "ele", "ela"),
    ),
    LatinProfile(
        "fr",
        exclusive = setOf('œ', 'æ', 'Œ', 'Æ'),
        strong = setOf('ç', 'Ç'),
        stopWords = setOf("les", "des", "dans", "avec", "sont", "vous", "nous", "mais", "leur", "cette"),
    ),
    LatinProfile(
        "it",
        exclusive = emptySet(),
        strong = emptySet(),
        stopWords = setOf("sono", "della", "degli", "questo", "quella", "anche", "loro", "siamo", "delle", "quello"),
    ),
    LatinProfile(
        "en",
        exclusive = emptySet(),
        strong = emptySet(),
        stopWords = setOf("the", "and", "that", "have", "they", "from", "will", "this", "with", "been"),
    ),
)

// Scores each Latin language profile against the text using:
//   exclusive chars  -> 4 pts each (strong signal, e.g. ß, ñ, ã)
//   strong chars     -> 1.5 pts each (e.g. umlaut vowels)
//   stop word match  -> 2 pts each
// Requires a minimum total score of 4 to avoid committing on a single weak signal.
private fun detectLatinLanguage(text: String): LanguageHint? {
    val words = text.lowercase().split(Regex("\\W+")).filter { it.length >= 2 }.toHashSet()

    val scores = latinProfiles.mapNotNull { profile ->
        var score = 0f
        for (ch in text) {
            if (ch in profile.exclusive) score += 4f
            if (ch in profile.strong) score += 1.5f
        }
        score += profile.stopWords.count { it in words } * 2f
        if (score > 0f) profile.tag to score else null
    }

    if (scores.isEmpty()) return null

    val best = scores.maxByOrNull { it.second }!!
    if (best.second < 4f) return null

    val total = scores.sumOf { it.second.toDouble() }.toFloat()
    val confidence = best.second / total
    return if (confidence >= 0.5f) LanguageHint(best.first, confidence) else null
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
