package com.bookies.reader.data.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Fixtures below are the real `jscmd=data` shape: objects-with-a-name for authors and
 * publishers, a three-size cover object, and a pile of fields we ignore. The extra keys
 * are kept deliberately — dropping them would stop the test proving that a real response
 * (which carries dozens more) does not blow up the decoder.
 */
class OpenLibraryTest {

    // --- fixtures ---------------------------------------------------------------------

    private val complete = """
        {
          "ISBN:9780140449198": {
            "url": "https://openlibrary.org/books/OL7360173M/Meditations",
            "key": "/books/OL7360173M",
            "title": "Meditations",
            "subtitle": "a new translation",
            "authors": [
              {"url": "https://openlibrary.org/authors/OL23919A/Marcus_Aurelius", "name": "Marcus Aurelius"}
            ],
            "number_of_pages": 254,
            "pagination": "xliv, 254 p. ;",
            "identifiers": {"isbn_13": ["9780140449198"], "isbn_10": ["0140449191"], "openlibrary": ["OL7360173M"]},
            "classifications": {"dewey_decimal_class": ["188"]},
            "publishers": [{"name": "Penguin Books"}],
            "publish_places": [{"name": "London"}],
            "publish_date": "2006",
            "subjects": [{"name": "Ethics", "url": "https://openlibrary.org/subjects/ethics"}],
            "cover": {
              "small": "https://covers.openlibrary.org/b/id/240727-S.jpg",
              "medium": "https://covers.openlibrary.org/b/id/240727-M.jpg",
              "large": "https://covers.openlibrary.org/b/id/240727-L.jpg"
            },
            "ebooks": [{"preview_url": "https://archive.org/details/meditations", "availability": "borrow"}],
            "excerpts": [{"text": "Begin the morning by saying to thyself...", "comment": "opening"}],
            "links": [{"title": "Wikipedia", "url": "https://en.wikipedia.org/wiki/Meditations"}],
            "notes": "Translated with an introduction."
          }
        }
    """.trimIndent()

    private val multipleAuthors = """
        {
          "ISBN:9780262033848": {
            "title": "Introduction to Algorithms",
            "authors": [
              {"name": "Thomas H. Cormen"},
              {"name": "Charles E. Leiserson"},
              {"name": "Ronald L. Rivest"},
              {"name": "Clifford Stein"}
            ],
            "number_of_pages": 1292,
            "publishers": [{"name": "MIT Press"}],
            "publish_date": "2009"
          }
        }
    """.trimIndent()

    private val noPages = """
        {"ISBN:9780140449198": {"title": "Meditations", "authors": [{"name": "Marcus Aurelius"}],
         "cover": {"medium": "https://covers.openlibrary.org/b/id/240727-M.jpg"}}}
    """.trimIndent()

    private val noCover = """
        {"ISBN:9780140449198": {"title": "Meditations", "authors": [{"name": "Marcus Aurelius"},
         {"name": "Martin Hammond"}], "number_of_pages": 254}}
    """.trimIndent()

    private val noAuthors = """
        {"ISBN:9780140449198": {"title": "Beowulf", "number_of_pages": 120,
         "publishers": [{"name": "Penguin Books"}]}}
    """.trimIndent()

    private val notFound = "{}"

    /** Valid ISBN-13, valid ISBN-10, and the same ISBN-13 with publisher hyphens. */
    private val isbn13 = "9780140449198"
    private val isbn10 = "0451524934"
    private val isbn10WithX = "080442957X"
    private val hyphenated = "978-0-14-044919-8"

    // --- helpers ----------------------------------------------------------------------

    /** Records the URL it was asked for, so tests can assert we never spend a bad request. */
    private class FakeFetcher(private val body: String) : OpenLibrary.Fetcher {
        val urls = mutableListOf<String>()
        override fun get(url: String): String {
            urls += url
            return body
        }
    }

    private fun found(result: OpenLibrary.Result): OpenLibrary.Metadata {
        assertTrue("expected Found, got $result", result is OpenLibrary.Result.Found)
        return (result as OpenLibrary.Result.Found).book
    }

    private val ol = OpenLibrary(OpenLibrary.Fetcher { error("no network in parse-only tests") })

    // --- parsing ----------------------------------------------------------------------

    @Test
    fun completeRecordMapsEveryFieldTheShelfUses() {
        val book = found(ol.parse(isbn13, complete))
        assertEquals("Meditations", book.title)
        assertEquals("Marcus Aurelius", book.authors)
        assertEquals(254, book.pageCount)
        assertEquals("https://covers.openlibrary.org/b/id/240727-L.jpg", book.coverUrl)
        assertEquals("2006", book.publishDate)
        assertEquals("Penguin Books", book.publisher)
        assertEquals(isbn13, book.isbn)
    }

    @Test
    fun multipleAuthorsJoinWithSemicolonToMatchBookEntity() {
        val book = found(ol.parse("9780262033848", multipleAuthors))
        assertEquals(
            "Thomas H. Cormen; Charles E. Leiserson; Ronald L. Rivest; Clifford Stein",
            book.authors
        )
    }

    @Test
    fun missingPageCountIsNullNotZero() {
        // Zero would make PhysicalBooks.progressionFor divide by a real-looking number.
        assertNull(found(ol.parse(isbn13, noPages)).pageCount)
    }

    @Test
    fun missingCoverIsNull() {
        assertNull(found(ol.parse(isbn13, noCover)).coverUrl)
    }

    @Test
    fun coverFallsBackToMediumWhenLargeIsAbsent() {
        assertEquals(
            "https://covers.openlibrary.org/b/id/240727-M.jpg",
            found(ol.parse(isbn13, noPages)).coverUrl
        )
    }

    @Test
    fun missingAuthorsGivesEmptyStringNotAFailure() {
        val book = found(ol.parse(isbn13, noAuthors))
        assertEquals("", book.authors)
        assertEquals("Beowulf", book.title)
    }

    @Test
    fun emptyObjectIsNotFoundNotError() {
        assertEquals(OpenLibrary.Result.NotFound, ol.parse(isbn13, notFound))
    }

    @Test
    fun malformedJsonIsAnError() {
        assertTrue(ol.parse(isbn13, "{\"ISBN:9780140449198\": {\"title\":") is OpenLibrary.Result.Error)
        assertTrue(ol.parse(isbn13, "not json at all") is OpenLibrary.Result.Error)
        assertTrue(ol.parse(isbn13, "") is OpenLibrary.Result.Error)
    }

    @Test
    fun recordWithoutATitleIsAnErrorRatherThanAnUntitledShelfEntry() {
        val body = """{"ISBN:9780140449198": {"authors": [{"name": "Anon"}]}}"""
        assertTrue(ol.parse(isbn13, body) is OpenLibrary.Result.Error)
    }

    @Test
    fun aDifferentlySpelledKeyStillResolvesWhenItIsTheOnlyRecord() {
        val body = """{"ISBN:978-0-14-044919-8": {"title": "Meditations"}}"""
        assertEquals("Meditations", found(ol.parse(isbn13, body)).title)
    }

    // --- ISBN normalisation -----------------------------------------------------------

    @Test
    fun hyphensAndSpacesAreStripped() {
        assertEquals(isbn13, OpenLibrary.normaliseIsbn(hyphenated))
        assertEquals(isbn13, OpenLibrary.normaliseIsbn("978 0 14 044919 8"))
    }

    @Test
    fun isbn10IsAccepted() {
        assertEquals(isbn10, OpenLibrary.normaliseIsbn(isbn10))
        assertEquals(isbn10, OpenLibrary.normaliseIsbn("0-451-52493-4"))
    }

    @Test
    fun trailingXIsUppercased() {
        assertEquals(isbn10WithX, OpenLibrary.normaliseIsbn("080442957x"))
        assertEquals(isbn10WithX, OpenLibrary.normaliseIsbn("0-8044-2957-x"))
    }

    @Test
    fun badCheckDigitsAreRejected() {
        assertNull(OpenLibrary.normaliseIsbn("9780140449199"))  // 13, last digit wrong
        assertNull(OpenLibrary.normaliseIsbn("0451524935"))     // 10, last digit wrong
        assertNull(OpenLibrary.normaliseIsbn("9780140449189"))  // 13, transposed digits
    }

    @Test
    fun xIsOnlyValidAsTheFinalIsbn10Digit() {
        assertNull(OpenLibrary.normaliseIsbn("X804429571"))
    }

    @Test
    fun nonBooklandEansAreRejectedEvenWhenTheChecksumPasses() {
        // 4006381333931 is a real EAN-13 (a Faber-Castell pen) and passes mod-10.
        assertNull(OpenLibrary.normaliseIsbn("4006381333931"))
    }

    @Test
    fun wrongLengthAndJunkAreRejected() {
        assertNull(OpenLibrary.normaliseIsbn(""))
        assertNull(OpenLibrary.normaliseIsbn("123"))
        assertNull(OpenLibrary.normaliseIsbn("97801404491980"))
        assertNull(OpenLibrary.normaliseIsbn("Meditations"))
    }

    // --- lookup (fake fetcher) --------------------------------------------------------

    @Test
    fun lookupNormalisesBeforeBuildingTheUrl() = runBlocking {
        val fetcher = FakeFetcher(complete)
        val book = found(OpenLibrary(fetcher).lookup(hyphenated))
        assertEquals("Meditations", book.title)
        assertEquals(isbn13, book.isbn)
        assertEquals(
            "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn13&format=json&jscmd=data",
            fetcher.urls.single()
        )
    }

    @Test
    fun lookupOfAnIsbn10Works() = runBlocking {
        val body = """{"ISBN:$isbn10": {"title": "1984", "authors": [{"name": "George Orwell"}]}}"""
        val fetcher = FakeFetcher(body)
        assertEquals("George Orwell", found(OpenLibrary(fetcher).lookup("0-451-52493-4")).authors)
        assertTrue(fetcher.urls.single().contains("ISBN:$isbn10"))
    }

    @Test
    fun anInvalidIsbnCostsNoRequest() = runBlocking {
        val fetcher = FakeFetcher(complete)
        val result = OpenLibrary(fetcher).lookup("978-0-14-044919-9")
        assertTrue("expected InvalidIsbn, got $result", result is OpenLibrary.Result.InvalidIsbn)
        assertTrue("no request should have been made", fetcher.urls.isEmpty())
    }

    @Test
    fun unknownIsbnIsNotFound() = runBlocking {
        assertEquals(OpenLibrary.Result.NotFound, OpenLibrary(FakeFetcher(notFound)).lookup(isbn13))
    }

    @Test
    fun transportFailureIsAnErrorCarryingItsCause() = runBlocking {
        val boom = IOException("Open Library lookup failed: 503")
        val result = OpenLibrary(OpenLibrary.Fetcher { throw boom }).lookup(isbn13)
        assertTrue("expected Error, got $result", result is OpenLibrary.Result.Error)
        assertEquals(boom, (result as OpenLibrary.Result.Error).cause)
    }

    @Test
    fun theThreeOutcomesAreDistinguishable() = runBlocking {
        // The point of the sealed type: the UI says something different for each.
        val ol13 = OpenLibrary(FakeFetcher(complete)).lookup(isbn13)
        val missing = OpenLibrary(FakeFetcher(notFound)).lookup(isbn13)
        val broken = OpenLibrary(FakeFetcher("<html>503</html>")).lookup(isbn13)
        assertTrue(ol13 is OpenLibrary.Result.Found)
        assertTrue(missing is OpenLibrary.Result.NotFound)
        assertTrue(broken is OpenLibrary.Result.Error)
    }
}
