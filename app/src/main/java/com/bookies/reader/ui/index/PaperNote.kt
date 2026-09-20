package com.bookies.reader.ui.index

/**
 * Making an annotation on a paper book: everything that has to be decided, Compose-free.
 *
 * An EPUB annotation is made by selecting text, so the app knows the quote is exactly what
 * the book says. On paper there is no selection — the passage is typed, or photographed and
 * corrected — and the two things that can go wrong are the page number and the line breaks
 * the page width put in. Both are handled here, where they can be tested.
 */

/** What the dialog holds while it is being filled in; strings, because input is strings. */
internal data class PaperNoteDraft(
    val quote: String = "",
    val note: String = "",
    val page: String = ""
)

/** A validated draft, shaped to hand straight to `PhysicalBooks.annotate`. */
internal data class PaperNote(
    val quote: String,
    val note: String?,
    val pageNumber: Int?
)

/**
 * Validates a draft against the book's page count.
 *
 * [pageCount] is null when the book was added without one, in which case any positive page
 * number is accepted: refusing input because a field the reader chose to skip is missing
 * would punish them twice for the same omission.
 */
internal fun validatePaperNote(draft: PaperNoteDraft, pageCount: Int?): Result<PaperNote> {
    val quote = tidyQuote(draft.quote)
    val note = draft.note.trim()

    // A row with neither is not an annotation; it would render in the index as an empty
    // rail with a page number under it.
    if (quote.isEmpty() && note.isEmpty()) {
        return Result.failure(IllegalArgumentException("A passage or a note — one of the two"))
    }

    val page = draft.page.trim()
    val pageNumber = when {
        page.isEmpty() -> null
        else -> page.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("The page should be a number"))
    }
    if (pageNumber != null) {
        if (pageNumber < 1) {
            return Result.failure(IllegalArgumentException("Pages start at 1"))
        }
        if (pageCount != null && pageNumber > pageCount) {
            return Result.failure(IllegalArgumentException("This book has $pageCount pages"))
        }
    }

    return Result.success(
        PaperNote(
            quote = quote,
            note = note.ifEmpty { null },
            pageNumber = pageNumber
        )
    )
}

/**
 * Removes the line breaks the printed page put in, and keeps the ones the author did.
 *
 * A passage typed or recognised off paper arrives wrapped at whatever width the page was
 * set to, and those breaks mean nothing — left in, they would be stored, exported and
 * searched as part of the quote, and FTS would see "some-\nthing" as two words. A blank
 * line is different: that is a paragraph the writer chose, so it survives.
 */
internal fun tidyQuote(raw: String): String =
    raw.split(PARAGRAPH_BREAK)
        .map { paragraph -> paragraph.split(WHITESPACE).filter(String::isNotEmpty).joinToString(" ") }
        .filter(String::isNotEmpty)
        .joinToString("\n\n")

/** One or more blank lines: a paragraph the writer chose. */
private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n\\s*")

private val WHITESPACE = Regex("\\s+")
