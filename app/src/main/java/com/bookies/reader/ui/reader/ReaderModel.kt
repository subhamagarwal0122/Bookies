package com.bookies.reader.ui.reader

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.model.Anchor
import com.bookies.reader.epub.TextAnchoring
import kotlin.math.max
import kotlin.math.min

/**
 * The reader's logic, deliberately kept apart from Compose, Android and Readium.
 *
 * Same reasoning as [com.bookies.reader.ui.index.IndexModel]: none of those three can be
 * compiled on this machine, so anything left inside `ReaderScreen` or `ReaderViewModel` is
 * unverifiable until CI runs. Everything here is plain JVM and covered by ReaderModelTest,
 * which `kotlinc` runs locally.
 *
 * Three things live here, and they are the three that can actually be wrong:
 *
 *  - [plainText], turning a spine item's XHTML into the flat text [TextAnchoring] expects
 *  - [passageAt], widening a narrow reader selection to its surrounding paragraphs
 *  - [ProgressThrottle], deciding when a page turn is worth a database write
 */

// --- selection widening ---------------------------------------------------------------

/**
 * A selection, widened and ready to become an [AnnotationEntity].
 *
 * [prefix]/[suffix] are the short disambiguators [Anchor] wants; [contextBefore]/
 * [contextAfter] are the whole surrounding paragraphs, which is what makes the annotation
 * readable years later with the EPUB long since archived to Drive.
 */
internal data class Passage(
    val quote: String,
    val prefix: String,
    val suffix: String,
    val contextBefore: String,
    val contextAfter: String
)

/** Roughly the window Readium itself gives us, and all an anchor needs to disambiguate. */
internal const val EDGE_CHARS = 32

/** Below this much leading context we reach back a further paragraph. */
internal const val MIN_CONTEXT_CHARS = 120

/** Ceiling per side. ~2 KB per annotation is the budget the archive model assumes. */
internal const val MAX_CONTEXT_CHARS = 700

/**
 * Flattens a spine item to text, preserving paragraph boundaries as blank lines.
 *
 * Blank lines are the only structure the rest of this file needs, and they are what lets
 * [passageAt] widen a selection to a paragraph rather than to an arbitrary character
 * count. Script and style bodies go first, or their contents would read as prose.
 */
internal fun plainText(xhtml: String): String {
    var s = xhtml
    s = SCRIPT_OR_STYLE.replace(s, " ")
    s = COMMENT.replace(s, " ")
    // Block ends become paragraph breaks before tags are stripped wholesale, otherwise
    // every chapter collapses into one run-on line and paragraph widening has nothing
    // to find.
    s = BLOCK_END.replace(s, "\n\n")
    s = LINE_BREAK.replace(s, "\n")
    // Inline tags are removed rather than replaced with a space: <em> sits inside words
    // as often as between them, and splitting "some<em>thing</em>" is the worse error.
    s = TAG.replace(s, "")
    s = decodeEntities(s)
    s = HORIZONTAL_SPACE.replace(s, " ")
    s = s.lines().joinToString("\n") { it.trim() }
    s = BLANK_LINES.replace(s, "\n\n")
    return s.trim()
}

private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
private val COMMENT = Regex("(?s)<!--.*?-->")
private val BLOCK_END = Regex(
    "(?i)</(p|div|section|article|aside|nav|header|footer|h[1-6]|li|ul|ol|dd|dt|dl|" +
        "blockquote|figure|figcaption|pre|table|tr|td|th)\\s*>"
)
private val LINE_BREAK = Regex("(?i)<(br|hr)\\b[^>]*>")
private val TAG = Regex("(?s)<[^>]*>")
private val HORIZONTAL_SPACE = Regex("[ \\t\\x0B\\f\\r\\u00A0]+")
private val BLANK_LINES = Regex("\\n{2,}")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]+);")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”"
)

/**
 * Only the entities that actually change what the text *says*.
 *
 * A missed entity is not cosmetic here: it lands in the quote, which is then what
 * [TextAnchoring] has to match against a future build of the book.
 */
private fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    var out = NUMERIC_ENTITY.replace(s) { m ->
        val radix = if (m.groupValues[1].isEmpty()) 10 else 16
        val code = m.groupValues[2].toIntOrNull(radix)
        if (code == null || code !in 1..0x10FFFF) m.value else String(Character.toChars(code))
    }
    for ((name, value) in NAMED_ENTITIES) out = out.replace("&$name;", value)
    return out
}

/**
 * The paragraph containing [range], as a character span of [text].
 *
 * A selection that crosses a paragraph boundary widens to cover both ends, which is why
 * this searches outward from each end of the range independently.
 */
internal fun paragraphSpan(text: String, range: IntRange): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val from = range.first.coerceIn(0, text.length - 1)
    val to = range.last.coerceIn(from, text.length - 1)
    val start = text.lastIndexOf(PARAGRAPH_BREAK, from).let { if (it < 0) 0 else it + 2 }
    val endBreak = text.indexOf(PARAGRAPH_BREAK, to)
    val end = if (endBreak < 0) text.length else endBreak
    return start until end
}

private const val PARAGRAPH_BREAK = "\n\n"

/**
 * Widens the selection at [range] to whole paragraphs and cuts it into a [Passage].
 *
 * Readium hands back a text window of a few dozen characters either side. That is enough
 * to re-find the quote and nowhere near enough to *read* it: once the EPUB has gone to
 * Drive the annotation is all that is left of the passage, and an excerpt that starts
 * mid-clause is not a record of anything. So we reach out to the paragraph, and to the
 * paragraph before it when that leaves too little in front of the quote — a quote that
 * opens its own paragraph would otherwise have no lead-in at all.
 */
internal fun passageAt(text: String, range: IntRange): Passage {
    if (text.isEmpty() || range.isEmpty()) return Passage("", "", "", "", "")

    val start = range.first.coerceIn(0, text.length - 1)
    val end = (range.last + 1).coerceIn(start, text.length)
    val quote = text.substring(start, end)

    val span = paragraphSpan(text, range)
    var spanStart = span.first
    val spanEnd = span.last + 1

    // Reach back a paragraph at a time until there is enough lead-in to make sense of.
    // Two hops is plenty; more and we are quoting the chapter rather than the passage.
    var hops = 0
    while (start - spanStart < MIN_CONTEXT_CHARS && spanStart > 0 && hops < 2) {
        val previousParagraphEnd = spanStart - PARAGRAPH_BREAK.length
        if (previousParagraphEnd <= 0) {
            spanStart = 0
            break
        }
        val previousStart = text.lastIndexOf(PARAGRAPH_BREAK, previousParagraphEnd - 1)
            .let { if (it < 0) 0 else it + PARAGRAPH_BREAK.length }
        if (previousStart >= spanStart) break
        spanStart = previousStart
        hops++
    }

    val before = text.substring(max(spanStart, start - MAX_CONTEXT_CHARS), start)
    val afterEnd = min(text.length, min(spanEnd, end + MAX_CONTEXT_CHARS))
    val after = if (afterEnd > end) text.substring(end, afterEnd) else ""

    return Passage(
        quote = quote,
        prefix = before.takeLast(EDGE_CHARS),
        suffix = after.take(EDGE_CHARS),
        contextBefore = before,
        contextAfter = after
    )
}

// --- re-anchoring ---------------------------------------------------------------------

/** The stored anchor of an annotation, as [TextAnchoring.resolve] wants it. */
internal fun anchorOf(annotation: AnnotationEntity): Anchor = Anchor(
    href = annotation.href.orEmpty(),
    cssSelector = annotation.cssSelector,
    startOffset = annotation.startOffset,
    endOffset = annotation.endOffset,
    quote = annotation.quote,
    prefix = annotation.prefix,
    suffix = annotation.suffix,
    progression = annotation.progression
)

/** One resource's worth of re-anchoring result, ranked so the best chapter wins. */
internal data class Reanchored(
    val href: String,
    val range: IntRange,
    val similarity: Double,
    /** Progress *within this spine item*, which is what a Readium Locator wants. */
    val progression: Double,
    val passage: Passage
)

/**
 * Re-finds [anchor] inside one spine item's text.
 *
 * Note what this deliberately does not return: a whole-book progression. It cannot know
 * one — it has a single chapter in hand. The value here is within-resource progress,
 * which is exactly what `Locator.Locations.progression` means, and it must never be
 * written back to [AnnotationEntity.progression], which is the whole-book figure.
 */
internal fun reanchorIn(href: String, chapterText: String, anchor: Anchor): Reanchored? {
    val match = TextAnchoring.resolve(chapterText, anchor) ?: return null
    return Reanchored(
        href = href,
        range = match.range,
        similarity = match.similarity,
        progression = progressionWithin(match.range, chapterText.length),
        passage = passageAt(chapterText, match.range)
    )
}

/** Where a range sits in its resource, 0.0–1.0, measured from the start of the quote. */
internal fun progressionWithin(range: IntRange, textLength: Int): Double =
    if (textLength <= 0) 0.0 else (range.first.toDouble() / textLength).coerceIn(0.0, 1.0)

/**
 * Picks the chapter a re-anchor should land in.
 *
 * Scanning the whole reading order can turn up the quote more than once — front matter
 * that reprints an epigraph, a chapter summary. Similarity decides, and a tie goes to the
 * candidate nearest the recorded whole-book progression, which is the only positional
 * information that survives a re-split spine.
 */
internal fun bestReanchor(candidates: List<Reanchored>, spineOrder: List<String>): Reanchored? {
    if (candidates.isEmpty()) return null
    val top = candidates.maxOf { it.similarity }
    val tied = candidates.filter { it.similarity >= top - 1e-9 }
    if (tied.size == 1) return tied.first()
    return tied.minByOrNull { spineOrder.indexOf(it.href).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

// --- position persistence -------------------------------------------------------------

/** How long a reader may turn pages before the database hears about it. */
internal const val PROGRESS_DEBOUNCE_MS = 3_000L

/**
 * Decides whether a reported position is worth a write.
 *
 * Readium emits a new locator on every page turn, and a book is a few hundred of those.
 * Writing each one puts the database — and, once a bundle is packed, Drive — in the path
 * of a gesture that should cost nothing. Two rules: never write the same place twice, and
 * otherwise no more than once every [PROGRESS_DEBOUNCE_MS].
 *
 * The second rule means the last few seconds of reading are always pending, so leaving the
 * reader must [force] a flush. Without that, closing the book loses exactly the position
 * the reader most wants back.
 */
internal class ProgressThrottle(private val intervalMs: Long = PROGRESS_DEBOUNCE_MS) {

    private var lastWritten: String? = null
    private var lastWrittenAt = Long.MIN_VALUE
    private var pending: String? = null

    /** True when [locatorJson] should be persisted now; records it as written if so. */
    fun shouldWrite(locatorJson: String?, now: Long, force: Boolean = false): Boolean {
        if (locatorJson == null) return false
        if (locatorJson == lastWritten) {
            pending = null
            return false
        }
        pending = locatorJson
        // Long.MIN_VALUE as the initial mark would overflow the subtraction, so the first
        // write is decided by having nothing written yet rather than by arithmetic.
        val due = lastWritten == null || now - lastWrittenAt >= intervalMs
        if (!force && !due) return false
        lastWritten = locatorJson
        lastWrittenAt = now
        pending = null
        return true
    }

    /** The position seen since the last write, if the throttle is holding one back. */
    fun pending(): String? = pending
}

// --- highlight colours ----------------------------------------------------------------

/**
 * The palette offered on selection.
 *
 * Stored as an ARGB Int in [AnnotationEntity.colorArgb] rather than as an enum name: the
 * colour is data the export and any future web client render directly, and a name would
 * make them all agree on a lookup table that does not exist. Alpha is deliberately low —
 * these are drawn *over* the text by the navigator.
 */
internal data class HighlightTint(val label: String, val argb: Int)

internal val HIGHLIGHT_TINTS = listOf(
    HighlightTint("Yellow", 0xFFF6C744.toInt()),
    HighlightTint("Green", 0xFF9BC78A.toInt()),
    HighlightTint("Blue", 0xFF8FB6D9.toInt()),
    HighlightTint("Pink", 0xFFE39FB0.toInt()),
    HighlightTint("Violet", 0xFFB7A2D6.toInt())
)

internal val DEFAULT_TINT = HIGHLIGHT_TINTS.first().argb
