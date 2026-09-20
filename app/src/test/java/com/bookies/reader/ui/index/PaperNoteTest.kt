package com.bookies.reader.ui.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperNoteTest {

    // --- what survives from a typed or recognised passage --------------------------------

    @Test
    fun `line breaks from the page width are removed`() {
        val typed = "Call me Ishmael. Some years ago—never\nmind how long precisely—having\nlittle money"
        assertEquals(
            "Call me Ishmael. Some years ago—never mind how long precisely—having little money",
            tidyQuote(typed)
        )
    }

    @Test
    fun `a word split across a line does not become two`() {
        // Not hyphen-rejoining — that would need a dictionary — but the break itself goes,
        // which is what stops FTS indexing "some" and "thing" as separate tokens.
        assertEquals("some thing", tidyQuote("some\nthing"))
    }

    @Test
    fun `a blank line is a paragraph the writer chose, and stays`() {
        assertEquals("One line.\n\nAnother.", tidyQuote("One line.\n\nAnother."))
    }

    @Test
    fun `several blank lines collapse to one paragraph break`() {
        assertEquals("One.\n\nTwo.", tidyQuote("One.\n\n\n\nTwo."))
    }

    @Test
    fun `runs of spaces and tabs collapse`() {
        assertEquals("a b c", tidyQuote("a   b\t\tc"))
    }

    @Test
    fun `leading and trailing whitespace goes`() {
        assertEquals("Hello", tidyQuote("\n  Hello  \n"))
    }

    @Test
    fun `whitespace alone tidies to nothing`() {
        assertEquals("", tidyQuote(" \n \t \n "))
    }

    // --- validation ----------------------------------------------------------------------

    @Test
    fun `a passage with a page number is saved as typed, tidied`() {
        val draft = PaperNoteDraft(quote = "The past is a\nforeign country.", page = "1")
        val note = validatePaperNote(draft, pageCount = 320).getOrThrow()
        assertEquals("The past is a foreign country.", note.quote)
        assertEquals(1, note.pageNumber)
        assertNull(note.note)
    }

    @Test
    fun `a note with no passage is allowed`() {
        val note = validatePaperNote(PaperNoteDraft(note = "Compare with ch. 4"), 320).getOrThrow()
        assertEquals("", note.quote)
        assertEquals("Compare with ch. 4", note.note)
    }

    @Test
    fun `neither a passage nor a note is refused`() {
        val error = validatePaperNote(PaperNoteDraft(page = "12"), 320).exceptionOrNull()
        assertEquals("A passage or a note — one of the two", error?.message)
    }

    @Test
    fun `whitespace in both fields counts as neither`() {
        val draft = PaperNoteDraft(quote = "  \n ", note = "   ")
        assertEquals(
            "A passage or a note — one of the two",
            validatePaperNote(draft, 320).exceptionOrNull()?.message
        )
    }

    @Test
    fun `the page number is optional`() {
        val note = validatePaperNote(PaperNoteDraft(quote = "A line"), 320).getOrThrow()
        assertNull(note.pageNumber)
    }

    @Test
    fun `a non-numeric page is refused`() {
        assertEquals(
            "The page should be a number",
            validatePaperNote(PaperNoteDraft(quote = "A line", page = "xii"), 320)
                .exceptionOrNull()?.message
        )
    }

    @Test
    fun `page zero is refused`() {
        assertEquals(
            "Pages start at 1",
            validatePaperNote(PaperNoteDraft(quote = "A line", page = "0"), 320)
                .exceptionOrNull()?.message
        )
    }

    @Test
    fun `a page past the end is refused, and says how long the book is`() {
        assertEquals(
            "This book has 320 pages",
            validatePaperNote(PaperNoteDraft(quote = "A line", page = "321"), 320)
                .exceptionOrNull()?.message
        )
    }

    @Test
    fun `the last page is not past the end`() {
        assertEquals(
            320,
            validatePaperNote(PaperNoteDraft(quote = "A line", page = "320"), 320)
                .getOrThrow().pageNumber
        )
    }

    @Test
    fun `without a page count any positive page is accepted`() {
        // The book was added without one; refusing input here would punish the reader
        // twice for the same skipped field.
        assertEquals(
            4000,
            validatePaperNote(PaperNoteDraft(quote = "A line", page = "4000"), null)
                .getOrThrow().pageNumber
        )
    }
}
