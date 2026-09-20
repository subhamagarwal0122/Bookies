package com.bookies.reader.epub

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Reads a Kindle `My Clippings.txt` into annotations.
 *
 * The file is the only export route off a Kindle that needs no account, no API and no
 * cable-free luck: it is a plain text log the device appends to, one entry per action,
 * separated by a line of `=`. That append-only design is also its one real hazard —
 * **extending a highlight writes a new entry rather than replacing the old one**, so a
 * passage the reader grew in three sips arrives as three overlapping entries. Importing
 * naively turns one passage into three annotations that all say nearly the same thing,
 * and no amount of later editing untangles which was meant. [dedupe] is the heart of
 * this file for that reason.
 *
 * [parse] is a pure `String -> ParseResult`: no file IO, no Android types, so the whole
 * thing is testable standalone and the caller owns reading the file off the SD card.
 *
 * A clipping carries no anchor — no spine item, no offsets, nothing but the quoted text.
 * The entities this produces therefore leave `href`/`cssSelector`/offsets null and rely
 * on `quote`, which is exactly the input `TextAnchoring.resolve` needs. Re-anchoring
 * against a real EPUB is deliberately NOT done here: it needs the book's chapter text,
 * which is the caller's to supply, and an import must succeed even when the matching
 * EPUB is not in the library at all.
 */
object KindleClippings {

    /** Kindle's own entry separator. */
    private const val SEPARATOR_CHAR = '='

    /**
     * Shortest text that may be collapsed into a longer one by substring containment.
     *
     * Containment is a strong signal on a sentence and a coincidence on a fragment:
     * "the whale" sits inside half of Moby-Dick. Below this length we would rather keep
     * two annotations than risk silently destroying a distinct one, which is the same
     * class of error as a mis-anchor and just as unrecoverable.
     */
    private const val MIN_COLLAPSIBLE_CHARS = 12

    /**
     * One entry, after parsing but before it becomes a row.
     *
     * [locationRaw] is preserved verbatim rather than reformatted from the parsed
     * numbers, because it is the provenance string and should read the way the Kindle
     * wrote it.
     */
    data class Clipping(
        val type: AnnotationType,
        val text: String,
        val note: String? = null,
        val locationStart: Int? = null,
        val locationEnd: Int? = null,
        val locationRaw: String? = null,
        /**
         * Parsed off the metadata line for completeness, and then deliberately never
         * persisted. See [toAnnotations].
         */
        val pageNumber: Int? = null,
        /** Epoch millis, or null when the line's locale defeated us. */
        val addedAt: Long? = null
    ) {
        /** Inclusive location span; a point clipping spans one location. */
        internal val start: Int? get() = locationStart
        internal val end: Int? get() = locationEnd ?: locationStart
    }

    /**
     * All the clippings for one book in one file.
     *
     * [maxLocation] is the largest location seen for this book *in this file*, which is
     * the only length signal the format offers.
     */
    data class Book(
        val title: String,
        val authors: String,
        val clippings: List<Clipping>,
        val maxLocation: Int?
    )

    data class ParseResult(
        val books: List<Book>,
        /** Entries the file contained, including the ones we dropped. */
        val entriesFound: Int,
        /** Dropped: clipping-limit notices, empty bodies, unreadable metadata lines. */
        val entriesSkipped: Int,
        /** Entries merged away by [dedupe] — worth surfacing, it can be a large number. */
        val entriesMerged: Int,
        /** Entries kept whose "Added on" line could not be parsed. */
        val undatedEntries: Int
    )

    // --- parsing -------------------------------------------------------------

    fun parse(source: String): ParseResult {
        // A UTF-8 BOM survives the decode as U+FEFF and would otherwise ride along in
        // the first book's title, making it a different book from every later import.
        val text = source.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

        var found = 0
        var skipped = 0
        var undated = 0
        // Insertion-ordered so the shelf sees books in the order the reader met them.
        val byBook = LinkedHashMap<String, MutableList<Clipping>>()
        val titles = LinkedHashMap<String, Pair<String, String>>()

        for (block in text.split("\n").splitOnSeparator()) {
            val lines = block.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            // A trailing separator with nothing after it is normal, not an error.
            if (lines.isEmpty()) continue
            found++

            val clipping = parseEntry(lines)
            if (clipping == null) {
                skipped++
                continue
            }
            if (clipping.addedAt == null) undated++

            val (title, authors) = parseTitleLine(lines[0])
            val key = title.lowercase(Locale.ROOT) + "\u0000" + authors.lowercase(Locale.ROOT)
            titles.getOrPut(key) { title to authors }
            byBook.getOrPut(key) { mutableListOf() } += clipping
        }

        var merged = 0
        val books = byBook.map { (key, clippings) ->
            val (title, authors) = titles.getValue(key)
            val collapsed = attachNotes(dedupe(clippings))
            merged += clippings.size - collapsed.size
            Book(
                title = title,
                authors = authors,
                clippings = collapsed.sortedWith(compareBy({ it.start ?: 0 }, { it.text })),
                maxLocation = clippings.mapNotNull { it.end }.maxOrNull()
            )
        }

        return ParseResult(books, found, skipped, merged, undated)
    }

    /** Splits the already-line-broken file on Kindle's `==========` rules. */
    private fun List<String>.splitOnSeparator(): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in this) {
            if (line.isSeparator()) {
                blocks += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        blocks += current
        return blocks
    }

    /**
     * Ten `=` is the documented rule, but files edited by hand or truncated by a sync
     * turn up with other counts, and nothing else in the format is a run of `=`.
     */
    private fun String.isSeparator(): Boolean {
        val t = trim()
        return t.length >= 5 && t.all { it == SEPARATOR_CHAR }
    }

    private fun parseEntry(lines: List<String>): Clipping? {
        if (lines.size < 2) return null
        val meta = lines[1].trim()
        val type = typeOf(meta) ?: return null

        val body = lines.drop(2)
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()

        // "<You have reached the clipping limit for this item>" — the Kindle telling us
        // the publisher capped exports. It is a notice, not a passage.
        if (body.contains("clipping limit", ignoreCase = true)) return null
        // A bookmark legitimately has no text; anything else without a body is noise.
        if (body.isEmpty() && type != AnnotationType.BOOKMARK) return null

        val parts = meta.split('|').map { it.trim() }
        val locationPart = parts.firstOrNull { LOCATION.containsMatchIn(it) }
        val location = locationPart?.let { LOCATION.find(it) }

        return Clipping(
            type = type,
            text = body,
            locationStart = location?.groupValues?.get(1)?.toIntOrNull(),
            locationEnd = location?.groupValues?.get(2)?.toIntOrNull(),
            locationRaw = locationPart,
            pageNumber = parts.firstNotNullOfOrNull { PAGE.find(it) }
                ?.groupValues?.get(1)?.toIntOrNull(),
            addedAt = parts.firstNotNullOfOrNull { part ->
                ADDED_ON.find(part)?.groupValues?.get(1)?.let(::parseDate)
            }
        )
    }

    /**
     * Classifies from the metadata line.
     *
     * Returns null for a line that looks like nothing we recognise — a truncated file,
     * or a body paragraph that ended up where a header should be. Guessing HIGHLIGHT
     * there would import garbage as a quote.
     */
    private fun typeOf(meta: String): AnnotationType? {
        val lower = meta.lowercase(Locale.ROOT)
        return when {
            lower.contains("bookmark") -> AnnotationType.BOOKMARK
            lower.contains("note") -> AnnotationType.NOTE
            lower.contains("highlight") -> AnnotationType.HIGHLIGHT
            // Localised firmware uses its own words, so fall back on the shape of the
            // line: a position and/or a timestamp is still recognisably a header.
            LOCATION.containsMatchIn(lower) || PAGE.containsMatchIn(lower) ->
                AnnotationType.HIGHLIGHT
            else -> null
        }
    }

    /**
     * `Moby-Dick (Herman Melville)` or `Moby-Dick - Herman Melville`.
     *
     * The author is left exactly as the Kindle wrote it, "Melville, Herman" included.
     * Reordering around the comma is right more often than not and wrong in a way the
     * reader cannot see (`Wright, Jr.`, `Lao, Tzu`), and the shelf shows the string.
     */
    fun parseTitleLine(line: String): Pair<String, String> {
        val trimmed = line.trim()
        if (trimmed.endsWith(")")) {
            val open = matchingOpenParen(trimmed)
            if (open > 0) {
                val title = trimmed.substring(0, open).trim()
                val authors = trimmed.substring(open + 1, trimmed.length - 1).trim()
                if (title.isNotEmpty() && authors.isNotEmpty()) return title to authors
            }
        }
        val dash = trimmed.lastIndexOf(" - ")
        if (dash > 0) {
            val title = trimmed.substring(0, dash).trim()
            val authors = trimmed.substring(dash + 3).trim()
            if (title.isNotEmpty() && authors.isNotEmpty()) return title to authors
        }
        return trimmed to ""
    }

    /** Index of the `(` closing at the end of the line, or -1. Titles contain parens too. */
    private fun matchingOpenParen(s: String): Int {
        var depth = 0
        for (i in s.indices.reversed()) {
            when (s[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    // --- deduplication -------------------------------------------------------

    /**
     * Collapses the revisions of one extended highlight into the single longest one.
     *
     * Two highlights merge only when **both** hold:
     *
     *  - their location spans overlap or touch (`b.start <= a.end + 1`), and
     *  - one's text contains the other's, whitespace-normalised.
     *
     * Either test alone is dangerous. Locations are coarse — roughly a screenful — so
     * two genuinely distinct highlights on the same page share a location constantly,
     * and position alone would fuse them. Containment alone would fuse a stock phrase
     * quoted in two chapters. Together they describe the thing we are actually looking
     * for: the same passage, written twice, once longer.
     *
     * Highlights missing a location are never merged, because the first test cannot be
     * evaluated and a "probably" is not good enough to delete someone's note with.
     *
     * Notes and bookmarks pass through untouched — a reader really does write two notes
     * at one spot, and they are not revisions of each other.
     */
    fun dedupe(clippings: List<Clipping>): List<Clipping> {
        val highlights = clippings.filter { it.type == AnnotationType.HIGHLIGHT }
        val others = clippings.filter { it.type != AnnotationType.HIGHLIGHT }
        if (highlights.size < 2) return clippings

        // Ascending by location so a group only ever grows rightwards; ties broken by
        // length so the longest revision tends to anchor its own group.
        val ordered = highlights.sortedWith(compareBy({ it.start ?: 0 }, { -it.text.length }))
        val groups = mutableListOf<MutableList<Clipping>>()

        for (clipping in ordered) {
            // Transitive by construction: a revision that touches any member joins the
            // whole group, so three sips of one passage end as one group and not two.
            val group = groups.firstOrNull { members -> members.any { isRevisionOf(it, clipping) } }
            if (group != null) group += clipping else groups += mutableListOf(clipping)
        }

        return others + groups.map(::collapse)
    }

    private fun isRevisionOf(a: Clipping, b: Clipping): Boolean {
        val aStart = a.start ?: return false
        val aEnd = a.end ?: return false
        val bStart = b.start ?: return false
        val bEnd = b.end ?: return false
        // Adjacent counts: extending a highlight over a location boundary leaves the
        // old entry ending exactly where the new one begins.
        if (bStart > aEnd + 1 || aStart > bEnd + 1) return false

        val x = TextAnchoring.normalizeWhitespace(a.text)
        val y = TextAnchoring.normalizeWhitespace(b.text)
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        if (shorter.length < MIN_COLLAPSIBLE_CHARS) return false
        return longer.contains(shorter)
    }

    /**
     * The surviving entry is the longest text, since that is the revision the reader
     * stopped at. The span is unioned so a note written against the earlier, shorter
     * revision still lands inside it, and the timestamp is the latest so the index
     * orders by when the reader last touched the passage.
     *
     * [Clipping.locationRaw] stays the winner's own string rather than being rewritten
     * from the unioned numbers: `sourceRef` is provenance, and a string the Kindle never
     * wrote is not provenance.
     */
    private fun collapse(group: List<Clipping>): Clipping {
        if (group.size == 1) return group.single()
        val winner = group.maxBy { TextAnchoring.normalizeWhitespace(it.text).length }
        return winner.copy(
            locationStart = group.mapNotNull { it.start }.minOrNull(),
            locationEnd = group.mapNotNull { it.end }.maxOrNull(),
            addedAt = group.mapNotNull { it.addedAt }.maxOrNull()
        )
    }

    /**
     * Folds each note into the highlight it was written against.
     *
     * A Kindle note made while text is selected gets a location inside that selection,
     * and the two are one annotation in every sense that matters — the note is what the
     * reader thought *about that passage*. Emitting them separately gives the index two
     * entries, one of which is a floating sentence with no passage attached.
     *
     * A note with no enclosing highlight stands on its own; readers do write notes on a
     * bare page, and dropping one would lose writing that only exists here.
     */
    private fun attachNotes(clippings: List<Clipping>): List<Clipping> {
        val highlights = clippings.filter { it.type == AnnotationType.HIGHLIGHT }.toMutableList()
        if (highlights.isEmpty()) return clippings

        val result = mutableListOf<Clipping>()
        for (clipping in clippings) {
            if (clipping.type != AnnotationType.NOTE) {
                if (clipping.type != AnnotationType.HIGHLIGHT) result += clipping
                continue
            }
            val at = clipping.start
            // Tightest enclosing highlight wins, so a note inside a short quote does not
            // get swallowed by a long one that merely overlaps it.
            val host = at?.let {
                highlights
                    .filter { h -> h.start != null && h.end != null && it in h.start!!..h.end!! }
                    .minByOrNull { h -> h.end!! - h.start!! }
            }
            if (host == null) {
                result += clipping
            } else {
                val index = highlights.indexOf(host)
                highlights[index] = host.copy(
                    note = listOfNotNull(host.note, clipping.text)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                )
            }
        }
        return result + highlights
    }

    // --- mapping to rows -----------------------------------------------------

    /**
     * Turns one parsed [Book] into rows for [bookId].
     *
     * [idFactory] exists so tests get deterministic ids; production takes the default,
     * which is a client-generated UUID like every other id in this app.
     */
    fun toAnnotations(
        book: Book,
        bookId: String,
        now: Long = System.currentTimeMillis(),
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): List<AnnotationEntity> = book.clippings.map { clipping ->
        AnnotationEntity(
            id = idFactory(),
            bookId = bookId,
            type = clipping.type,
            // A clipping is text and a position, nothing more. Leaving the selector and
            // offsets null is what marks these as re-anchorable: a later pass can run
            // TextAnchoring over the EPUB's chapters using `quote` and fill them in.
            href = null,
            cssSelector = null,
            startOffset = null,
            endOffset = null,
            progression = progressionFor(clipping, book.maxLocation),
            // Kindle prints a page number, and it is a lie for our purposes: the book is
            // reflowable and that number belongs to some print edition we do not have.
            // Invariant 4 reserves pageNumber for paper, where pages are real.
            pageNumber = null,
            quote = clipping.text,
            // A clipping is cut out of the book with no margins — there is genuinely no
            // surrounding text to record, and inventing some would poison re-anchoring.
            prefix = "",
            suffix = "",
            contextBefore = "",
            contextAfter = "",
            chapterTitle = null,
            note = clipping.note,
            locatorJson = null,
            source = AnnotationSource.KINDLE,
            sourceRef = clipping.locationRaw,
            // The Kindle's timestamp is the real creation time and worth keeping; when
            // its locale defeated the parser we fall back to now rather than to 0, which
            // would sort a decade of reading to the epoch.
            createdAt = clipping.addedAt ?: now,
            updatedAt = now
        )
    }

    /**
     * Position as a fraction of the furthest location seen for this book *in this file*.
     *
     * This is an estimate and nothing more: the file never states how long the book is,
     * so the last thing the reader highlighted necessarily reads as 100%. It is a lower
     * bound on the true length, which makes every progression an over-estimate that is
     * monotonic in the real order — good enough to sort the index correctly, which is
     * what progression is for. It sharpens on its own if these annotations are later
     * re-anchored against a real EPUB, at which point the reader's actual positions
     * replace these. Deliberately not dressed up as more precise than that.
     */
    fun progressionFor(clipping: Clipping, maxLocation: Int?): Double {
        val at = clipping.start ?: return 0.0
        if (maxLocation == null || maxLocation <= 0) return 0.0
        return (at.toDouble() / maxLocation).coerceIn(0.0, 1.0)
    }

    // --- metadata line scraps ------------------------------------------------

    private val LOCATION = Regex("""(?:location|loc\.?)\s+(\d+)(?:\s*-\s*(\d+))?""", RegexOption.IGNORE_CASE)
    private val PAGE = Regex("""page\s+(\d+)(?:\s*-\s*(\d+))?""", RegexOption.IGNORE_CASE)
    private val ADDED_ON = Regex("""added on\s+(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Kindle writes the timestamp in the device's locale and format, which varies by
     * firmware and region; these cover the English forms. Anything else returns null and
     * the clipping still imports — a date is metadata, the passage is the point.
     */
    private val DATE_FORMATS = listOf(
        "EEEE, d MMMM yyyy HH:mm:ss",
        "EEEE, MMMM d, yyyy h:mm:ss a",
        "EEEE, d MMMM yyyy h:mm:ss a",
        "EEEE, MMMM d, yyyy HH:mm:ss",
        "EEEE, d MMMM yy HH:mm:ss",
        "d MMMM yyyy HH:mm:ss",
        "MMMM d, yyyy h:mm:ss a"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        // Narrow no-break spaces show up around the meridiem on some firmware and are
        // not what any pattern expects.
        val cleaned = raw.trim().replace(' ', ' ').replace(' ', ' ')
        for (format in DATE_FORMATS) {
            runCatching {
                return LocalDateTime.parse(cleaned, format).atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }
}
