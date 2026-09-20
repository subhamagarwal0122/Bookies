package com.bookies.reader.ui.search

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import java.util.Locale

/**
 * Cross-library search, with every decision it makes kept out of Compose.
 *
 * Same reason as IndexModel: Compose cannot be compiled on this machine, so anything
 * left in the screen file is unverifiable until CI runs. Here it is covered by
 * SearchModelTest, which `kotlinc` runs locally.
 *
 * The load-bearing piece is [sanitiseFtsQuery]. `AnnotationDao.search` passes its
 * argument straight to FTS4 MATCH, and FTS4 does not merely return nothing for malformed
 * syntax — it raises SQLiteException. A lone `"`, a bare `AND`, a leading `-`, a stray
 * `*`: each of those is a crash on a keystroke, in a search box the user types into
 * character by character. So nothing reaches MATCH that this file did not build itself
 * out of known-safe pieces.
 */

// --- query parsing --------------------------------------------------------------------

/**
 * A piece of the user's query that we are willing to send to FTS4.
 *
 * Only two shapes exist, because these are the only two the standard and the enhanced
 * FTS3/4 query parsers agree on. Which of the two SQLite was compiled with is a property
 * of the device's system library, not of this app, so anything they disagree about is
 * off the table — see [sanitiseFtsQuery].
 */
internal sealed interface Term {
    data class Word(val text: String) : Term
    data class Phrase(val words: List<String>) : Term
}

/**
 * FTS3/4 operators. Case matters to SQLite — a lowercase `and` is an ordinary search
 * term — so only the uppercase spellings are dropped, and only when they stand alone.
 */
private val OPERATORS = setOf("AND", "OR", "NOT", "NEAR")

/** Enough terms to express any real query; a bound so a pasted page cannot build a monster. */
internal const val MAX_TERMS = 12

/** Characters a term may contain. Everything else in the input is a separator. */
private fun Char.isTermChar() = isLetterOrDigit() || this == '_'

/**
 * Splits raw input into terms, discarding anything that is syntax rather than content.
 *
 * Every character that is not a letter, digit or underscore is treated as a separator,
 * which is what disposes of the whole family of crashes at once: `*`, `-`, `:`, `(`, `^`
 * and friends never survive to be parsed by SQLite. Apostrophes become separators rather
 * than being deleted, because the simple tokenizer indexed "don't" as `don` + `t` and a
 * query for `dont` would match neither.
 */
internal fun parseTerms(raw: String): List<Term> {
    val terms = mutableListOf<Term>()
    val word = StringBuilder()
    val phrase = mutableListOf<String>()
    var inQuote = false

    fun endWord() {
        if (word.isEmpty()) return
        val w = word.toString()
        word.setLength(0)
        if (inQuote) phrase += w else if (w !in OPERATORS) terms += Term.Word(w)
    }

    for (c in raw) {
        when {
            c == '"' -> {
                endWord()
                if (inQuote) {
                    inQuote = false
                    // Even a single quoted word stays a Phrase: quoting is how an operator
                    // spelling gets searched for literally, and a Phrase is the one form
                    // that cannot be re-read as syntax.
                    if (phrase.isNotEmpty()) terms += Term.Phrase(phrase.toList())
                    phrase.clear()
                } else {
                    inQuote = true
                }
            }
            c.isTermChar() -> word.append(c)
            else -> endWord()
        }
    }
    endWord()

    // An unclosed quote is the normal state of a query halfway through being typed. Treat
    // what follows it as loose words rather than refusing to search.
    if (inQuote) for (w in phrase) if (w !in OPERATORS) terms += Term.Word(w)

    return terms
}

/**
 * Builds an FTS4 MATCH expression, or null when there is nothing left to search for.
 *
 * Null is a real answer, not a failure: "AND", `***` and `"` all sanitise to nothing, and
 * the caller must skip the query rather than send an empty string, which is itself a
 * syntax error.
 *
 * Terms are joined by nothing but whitespace, with no explicit operator anywhere. The two
 * FTS3/4 parsers read bare whitespace differently — enhanced syntax means AND, standard
 * syntax means OR — but both *accept* it, and no operator we could write is read the same
 * way by both. The difference in meaning is then taken back out in Kotlin: [buildResults]
 * keeps only annotations carrying every term, so the screen behaves identically whichever
 * SQLite the device shipped with.
 *
 * The final word gets a `*`, so results appear while a word is still being typed. Only a
 * word, never a phrase: a trailing `*` on a quoted phrase is not portable between the two
 * parsers, and a phrase is something the user has finished spelling anyway.
 */
internal fun sanitiseFtsQuery(raw: String): String? {
    val terms = parseTerms(raw).distinct().take(MAX_TERMS)
    if (terms.isEmpty()) return null

    return terms.mapIndexed { i, term ->
        when (term) {
            // Safe to quote without escaping: parseTerms only ever emits term characters,
            // so a phrase can contain no quote of its own.
            is Term.Phrase -> "\"" + term.words.joinToString(" ") + "\""
            is Term.Word -> if (i == terms.lastIndex) term.text + "*" else term.text
        }
    }.joinToString(" ")
}

/**
 * The same query as lowercase words, for scoring and highlighting in Kotlin.
 *
 * A phrase contributes its words individually. That is deliberately looser than the
 * phrase FTS4 enforced: this list is only ever used to filter and rank rows the database
 * already matched, and being looser than the database can only fail to remove a row —
 * never remove one that genuinely matched.
 */
internal fun matchTokens(raw: String): List<String> =
    parseTerms(raw).distinct().take(MAX_TERMS).flatMap { term ->
        when (term) {
            is Term.Word -> listOf(term.text)
            is Term.Phrase -> term.words
        }
    }.map { it.lowercase(Locale.ROOT) }.distinct()

// --- scoring --------------------------------------------------------------------------

// FTS4 has no bm25 — that ranking function is FTS5 only, and FTS5 is not guaranteed to be
// compiled into a device's SQLite (see Entities.kt). So relevance is computed here, from
// where in the annotation each term landed. The weights say the same thing the screen
// does: the passage the reader chose to keep outranks what they wrote about it, which
// outranks the text that merely happened to surround it.
private const val W_QUOTE = 6
private const val W_NOTE = 4
private const val W_CHAPTER = 3
private const val W_CONTEXT = 1

/** Every term present, not just one — the thing a reader is nearly always looking for. */
private const val COMPLETE_BONUS = 10

/**
 * Where [token] sits in [field]: 0 absent, 1 inside a word, 2 at a word boundary.
 *
 * The distinction earns its keep because the last term is sent as a prefix match, so
 * "lion" also brings back "rebellion". Both are genuine matches and neither is dropped,
 * but the one that starts a word is the one the reader meant.
 */
internal fun placement(field: String?, token: String): Int {
    if (field.isNullOrEmpty() || token.isEmpty()) return 0
    val hay = field.lowercase(Locale.ROOT)
    var best = 0
    var i = hay.indexOf(token)
    while (i >= 0) {
        if (i == 0 || !hay[i - 1].isTermChar()) return 2
        best = 1
        i = hay.indexOf(token, i + 1)
    }
    return best
}

/** The strongest field this token appears in, already multiplied by that field's weight. */
private fun tokenScore(annotation: AnnotationEntity, token: String): Int = maxOf(
    W_QUOTE * placement(annotation.quote, token),
    W_NOTE * placement(annotation.note, token),
    W_CHAPTER * placement(annotation.chapterTitle, token),
    W_CONTEXT * maxOf(
        placement(annotation.contextBefore, token),
        placement(annotation.contextAfter, token)
    )
)

/**
 * Relevance, or 0 when the annotation does not carry every term.
 *
 * Zero is how the OR/AND ambiguity described in [sanitiseFtsQuery] is settled: a row the
 * database returned for matching one term out of three scores nothing and never reaches
 * the screen.
 */
internal fun scoreOf(annotation: AnnotationEntity, tokens: List<String>): Int {
    if (tokens.isEmpty()) return 0
    var score = 0
    for (token in tokens) {
        val s = tokenScore(annotation, token)
        if (s == 0) return 0
        score += s
    }
    return score + COMPLETE_BONUS
}

// --- snippets -------------------------------------------------------------------------

/** A window of text plus the ranges within it the query matched, for the screen to mark. */
internal data class Snippet(val text: String, val matches: List<IntRange>)

/** Wide enough for the sentence around a hit, short enough that ten of them still scan. */
internal const val SNIPPET_WIDTH = 180

private const val ELLIPSIS = "…"

/**
 * The text to show for a hit: whichever field actually matched, windowed onto the match.
 *
 * Preference order is the same as the scoring weights, with one addition — a bookmark has
 * no quote at all, so the surrounding context is what is left to show. Something is
 * always returned, because a row that matched must never render as a blank line.
 */
internal fun snippetFor(annotation: AnnotationEntity, tokens: List<String>): Snippet {
    val candidates = listOf(
        annotation.quote,
        annotation.note,
        annotation.contextAfter,
        annotation.contextBefore
    ).filter { !it.isNullOrBlank() }.map { it!! }

    if (candidates.isEmpty()) return Snippet("", emptyList())

    val source = candidates.firstOrNull { field -> tokens.any { placement(field, it) > 0 } }
        ?: candidates.first()

    return window(source, tokens)
}

/** Cuts [text] down to [SNIPPET_WIDTH] around the first match, on word boundaries. */
private fun window(text: String, tokens: List<String>): Snippet {
    val clean = text.trim()
    if (clean.length <= SNIPPET_WIDTH) return Snippet(clean, rangesIn(clean, tokens))

    val hay = clean.lowercase(Locale.ROOT)
    val focus = tokens.map { hay.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0

    // A third of the window before the match, two thirds after: the reader needs enough
    // lead-in to place the sentence, and more of what follows it than precedes it.
    var start = (focus - SNIPPET_WIDTH / 3).coerceAtLeast(0)
    var end = (start + SNIPPET_WIDTH).coerceAtMost(clean.length)
    start = (end - SNIPPET_WIDTH).coerceAtLeast(0)

    // Never cut a word in half at either edge, but never eat the match either.
    if (start > 0) {
        val space = clean.indexOf(' ', start)
        if (space in start until focus) start = space + 1
    }
    if (end < clean.length) {
        val space = clean.lastIndexOf(' ', end)
        if (space > focus) end = space
    }

    val body = clean.substring(start, end).trim()
    val shown = buildString {
        if (start > 0) append(ELLIPSIS).append(' ')
        append(body)
        if (end < clean.length) append(' ').append(ELLIPSIS)
    }
    return Snippet(shown, rangesIn(shown, tokens))
}

/** Every occurrence of every token, merged so overlapping terms cannot double-mark. */
internal fun rangesIn(text: String, tokens: List<String>): List<IntRange> {
    val hay = text.lowercase(Locale.ROOT)
    val found = mutableListOf<IntRange>()
    for (token in tokens) {
        if (token.isEmpty()) continue
        var i = hay.indexOf(token)
        while (i >= 0) {
            found += i until (i + token.length)
            i = hay.indexOf(token, i + 1)
        }
    }
    if (found.isEmpty()) return emptyList()

    found.sortBy { it.first }
    val merged = mutableListOf<IntRange>()
    var current = found.first()
    for (range in found.drop(1)) {
        current = if (range.first <= current.last + 1) {
            current.first until (maxOf(current.last, range.last) + 1)
        } else {
            merged += current
            range
        }
    }
    merged += current
    return merged
}

// --- results --------------------------------------------------------------------------

internal data class SearchHit(
    val book: BookEntity,
    val annotation: AnnotationEntity,
    val snippet: Snippet,
    val score: Int
)

/** One book's hits. The book is carried, not just its title, so the row can show its state. */
internal data class BookGroup(val book: BookEntity, val hits: List<SearchHit>, val score: Int)

/**
 * Turns the DAO's rows into what the screen draws.
 *
 * Grouped by book rather than listed flat: a hit means nothing without knowing which book
 * it came from, and repeating the title on every row costs more than a header does.
 *
 * Books are ordered by their best hit, then by recency — relevance first, because the
 * reader searching across the whole library is looking for a passage, not browsing.
 * Within a book the order is progression, exactly as the index screen does it: that is
 * the one axis a paper book and an EPUB share, so a scanned page and a highlighted
 * paragraph interleave without anything here knowing which is which (invariant 4).
 *
 * An annotation whose book is missing from [books] is dropped. That is what keeps a
 * tombstoned book's annotations out of the results — the shelf query already filters
 * `deletedAt`, so absence from the map is exactly the condition we want.
 */
internal fun buildResults(
    raw: String,
    annotations: List<AnnotationEntity>,
    books: Map<String, BookEntity>
): List<BookGroup> {
    val tokens = matchTokens(raw)
    if (tokens.isEmpty()) return emptyList()

    val hits = annotations.mapNotNull { annotation ->
        val book = books[annotation.bookId] ?: return@mapNotNull null
        val score = scoreOf(annotation, tokens)
        if (score == 0) return@mapNotNull null
        SearchHit(book, annotation, snippetFor(annotation, tokens), score)
    }

    return hits.groupBy { it.book.id }
        .map { (_, group) ->
            BookGroup(
                book = group.first().book,
                hits = group.sortedWith(
                    compareBy({ it.annotation.progression }, { it.annotation.id })
                ),
                score = group.maxOf { it.score }
            )
        }
        .sortedWith(
            compareByDescending<BookGroup> { it.score }
                .thenByDescending { group -> group.hits.maxOf { it.annotation.updatedAt } }
                .thenBy { it.book.title.lowercase(Locale.ROOT) }
        )
}
