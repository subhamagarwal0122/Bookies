package com.bookies.reader.ui.add

import com.bookies.reader.data.repo.OpenLibrary

/**
 * Everything the "add a paper book" flow decides, with no Compose and no Android in it.
 *
 * The screen itself is a dialog with three faces — type or scan an ISBN, confirm what came
 * back, or fill the details in by hand — and which face is showing is the only interesting
 * thing about it. Keeping that here means the transitions can be executed on this machine,
 * where neither Compose nor ML Kit can be compiled at all.
 */

/** Which face of the dialog is showing. */
internal sealed interface AddStep {

    /** Typing or scanning an ISBN. The entry point, and where a failed lookup returns to. */
    data object Entry : AddStep

    /** Open Library answered; the reader confirms it is the right book before it is saved. */
    data class Confirm(val meta: OpenLibrary.Metadata) : AddStep

    /**
     * Keying the book in by hand. [reason] says why we are here rather than on [Confirm] —
     * "no record of that ISBN" and "chose to skip the lookup" look identical otherwise, and
     * only one of them is worth retrying later.
     */
    data class Manual(val reason: String?) : AddStep

    /**
     * That ISBN is already on the shelf. A distinct step rather than a hint, because the
     * useful thing to do next is close the dialog, not correct the digits.
     */
    data class AlreadyHere(val title: String) : AddStep
}

/** The hand-entry fields, kept as strings so the dialog can hold half-typed input. */
internal data class ManualDraft(
    val title: String = "",
    val authors: String = "",
    val pageCount: String = "",
    val isbn: String? = null
)

/** A [ManualDraft] that passed [validate], shaped to hand straight to `PhysicalBooks.add`. */
internal data class NewPhysicalBook(
    val title: String,
    val authors: String,
    val isbn: String?,
    val pageCount: Int?
)

/** The whole dialog's state. */
internal data class AddBookState(
    val isbn: String = "",
    val step: AddStep = AddStep.Entry,
    /** A lookup or a save is running; the buttons are dead while it is. */
    val busy: Boolean = false,
    /** One line under the ISBN field: progress, a checksum complaint, or a failed lookup. */
    val hint: String? = null,
    val draft: ManualDraft = ManualDraft(),
    val draftError: String? = null
)

/** What a lookup did, as both the new face and the line underneath it. */
internal data class LookupOutcome(val step: AddStep, val hint: String?)

/** An ISBN-13 barcode is 13 digits; ISBN-10's check digit can be an X. */
internal const val MAX_ISBN_LENGTH = 13

/** Page counts above this are a typo, not a book — the longest printed books sit near 5,000. */
internal const val MAX_PAGE_COUNT = 20_000

/**
 * Keeps only what can appear in an ISBN, so the field cannot hold something no lookup
 * could ever accept.
 *
 * Separators are dropped as they are typed rather than tolerated and stripped later: an
 * ISBN is printed with hyphens, people type the hyphens, and a field that silently ignores
 * them is less confusing than one that counts them towards the thirteen.
 */
internal fun filterIsbnInput(raw: String): String =
    raw.uppercase()
        .filter { it.isDigit() || it == 'X' }
        .take(MAX_ISBN_LENGTH)

/** True once the field holds something worth spending a request on. */
internal fun canLookUp(isbn: String): Boolean = OpenLibrary.normaliseIsbn(isbn) != null

/**
 * The line under the field while the reader is still typing.
 *
 * A wrong check digit is called out at 10 and 13 characters — the two lengths where the
 * arithmetic can actually run — because that is exactly the mistake a mis-read barcode or
 * a transposed pair of digits makes, and finding it here costs nothing.
 */
internal fun isbnHint(isbn: String): String? = when {
    isbn.isEmpty() -> null
    canLookUp(isbn) -> null
    isbn.length == 10 || isbn.length == MAX_ISBN_LENGTH ->
        "That is not a valid ISBN — check for a mistyped digit"
    else -> "An ISBN is 10 or 13 characters"
}

/**
 * Turns a lookup into the next face of the dialog.
 *
 * [existingTitle] is the book already on the shelf with this ISBN, if there is one, and it
 * wins over everything else: re-scanning a book you already own is a far commoner mistake
 * than the lookup failing, and adding a silent duplicate is the one outcome with no undo.
 */
internal fun afterLookup(result: OpenLibrary.Result, existingTitle: String?): LookupOutcome {
    if (existingTitle != null) {
        return LookupOutcome(AddStep.AlreadyHere(existingTitle), null)
    }
    return when (result) {
        is OpenLibrary.Result.Found -> LookupOutcome(AddStep.Confirm(result.book), null)

        // A record that is simply missing is not an error and should not read like one:
        // Open Library is crowd-sourced, and the remedy is to type four fields.
        OpenLibrary.Result.NotFound -> LookupOutcome(
            AddStep.Manual("Open Library has no record of that ISBN."),
            null
        )

        // Both of these leave the reader on the field, because both are worth retrying —
        // one by fixing the digits, the other by waiting a moment.
        is OpenLibrary.Result.InvalidIsbn -> LookupOutcome(
            AddStep.Entry,
            "That is not a valid ISBN — check for a mistyped digit"
        )
        is OpenLibrary.Result.Error -> LookupOutcome(
            AddStep.Entry,
            "${result.reason}. Try again, or add the book by hand."
        )
    }
}

/**
 * Pre-fills hand entry from whatever the lookup did manage to return.
 *
 * Used when the reader rejects a match: the title is usually close to right even when the
 * edition is wrong, and starting from it beats starting from nothing.
 */
internal fun draftFrom(meta: OpenLibrary.Metadata): ManualDraft = ManualDraft(
    title = meta.title,
    authors = meta.authors,
    pageCount = meta.pageCount?.toString().orEmpty(),
    isbn = meta.isbn
)

/** Hand entry started from a scan that found nothing keeps the ISBN, which is still good. */
internal fun draftFor(isbn: String): ManualDraft =
    ManualDraft(isbn = OpenLibrary.normaliseIsbn(isbn))

/**
 * Validates hand entry, returning either the book to add or the one thing to fix.
 *
 * Only the title is required. A page count is genuinely optional — without one,
 * `PhysicalBooks.progressionFor` returns 0.0 and the notes sort to the front of the index,
 * which is visible and fixable; refusing to add the book at all is not.
 */
internal fun validate(draft: ManualDraft): kotlin.Result<NewPhysicalBook> {
    val title = draft.title.trim()
    if (title.isEmpty()) return kotlin.Result.failure(IllegalArgumentException("A title, at least"))

    val pages = draft.pageCount.trim()
    val pageCount = when {
        pages.isEmpty() -> null
        else -> pages.toIntOrNull()
            ?: return kotlin.Result.failure(IllegalArgumentException("Pages should be a number"))
    }
    if (pageCount != null && (pageCount <= 0 || pageCount > MAX_PAGE_COUNT)) {
        return kotlin.Result.failure(IllegalArgumentException("That page count cannot be right"))
    }

    return kotlin.Result.success(
        NewPhysicalBook(
            title = title,
            authors = joinAuthors(draft.authors),
            isbn = draft.isbn,
            pageCount = pageCount
        )
    )
}

/**
 * Normalises typed authors onto the "; " separator `BookEntity.authors` stores.
 *
 * People type commas. Accepting both and writing one means the shelf, the index header and
 * every export show authors the same way whether the book came from an OPF, from Open
 * Library or off a keyboard.
 */
internal fun joinAuthors(raw: String): String =
    raw.split(';', ',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString("; ")

/** The one-line summary under a proposed match, e.g. "Penguin · 1979 · 224 pages". */
internal fun metaSummary(meta: OpenLibrary.Metadata): String = buildList {
    meta.publisher?.let(::add)
    meta.publishDate?.let(::add)
    meta.pageCount?.let { add("$it pages") }
}.joinToString(" · ")
