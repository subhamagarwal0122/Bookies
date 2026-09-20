package com.bookies.reader.data.repo

import com.bookies.reader.data.db.AnnotationDao
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookDao
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.StorageState
import java.util.UUID

/**
 * Paper books on the same shelf as the EPUBs.
 *
 * The only thing that genuinely differs is how a position is expressed: paper has real
 * page numbers, EPUBs do not. Rather than branching everywhere downstream, a page number
 * is converted once, here, into the same 0.0–1.0 progression an EPUB annotation carries.
 * After that the index screen, the ordering index, FTS search and Markdown export cannot
 * tell the two kinds apart.
 */
class PhysicalBooks(
    private val books: BookDao,
    private val annotations: AnnotationDao
) {

    /**
     * Adds a paper book, typically after scanning its barcode and resolving the ISBN
     * against Open Library for title, authors, cover and page count.
     */
    suspend fun add(
        title: String,
        authors: String,
        isbn: String?,
        pageCount: Int?,
        coverPath: String?
    ): BookEntity {
        isbn?.let { books.byIsbn(it) }?.let { return it }

        val now = System.currentTimeMillis()
        val book = BookEntity(
            id = UUID.randomUUID().toString(),
            format = BookFormat.PHYSICAL,
            title = title,
            authors = authors,
            coverPath = coverPath,
            isbn = isbn,
            pageCount = pageCount,
            // A physical book has no file, so it has no hash and nothing to archive.
            fileHash = "",
            opfIdentifier = null,
            localFilePath = null,
            storageState = StorageState.LOCAL,
            addedAt = now,
            updatedAt = now
        )
        books.upsert(book)
        return book
    }

    /**
     * Records a passage copied from a paper page — typed, or photographed and recognised.
     *
     * [quote] is whatever the user confirmed after correcting the OCR, so it is treated
     * as authoritative. No anchor resolution happens for physical books: there is no
     * document to resolve against, and the quote itself is the annotation.
     */
    suspend fun annotate(
        book: BookEntity,
        quote: String,
        pageNumber: Int?,
        note: String? = null,
        chapterTitle: String? = null,
        source: AnnotationSource = AnnotationSource.TYPED
    ): AnnotationEntity {
        require(book.format == BookFormat.PHYSICAL) { "Use the reader's selection flow for EPUBs" }

        val now = System.currentTimeMillis()
        val annotation = AnnotationEntity(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            type = if (quote.isBlank()) AnnotationType.NOTE else AnnotationType.HIGHLIGHT,
            href = null,
            cssSelector = null,
            startOffset = null,
            endOffset = null,
            progression = progressionFor(pageNumber, book.pageCount),
            pageNumber = pageNumber,
            quote = quote,
            // No surrounding text to capture: what was transcribed is all there is.
            prefix = "",
            suffix = "",
            contextBefore = "",
            contextAfter = "",
            chapterTitle = chapterTitle,
            note = note,
            locatorJson = null,
            source = source,
            sourceRef = pageNumber?.let { "p. $it" },
            createdAt = now,
            updatedAt = now
        )
        annotations.upsert(annotation)
        return annotation
    }

    companion object {
        /**
         * Page number as a fraction of the book, for uniform ordering.
         *
         * Returns 0.0 when the page count is unknown, which sorts such annotations to
         * the front of the index rather than dropping them — visible and fixable, which
         * is better than silently misplaced.
         */
        fun progressionFor(pageNumber: Int?, pageCount: Int?): Double {
            if (pageNumber == null || pageCount == null || pageCount <= 0) return 0.0
            return (pageNumber.toDouble() / pageCount).coerceIn(0.0, 1.0)
        }
    }
}
