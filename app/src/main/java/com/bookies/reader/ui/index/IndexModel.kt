package com.bookies.reader.ui.index

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The annotation index's logic, deliberately kept apart from its Compose layer.
 *
 * Everything the index screen decides — what an annotation's location reads as, what
 * counts as a Note, how the list is ordered and grouped — lives here, free of Compose.
 * That is not tidiness: Compose cannot be compiled on this machine, so logic left inside
 * the screen file is logic nothing can check until CI runs. Here it is covered by
 * IndexModelTest, which `kotlinc` runs locally like the anchoring suite.
 *
 * Invariant 4 is enforced in this file and nowhere else, which is what makes it testable.
 */

internal enum class IndexFilter(val label: String) {
    ALL("All"), HIGHLIGHTS("Highlights"), NOTES("Notes"), BOOKMARKS("Bookmarks")
}

internal val listDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

/** Shown when a chapter title and an href are both missing — a physical book has neither. */
internal const val UNPLACED = "Elsewhere in the book"

// --- pure helpers ---------------------------------------------------------------------
// Kept free of Compose so they can be reasoned about (and eventually tested) without an
// Android toolchain, which this project cannot run locally.

internal fun percent(progression: Double) = "${(progression * 100).roundToInt()}%"

/**
 * The location of an annotation, decided by whether it has a page number and nothing
 * else. Branching on BookFormat here would let the rest of the app tell a paper book
 * from an EPUB, which invariant 4 exists to prevent.
 */
internal fun locationLabel(annotation: AnnotationEntity): String =
    annotation.pageNumber?.let { "p. $it" } ?: percent(annotation.progression)

/** The book's own position — paper reports pages because paper genuinely has them. */
internal fun positionLabel(book: BookEntity): String {
    val pages = book.pageCount
    return if (pages != null && pages > 0) {
        "p. ${(book.progression.coerceIn(0.0, 1.0) * pages).roundToInt()} of $pages"
    } else {
        percent(book.progression)
    }
}

internal fun kindLabel(annotation: AnnotationEntity): String = when {
    annotation.type == AnnotationType.BOOKMARK -> "Bookmark"
    !annotation.note.isNullOrBlank() -> "Note"
    else -> "Highlight"
}

internal fun footerLine(annotation: AnnotationEntity): String = buildList {
    add(kindLabel(annotation))
    add(locationLabel(annotation))
    add(listDateFormat.format(Date(annotation.createdAt)))
    // Provenance is only worth the pixels when it is not the obvious one.
    if (annotation.source != AnnotationSource.ANDROID) {
        add(annotation.source.name.lowercase(Locale.US))
    }
}.joinToString(" · ")

internal fun matches(annotation: AnnotationEntity, needle: String): Boolean =
    annotation.quote.contains(needle, ignoreCase = true) ||
        annotation.note?.contains(needle, ignoreCase = true) == true ||
        annotation.chapterTitle?.contains(needle, ignoreCase = true) == true

internal fun passesFilter(annotation: AnnotationEntity, filter: IndexFilter): Boolean =
    when (filter) {
        IndexFilter.ALL -> true
        IndexFilter.HIGHLIGHTS -> annotation.type == AnnotationType.HIGHLIGHT
        // "Notes" is about carrying a note, not about the type: a highlight someone wrote
        // on is the thing they want to find, whatever it was created as.
        IndexFilter.NOTES -> !annotation.note.isNullOrBlank()
        IndexFilter.BOOKMARKS -> annotation.type == AnnotationType.BOOKMARK
    }

/**
 * Filter, then sort by progression, then group into chapters.
 *
 * The sort is on progression alone — not href, not createdAt. Progression is the single
 * axis both kinds of book share, so this is what lets a paper book's page-derived
 * positions interleave with an EPUB's without the list knowing the difference.
 */
internal fun groupForDisplay(
    annotations: List<AnnotationEntity>,
    query: String,
    filter: IndexFilter
): List<Pair<String, List<AnnotationEntity>>> {
    val needle = query.trim()
    return annotations
        .asSequence()
        .filter { passesFilter(it, filter) }
        .filter { needle.isEmpty() || matches(it, needle) }
        .sortedBy { it.progression }
        .groupBy { it.chapterTitle?.takeIf { t -> t.isNotBlank() } ?: it.href ?: UNPLACED }
        .toList()
}
