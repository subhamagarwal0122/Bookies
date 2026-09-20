package com.bookies.reader.ui.index

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ann(
    id: String = "a", progression: Double = 0.5, pageNumber: Int? = null,
    type: AnnotationType = AnnotationType.HIGHLIGHT, note: String? = null,
    quote: String = "q", chapterTitle: String? = "Ch", href: String? = "c1.xhtml",
    source: AnnotationSource = AnnotationSource.ANDROID
) = AnnotationEntity(
    id = id, bookId = "b", type = type, href = href, cssSelector = null,
    startOffset = null, endOffset = null, progression = progression, pageNumber = pageNumber,
    quote = quote, prefix = "", suffix = "", contextBefore = "", contextAfter = "",
    chapterTitle = chapterTitle, note = note, locatorJson = null, source = source,
    createdAt = 0L, updatedAt = 0L
)

private fun book(pageCount: Int? = null, progression: Double = 0.5, format: BookFormat = BookFormat.EPUB) =
    BookEntity(
        id = "b", format = format, title = "T", authors = "A", coverPath = null,
        pageCount = pageCount, fileHash = "", opfIdentifier = null, localFilePath = null,
        progression = progression, addedAt = 0L, updatedAt = 0L
    )

class IndexModelTest {

    // --- invariant 4: location is decided by pageNumber, never by BookFormat ---

    @Test fun paperAnnotationShowsPage() =
        assertEquals("p. 84", locationLabel(ann(pageNumber = 84)))

    @Test fun digitalAnnotationShowsPercent() =
        assertEquals("33%", locationLabel(ann(progression = 0.33)))

    @Test fun pageNumberWinsEvenOnAnEpubBook() {
        // A PHYSICAL-format book is never consulted here; only the field matters.
        assertEquals("p. 12", locationLabel(ann(pageNumber = 12, progression = 0.9)))
    }

    @Test fun bookPositionUsesPagesWhenKnown() =
        assertEquals("p. 127 of 254", positionLabel(book(pageCount = 254, progression = 0.5)))

    @Test fun bookPositionFallsBackToPercent() =
        assertEquals("50%", positionLabel(book(pageCount = null, progression = 0.5)))

    @Test fun bookPositionIgnoresFormatFlag() {
        // A PHYSICAL book with no pageCount must still render, not crash or special-case.
        assertEquals("22%", positionLabel(book(pageCount = null, progression = 0.22, format = BookFormat.PHYSICAL)))
    }

    // --- kind label precedence ---

    @Test fun bookmarkWithNoteStillReadsBookmark() =
        assertEquals("Bookmark", kindLabel(ann(type = AnnotationType.BOOKMARK, note = "n")))

    @Test fun highlightWithNoteReadsNote() =
        assertEquals("Note", kindLabel(ann(note = "thought")))

    @Test fun blankNoteIsNotANote() =
        assertEquals("Highlight", kindLabel(ann(note = "   ")))

    // --- filters ---

    @Test fun notesFilterIsAboutCarryingANoteNotTheType() {
        assertTrue(passesFilter(ann(note = "x"), IndexFilter.NOTES))
        assertFalse(passesFilter(ann(note = null), IndexFilter.NOTES))
        assertTrue(passesFilter(ann(type = AnnotationType.BOOKMARK, note = "x"), IndexFilter.NOTES))
    }

    @Test fun filterAndFooterAgree() {
        // Whatever the Notes chip selects must be what the footer calls a Note.
        val a = ann(note = "x")
        assertEquals(passesFilter(a, IndexFilter.NOTES), kindLabel(a) == "Note")
    }

    // --- search ---

    @Test fun searchIsCaseInsensitiveAcrossFields() {
        assertTrue(matches(ann(quote = "Call me Ishmael"), "ISHMAEL"))
        assertTrue(matches(ann(note = "A thought"), "thought"))
        assertTrue(matches(ann(chapterTitle = "Loomings"), "loom"))
        assertFalse(matches(ann(), "absent"))
    }

    // --- ordering: the whole point of invariant 4 ---

    @Test fun paperAndDigitalInterleaveByProgression() {
        val paper = ann(id = "p", progression = 0.20, pageNumber = 70, chapterTitle = null, href = null)
        val epub1 = ann(id = "e1", progression = 0.10, chapterTitle = "One", href = "1.xhtml")
        val epub2 = ann(id = "e2", progression = 0.30, chapterTitle = "Two", href = "2.xhtml")
        val flat = groupForDisplay(listOf(epub2, paper, epub1), "", IndexFilter.ALL)
            .flatMap { it.second }.map { it.id }
        assertEquals(listOf("e1", "p", "e2"), flat)
    }

    @Test fun physicalAnnotationWithoutChapterOrHrefGetsFallbackGroup() {
        val groups = groupForDisplay(listOf(ann(chapterTitle = null, href = null)), "", IndexFilter.ALL)
        assertEquals(UNPLACED, groups.single().first)
    }

    @Test fun blankChapterTitleFallsBackRatherThanShowingBlank() {
        val groups = groupForDisplay(listOf(ann(chapterTitle = "  ", href = "c9.xhtml")), "", IndexFilter.ALL)
        assertEquals("c9.xhtml", groups.single().first)
    }

    // --- footer provenance ---

    @Test fun androidSourceIsNotShownButOcrIs() {
        assertFalse(footerLine(ann()).contains("android"))
        assertTrue(footerLine(ann(source = AnnotationSource.OCR)).contains("ocr"))
    }

    // --- a behaviour worth knowing about: non-contiguous chapters collapse ---

    @Test fun revisitedChapterCollapsesIntoItsFirstGroup() {
        val a = ann(id = "a", progression = 0.1, chapterTitle = "One")
        val b = ann(id = "b", progression = 0.2, chapterTitle = "Two")
        val c = ann(id = "c", progression = 0.3, chapterTitle = "One")
        val groups = groupForDisplay(listOf(a, b, c), "", IndexFilter.ALL)
        assertEquals(listOf("One", "Two"), groups.map { it.first })
        assertEquals(listOf("a", "c"), groups.first().second.map { it.id })
    }
}
