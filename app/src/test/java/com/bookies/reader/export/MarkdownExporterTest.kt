package com.bookies.reader.export

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown is the portability guarantee — it goes in every Drive bundle and is what
 * remains when Bookies is gone — so it had no business being the one untested part of
 * the export path.
 */
class MarkdownExporterTest {

    private fun book(
        title: String = "Wolf Hall",
        authors: String = "Hilary Mantel",
        identifier: String? = null,
        format: BookFormat = BookFormat.EPUB
    ) = BookEntity(
        id = "b1",
        format = format,
        title = title,
        authors = authors,
        coverPath = null,
        fileHash = "",
        opfIdentifier = identifier,
        localFilePath = null,
        addedAt = 0L,
        updatedAt = 0L
    )

    private fun annotation(
        quote: String = "He is a person of great ability.",
        note: String? = null,
        chapter: String? = "Chapter One",
        href: String? = "OEBPS/ch1.xhtml",
        progression: Double = 0.25,
        pageNumber: Int? = null,
        type: AnnotationType = AnnotationType.HIGHLIGHT
    ) = AnnotationEntity(
        id = "a1",
        bookId = "b1",
        type = type,
        href = href,
        cssSelector = null,
        startOffset = null,
        endOffset = null,
        progression = progression,
        pageNumber = pageNumber,
        quote = quote,
        prefix = "",
        suffix = "",
        contextBefore = "",
        contextAfter = "",
        chapterTitle = chapter,
        note = note,
        locatorJson = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test fun `header carries title, author and a count`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(), annotation()))
        assertTrue(out.startsWith("# Wolf Hall"))
        assertTrue(out.contains("*Hilary Mantel*"))
        assertTrue(out.contains("> 2 annotations"))
    }

    @Test fun `a blank author line is omitted rather than left empty`() {
        val out = MarkdownExporter.render(book(authors = ""), listOf(annotation()))
        assertFalse(out.lines().any { it == "**" })
    }

    @Test fun `identifier appears only when the book has one`() {
        assertFalse(MarkdownExporter.render(book(), listOf(annotation())).contains("Identifier"))
        val out = MarkdownExporter.render(book(identifier = "urn:isbn:9780007230181"), listOf(annotation()))
        assertTrue(out.contains("> Identifier: `urn:isbn:9780007230181`"))
    }

    @Test fun `consecutive annotations in one chapter get a single heading`() {
        val out = MarkdownExporter.render(
            book(),
            listOf(annotation(quote = "one"), annotation(quote = "two"))
        )
        assertEquals(1, out.lines().count { it == "## Chapter One" })
    }

    @Test fun `a new chapter opens a new heading`() {
        val out = MarkdownExporter.render(
            book(),
            listOf(annotation(chapter = "One"), annotation(chapter = "Two"), annotation(chapter = "One"))
        )
        // Three headings, not two: the exporter follows reading order rather than
        // grouping, so a chapter revisited later opens again.
        assertEquals(3, out.lines().count { it.startsWith("## ") })
    }

    @Test fun `href stands in for a missing chapter title`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(chapter = null)))
        assertTrue(out.contains("## OEBPS/ch1.xhtml"))
    }

    @Test fun `every line of a multi-line quote is blockquoted`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(quote = "first\nsecond\nthird")))
        assertTrue(out.contains("> first"))
        assertTrue(out.contains("> second"))
        assertTrue(out.contains("> third"))
    }

    @Test fun `a note follows the quote outside the blockquote`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(note = "Cromwell again.")))
        val lines = out.lines()
        assertTrue(lines.contains("Cromwell again."))
        assertFalse(lines.contains("> Cromwell again."))
    }

    @Test fun `a blank note is not rendered as an empty paragraph`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(note = "   ")))
        assertEquals(
            out,
            MarkdownExporter.render(book(), listOf(annotation(note = null)))
        )
    }

    @Test fun `a bookmark reports its position and quotes nothing`() {
        val out = MarkdownExporter.render(
            book(),
            listOf(annotation(type = AnnotationType.BOOKMARK, quote = "unused", progression = 0.5))
        )
        assertTrue(out.contains("*Bookmark*"))
        assertTrue(out.contains("50%"))
        assertFalse(out.contains("> unused"))
    }

    @Test fun `a page number is preferred over a progression when there is one`() {
        val out = MarkdownExporter.render(book(), listOf(annotation(pageNumber = 212, progression = 0.5)))
        assertTrue(out.contains("p. 212"))
        assertFalse(out.contains("50%"))
    }

    @Test fun `a physical book renders exactly the way a digital one does`() {
        // Invariant 4: nothing downstream of the page-to-progression conversion may
        // branch on format. Identical rows must produce identical Markdown.
        val rows = listOf(annotation(pageNumber = 212, progression = 0.5))
        assertEquals(
            MarkdownExporter.render(book(format = BookFormat.EPUB), rows),
            MarkdownExporter.render(book(format = BookFormat.PHYSICAL), rows)
        )
    }

    @Test fun `no annotations still yields a readable document`() {
        val out = MarkdownExporter.render(book(), emptyList())
        assertTrue(out.contains("# Wolf Hall"))
        assertTrue(out.contains("> 0 annotations"))
        assertFalse(out.contains("## "))
    }
}
