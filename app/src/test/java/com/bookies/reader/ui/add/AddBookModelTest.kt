package com.bookies.reader.ui.add

import com.bookies.reader.data.repo.OpenLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-a-paper-book dialog's decisions.
 *
 * Nothing here touches the network: [OpenLibrary.Result] values are constructed directly,
 * which is the whole point of that class returning a sealed type rather than throwing.
 */
class AddBookModelTest {

    private fun meta(
        isbn: String = "9780140449136",
        title: String = "The Odyssey",
        authors: String = "Homer",
        pageCount: Int? = 416,
        publisher: String? = "Penguin",
        publishDate: String? = "1996"
    ) = OpenLibrary.Metadata(
        isbn = isbn,
        title = title,
        authors = authors,
        pageCount = pageCount,
        coverUrl = null,
        publishDate = publishDate,
        publisher = publisher
    )

    // --- the ISBN field -----------------------------------------------------------------

    @Test
    fun `hyphens are dropped as they are typed`() {
        assertEquals("9780140449136", filterIsbnInput("978-0-14-044913-6"))
    }

    @Test
    fun `spaces and stray letters cannot enter the field`() {
        assertEquals("0141182", filterIsbnInput("0 14 1182 abc"))
    }

    @Test
    fun `a check digit X survives, in either case`() {
        assertEquals("043942089X", filterIsbnInput("043942089x"))
        assertEquals("043942089X", filterIsbnInput("0-439-42089-X"))
    }

    @Test
    fun `the field cannot grow past thirteen characters`() {
        assertEquals(MAX_ISBN_LENGTH, filterIsbnInput("97801404491369780140449136").length)
    }

    @Test
    fun `lookup waits for a complete, checksummed ISBN`() {
        assertFalse(canLookUp(""))
        assertFalse(canLookUp("978014044913"))
        assertTrue(canLookUp("9780140449136"))
        assertTrue(canLookUp("0140449132"))
    }

    @Test
    fun `an EAN that is not a book is not lookuppable`() {
        // A valid EAN-13 checksum, but not a Bookland prefix.
        assertFalse(canLookUp("4006381333931"))
    }

    // --- the hint under it --------------------------------------------------------------

    @Test
    fun `an empty field says nothing`() {
        assertNull(isbnHint(""))
    }

    @Test
    fun `a valid ISBN says nothing`() {
        assertNull(isbnHint("9780140449136"))
    }

    @Test
    fun `a half-typed ISBN is told how long one is, not that it is wrong`() {
        assertEquals("An ISBN is 10 or 13 characters", isbnHint("978014"))
    }

    @Test
    fun `a full-length ISBN with a bad check digit is called out`() {
        // Last digit transposed: exactly what a mis-read barcode produces.
        assertEquals(
            "That is not a valid ISBN — check for a mistyped digit",
            isbnHint("9780140449137")
        )
        assertEquals(
            "That is not a valid ISBN — check for a mistyped digit",
            isbnHint("0140449133")
        )
    }

    // --- what a lookup leads to ---------------------------------------------------------

    @Test
    fun `a found book goes to confirmation`() {
        val found = OpenLibrary.Result.Found(meta())
        val outcome = afterLookup(found, existingTitle = null)
        assertEquals(AddStep.Confirm(meta()), outcome.step)
        assertNull(outcome.hint)
    }

    @Test
    fun `a book already on the shelf beats the lookup result`() {
        val outcome = afterLookup(OpenLibrary.Result.Found(meta()), existingTitle = "The Odyssey")
        assertEquals(AddStep.AlreadyHere("The Odyssey"), outcome.step)
    }

    @Test
    fun `a duplicate is reported even when Open Library has nothing`() {
        // The shelf is the authority on what is already here; the lookup is only metadata.
        val outcome = afterLookup(OpenLibrary.Result.NotFound, existingTitle = "Middlemarch")
        assertEquals(AddStep.AlreadyHere("Middlemarch"), outcome.step)
    }

    @Test
    fun `an unknown ISBN offers hand entry and says why`() {
        val outcome = afterLookup(OpenLibrary.Result.NotFound, existingTitle = null)
        val step = outcome.step as AddStep.Manual
        assertEquals("Open Library has no record of that ISBN.", step.reason)
    }

    @Test
    fun `a transport failure stays on the field, because it is worth retrying`() {
        val result = OpenLibrary.Result.Error("Could not reach Open Library")
        val outcome = afterLookup(result, existingTitle = null)
        assertEquals(AddStep.Entry, outcome.step)
        assertEquals("Could not reach Open Library. Try again, or add the book by hand.", outcome.hint)
    }

    @Test
    fun `an invalid ISBN stays on the field with the checksum complaint`() {
        val outcome = afterLookup(OpenLibrary.Result.InvalidIsbn("123"), existingTitle = null)
        assertEquals(AddStep.Entry, outcome.step)
        assertEquals("That is not a valid ISBN — check for a mistyped digit", outcome.hint)
    }

    // --- hand entry ---------------------------------------------------------------------

    @Test
    fun `rejecting a match starts hand entry from what it did return`() {
        val draft = draftFrom(meta())
        assertEquals("The Odyssey", draft.title)
        assertEquals("Homer", draft.authors)
        assertEquals("416", draft.pageCount)
        assertEquals("9780140449136", draft.isbn)
    }

    @Test
    fun `a match with no page count leaves the field empty rather than writing zero`() {
        assertEquals("", draftFrom(meta(pageCount = null)).pageCount)
    }

    @Test
    fun `hand entry after a failed scan keeps the ISBN`() {
        assertEquals("9780140449136", draftFor("978-0-14-044913-6").isbn)
    }

    @Test
    fun `hand entry with no scan behind it has no ISBN`() {
        assertNull(draftFor("").isbn)
    }

    @Test
    fun `a title is the only required field`() {
        val book = validate(ManualDraft(title = "  Wolf Hall  ")).getOrThrow()
        assertEquals("Wolf Hall", book.title)
        assertEquals("", book.authors)
        assertNull(book.pageCount)
        assertNull(book.isbn)
    }

    @Test
    fun `a blank title is refused`() {
        val error = validate(ManualDraft(title = "   ")).exceptionOrNull()
        assertEquals("A title, at least", error?.message)
    }

    @Test
    fun `commas and semicolons both separate authors, and one separator is written`() {
        val book = validate(
            ManualDraft(title = "Good Omens", authors = "Terry Pratchett, Neil Gaiman")
        ).getOrThrow()
        assertEquals("Terry Pratchett; Neil Gaiman", book.authors)
    }

    @Test
    fun `a trailing separator does not produce an empty author`() {
        assertEquals("Homer", joinAuthors("Homer, "))
        assertEquals("", joinAuthors(" , ; "))
    }

    @Test
    fun `a non-numeric page count is refused`() {
        val error = validate(ManualDraft(title = "X", pageCount = "many")).exceptionOrNull()
        assertEquals("Pages should be a number", error?.message)
    }

    @Test
    fun `zero and negative page counts are refused`() {
        assertEquals(
            "That page count cannot be right",
            validate(ManualDraft(title = "X", pageCount = "0")).exceptionOrNull()?.message
        )
        assertEquals(
            "That page count cannot be right",
            validate(ManualDraft(title = "X", pageCount = "-5")).exceptionOrNull()?.message
        )
    }

    @Test
    fun `an absurd page count is refused, because it is a typo`() {
        assertEquals(
            "That page count cannot be right",
            validate(ManualDraft(title = "X", pageCount = "99999")).exceptionOrNull()?.message
        )
        // The boundary itself is allowed.
        assertEquals(
            MAX_PAGE_COUNT,
            validate(ManualDraft(title = "X", pageCount = "$MAX_PAGE_COUNT")).getOrThrow().pageCount
        )
    }

    @Test
    fun `a validated draft carries the scanned ISBN through`() {
        val draft = ManualDraft(title = "The Odyssey", pageCount = "416", isbn = "9780140449136")
        assertEquals("9780140449136", validate(draft).getOrThrow().isbn)
    }

    // --- the confirmation line ----------------------------------------------------------

    @Test
    fun `the summary joins whatever is known`() {
        assertEquals("Penguin · 1996 · 416 pages", metaSummary(meta()))
    }

    @Test
    fun `the summary omits what is missing rather than leaving gaps`() {
        assertEquals("416 pages", metaSummary(meta(publisher = null, publishDate = null)))
        assertEquals("", metaSummary(meta(publisher = null, publishDate = null, pageCount = null)))
    }
}
