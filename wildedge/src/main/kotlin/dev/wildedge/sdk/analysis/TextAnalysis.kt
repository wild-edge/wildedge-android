package dev.wildedge.sdk.analysis

import dev.wildedge.sdk.WildEdge
import dev.wildedge.sdk.events.TextInputMeta
import kotlin.math.roundToInt

private const val CHARS_PER_TOKEN = 4.0
private const val WORDS_PER_TOKEN_RATIO = 0.75
private const val LATIN_SCRIPT_THRESHOLD = 0.5f
private const val CONFIDENCE_THRESHOLD = 0.5f
private const val EXCLUSIVE_CHAR_SCORE = 4f
private const val STRONG_CHAR_SCORE = 1.5f
private const val STOP_WORD_SCORE = 2f
private const val MIN_TOTAL_SCORE = 4f
private const val MIN_WORD_LENGTH = 2

// Estimates BPE token count from character count.
// OpenAI rule of thumb: ~4 chars per token for common English text.
// Accumulate charCount across streaming chunks and call once at the end.
internal fun approximateBpeTokenCount(charCount: Int): Int =
    (charCount / CHARS_PER_TOKEN).roundToInt().coerceAtLeast(1)

/**
 * Analyzes a text string and returns [TextInputMeta] for use with [dev.wildedge.sdk.ModelHandle.trackInference].
 *
 * @param text Input text to analyze.
 * @param promptType Optional label for the prompt style (e.g. "instruction", "chat").
 * @param turnIndex Conversation turn index for multi-turn sessions.
 * @param hasAttachments Whether the input includes file or image attachments.
 * @param tokenizer Optional function that counts tokens. Without it, token count is estimated
 *   as `wordCount / 0.75` (rough GPT heuristic).
 */
fun WildEdge.Companion.analyzeText(
    text: String,
    promptType: String? = null,
    turnIndex: Int? = null,
    hasAttachments: Boolean? = null,
    tokenizer: ((String) -> Int)? = null,
): TextInputMeta {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val wordCount = words.size
    val tokenCount = tokenizer?.invoke(text) ?: (wordCount / WORDS_PER_TOKEN_RATIO).toInt()

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

    var cjk = 0
    var arabic = 0
    var cyrillic = 0
    var devanagari = 0
    var latin = 0

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

    if (latin.toFloat() / total > LATIN_SCRIPT_THRESHOLD) return detectLatinLanguage(text)

    val dominant = listOf(
        cjk to "zh", arabic to "ar", cyrillic to "ru", devanagari to "hi",
    ).maxByOrNull { it.first } ?: return null

    val confidence = dominant.first.toFloat() / total
    return if (confidence > CONFIDENCE_THRESHOLD) LanguageHint(dominant.second, confidence) else null
}

private data class LatinProfile(
    val tag: String,
    val exclusive: Set<Char>,
    val strong: Set<Char>,
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
    val words = text.lowercase().split(Regex("\\W+")).filter { it.length >= MIN_WORD_LENGTH }.toHashSet()

    val scores = latinProfiles.mapNotNull { profile ->
        var score = 0f
        for (ch in text) {
            if (ch in profile.exclusive) score += EXCLUSIVE_CHAR_SCORE
            if (ch in profile.strong) score += STRONG_CHAR_SCORE
        }
        score += profile.stopWords.count { it in words } * STOP_WORD_SCORE
        if (score > 0f) profile.tag to score else null
    }

    if (scores.isEmpty()) return null

    val best = scores.maxByOrNull { it.second }!!
    if (best.second < MIN_TOTAL_SCORE) return null

    val total = scores.sumOf { it.second.toDouble() }.toFloat()
    val confidence = best.second / total
    return if (confidence >= CONFIDENCE_THRESHOLD) LanguageHint(best.first, confidence) else null
}

private val codePatterns = listOf(
    Regex("""\bfun\s+\w+\s*\("""),
    Regex("""\bdef\s+\w+\s*[\(:]"""),
    Regex("""\bfunction\s+\w+\s*\("""),
    Regex("""\bclass\s+\w+[\s\{:]"""),
    Regex("""[{};]\s*\n"""),
    Regex("""\bimport\s+[\w.]+"""),
    Regex("""->\s*\w+"""),
    Regex("""```[\w]*\n"""),
)

private fun containsCode(text: String): Boolean =
    codePatterns.any { it.containsMatchIn(text) }
