package com.bookies.reader.epub

import com.bookies.reader.data.model.Anchor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Re-finds an annotation's text inside a chapter when the exact selector no longer works.
 *
 * This runs in three situations, all of which matter:
 *
 *  - restoring highlights into a re-downloaded (differently built) EPUB
 *  - recovering annotations whose book fell back to annotations-only storage
 *  - turning a Kindle highlight, which gives us nothing but the quoted text, into a
 *    real clickable locator
 *
 * The approach is deliberately not a generic diff: we exploit the fact that we stored
 * the quote plus its surrounding context, so we can locate candidates cheaply by exact
 * substring search and only fall back to edit distance when that fails.
 */
object TextAnchoring {

    /** Below this normalised similarity we would rather report failure than guess. */
    private const val MIN_SIMILARITY = 0.75

    /** Words used as probes when hunting for fuzzy candidates. */
    private const val PROBE_WORDS = 4

    data class Match(val range: IntRange, val similarity: Double)

    /**
     * Resolves [anchor] against [chapterText] (plain text, not markup).
     *
     * Returns the character range of the quote in [chapterText], or null if nothing
     * scored above [MIN_SIMILARITY].
     */
    fun resolve(chapterText: String, anchor: Anchor): Match? {
        if (anchor.quote.isBlank()) return null

        val doc = Normalized.of(chapterText)
        val quote = normalizeWhitespace(anchor.quote)
        if (quote.isEmpty()) return null

        exactWithContext(doc, quote, anchor)?.let { return it }
        bestExact(doc, quote, anchor)?.let { return it }
        return fuzzy(doc, quote, anchor)
    }

    /**
     * Best case: prefix + quote + suffix appears verbatim. Unambiguous by construction,
     * because the context is what disambiguates a quote that repeats.
     */
    private fun exactWithContext(doc: Normalized, quote: String, anchor: Anchor): Match? {
        val prefix = normalizeWhitespace(anchor.prefix)
        val suffix = normalizeWhitespace(anchor.suffix)
        if (prefix.isEmpty() && suffix.isEmpty()) return null

        val needle = prefix + quote + suffix
        val at = doc.text.indexOf(needle)
        if (at < 0) return null

        val start = at + prefix.length
        return Match(doc.toOriginalRange(start, start + quote.length), 1.0)
    }

    /**
     * The quote appears exactly, perhaps several times. Score each occurrence by how
     * well its neighbours match the stored context, breaking ties by nearness to where
     * the annotation used to sit.
     */
    private fun bestExact(doc: Normalized, quote: String, anchor: Anchor): Match? {
        val occurrences = buildList {
            var i = doc.text.indexOf(quote)
            while (i >= 0) {
                add(i)
                i = doc.text.indexOf(quote, i + 1)
            }
        }
        if (occurrences.isEmpty()) return null

        val expected = (anchor.progression * doc.text.length).toInt()
        val best = occurrences.maxByOrNull { at ->
            contextScore(doc.text, at, quote.length, anchor) -
                // Tiny tiebreaker only: never let position outweigh matching context.
                (abs(at - expected).toDouble() / doc.text.length) * 0.1
        }!!

        return Match(doc.toOriginalRange(best, best + quote.length), 1.0)
    }

    /**
     * The text has changed — different edition, re-encoded punctuation, fixed typo.
     *
     * Rather than scanning every offset (quadratic and far too slow on a long chapter),
     * we locate candidate starts with short verbatim probes taken from the quote and
     * only then pay for edit distance.
     *
     * Probes are drawn from the start, middle AND end of the quote. Probing only the
     * opening words fails exactly when an edition changed something early — a curly
     * apostrophe in the second word, "metre" becoming "meter" — which is precisely the
     * case this path exists to handle. Any one surviving probe is enough to locate the
     * passage, because each carries its own offset within the quote.
     */
    private fun fuzzy(doc: Normalized, quote: String, anchor: Anchor): Match? {
        var best: Match? = null

        for ((offsetInQuote, probe) in probes(quote)) {
            var found = doc.text.indexOf(probe, ignoreCase = true)
            var examined = 0
            while (found >= 0 && examined < 32) {
                // Back off by the probe's position within the quote to get the start.
                val start = (found - offsetInQuote).coerceIn(0, doc.text.length)
                best = better(best, score(doc, quote, start))
                if (best?.similarity == 1.0) return best
                found = doc.text.indexOf(probe, found + 1, ignoreCase = true)
                examined++
            }
        }
        if (best != null) return best

        // Every probe changed. Fall back to a band around the recorded progression,
        // sampled finely enough not to step over the passage.
        val expected = (anchor.progression * doc.text.length).toInt()
        val band = max(2_000, quote.length * 4)
        val step = max(1, quote.length / 8)
        var at = max(0, expected - band)
        val limit = min(doc.text.length, expected + band)
        while (at < limit) {
            best = better(best, score(doc, quote, at))
            at += step
        }
        return best
    }

    /** Verbatim slices of the quote, each tagged with its character offset within it. */
    private fun probes(quote: String): List<Pair<Int, String>> {
        val words = quote.split(' ').filter { it.isNotEmpty() }
        if (words.size <= PROBE_WORDS) return listOf(0 to quote)

        fun probeAt(firstWord: Int): Pair<Int, String> {
            val offset = words.take(firstWord).sumOf { it.length + 1 }
            return offset to words.subList(firstWord, firstWord + PROBE_WORDS).joinToString(" ")
        }

        val last = words.size - PROBE_WORDS
        return listOf(0, last / 2, last)
            .distinct()
            .filter { it in 0..last }
            .map(::probeAt)
            .filter { it.second.length >= 4 }
    }

    private fun score(doc: Normalized, quote: String, start: Int): Match? {
        val end = min(doc.text.length, start + quote.length)
        if (end <= start) return null
        val similarity = similarity(doc.text.substring(start, end), quote)
        return if (similarity >= MIN_SIMILARITY) {
            Match(doc.toOriginalRange(start, end), similarity)
        } else null
    }

    private fun better(current: Match?, candidate: Match?): Match? = when {
        candidate == null -> current
        current == null -> candidate
        candidate.similarity > current.similarity -> candidate
        else -> current
    }

    private fun contextScore(text: String, at: Int, quoteLength: Int, anchor: Anchor): Double {
        val prefix = normalizeWhitespace(anchor.prefix)
        val suffix = normalizeWhitespace(anchor.suffix)
        var score = 0.0
        var parts = 0

        if (prefix.isNotEmpty()) {
            val actual = text.substring(max(0, at - prefix.length), at)
            score += similarity(actual, prefix); parts++
        }
        if (suffix.isNotEmpty()) {
            val from = min(text.length, at + quoteLength)
            val actual = text.substring(from, min(text.length, from + suffix.length))
            score += similarity(actual, suffix); parts++
        }
        return if (parts == 0) 0.0 else score / parts
    }

    /** Normalised Levenshtein similarity in 0.0–1.0, with a cheap length guard. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val longer = max(a.length, b.length)
        // Strings of wildly different length cannot clear the threshold; skip the work.
        if (min(a.length, b.length).toDouble() / longer < MIN_SIMILARITY) return 0.0
        return (longer - levenshtein(a, b)).toDouble() / longer
    }

    /** Two-row Levenshtein: O(a*b) time, O(b) space. */
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    fun normalizeWhitespace(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /**
     * Whitespace-normalised text plus a map back to offsets in the original string.
     *
     * Necessary because EPUB markup leaves ragged whitespace that differs between
     * builds, but callers need ranges that address the real document.
     */
    private class Normalized(val text: String, private val toOriginal: IntArray) {

        fun toOriginalRange(start: Int, end: Int): IntRange {
            val s = toOriginal.getOrElse(start) { 0 }
            val e = toOriginal.getOrElse(end - 1) { toOriginal.lastOrNull() ?: 0 }
            return s..e
        }

        companion object {
            fun of(source: String): Normalized {
                val sb = StringBuilder(source.length)
                val map = IntArray(source.length + 1)
                var lastWasSpace = true // leading whitespace is dropped, as in trim()
                for (i in source.indices) {
                    val c = source[i]
                    if (c.isWhitespace()) {
                        if (!lastWasSpace) {
                            map[sb.length] = i
                            sb.append(' ')
                            lastWasSpace = true
                        }
                    } else {
                        map[sb.length] = i
                        sb.append(c)
                        lastWasSpace = false
                    }
                }
                while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                return Normalized(sb.toString(), map.copyOf(sb.length + 1))
            }
        }
    }
}
