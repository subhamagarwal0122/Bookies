package com.bookies.reader.epub

import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two directions that matter are pulled apart deliberately:
 * [extendedHighlightCollapsesToTheLongest] proves we clean up Kindle's revision spam,
 * and [distinctNearbyHighlightsAreNeverCollapsed] proves we do not pay for that by
 * eating someone's second highlight. Failing to dedup is untidy; over-dedupping is data
 * loss, so the false-collapse cases are the ones to keep adding to.
 */
class KindleClippingsTest {

    private fun entry(title: String, meta: String, body: String) =
        "$title\n$meta\n\n$body\n==========\n"

    // --- the shape of the file ----------------------------------------------

    @Test
    fun parsesANormalMultiEntryFile() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on page 12 | Location 1234-1240 | Added on Monday, 3 March 2025 14:23:11",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on page 40 | Location 3300-3305 | Added on Tuesday, 4 March 2025 09:00:00",
            "It is not down in any map; true places never are."
        ) + entry(
            "The Waves - Virginia Woolf",
            "- Your Highlight on Location 500-503 | Added on Wednesday, 5 March 2025 20:15:00",
            "I am not one and simple, but complex and many."
        )

        val result = KindleClippings.parse(file)

        assertEquals(3, result.entriesFound)
        assertEquals(0, result.entriesSkipped)
        assertEquals(2, result.books.size)

        val moby = result.books.first()
        assertEquals("Moby-Dick", moby.title)
        assertEquals("Herman Melville", moby.authors)
        assertEquals(2, moby.clippings.size)
        assertEquals(3305, moby.maxLocation)

        val waves = result.books[1]
        assertEquals("The Waves", waves.title)
        assertEquals("Virginia Woolf", waves.authors)
        assertEquals(1, waves.clippings.size)

        val first = moby.clippings.first()
        assertEquals(AnnotationType.HIGHLIGHT, first.type)
        assertEquals(1234, first.locationStart)
        assertEquals(1240, first.locationEnd)
        assertEquals("Location 1234-1240", first.locationRaw)
        assertNotNull(first.addedAt)
    }

    @Test
    fun stripsTheByteOrderMark() {
        val file = "﻿" + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 10-12 | Added on Monday, 3 March 2025 14:23:11",
            "Call me Ishmael, and mind the mark."
        )
        val book = KindleClippings.parse(file).books.single()
        assertEquals("Moby-Dick", book.title)
    }

    @Test
    fun toleratesCrlfAndATrailingSeparator() {
        // A multi-line body on purpose: a single-line one has its stray carriage return
        // removed by the trim and would hide a missing newline normalisation.
        val file = (
            entry(
                "Moby-Dick (Herman Melville)",
                "- Your Highlight on Location 10-12 | Added on Monday, 3 March 2025 14:23:11",
                "Call me Ishmael, and mind the mark.\nSome years ago—never mind how long."
            ) + "==========\r\n"
            ).replace("\n", "\r\n")

        val result = KindleClippings.parse(file)
        assertEquals(1, result.entriesFound)
        val clipping = result.books.single().clippings.single()
        assertEquals(
            "Call me Ishmael, and mind the mark.\nSome years ago—never mind how long.",
            clipping.text
        )
        assertTrue("no carriage return may survive into a quote", '\r' !in clipping.text)
    }

    @Test
    fun parsesBothTitleLineShapesAndTheFallback() {
        assertEquals("Moby-Dick" to "Herman Melville", KindleClippings.parseTitleLine("Moby-Dick (Herman Melville)"))
        assertEquals("Moby-Dick" to "Herman Melville", KindleClippings.parseTitleLine("Moby-Dick - Herman Melville"))
        assertEquals("Untitled" to "", KindleClippings.parseTitleLine("Untitled"))
        // A title of its own containing parentheses must not be shredded by the paren rule.
        assertEquals(
            "Dune (Deluxe Edition)" to "Frank Herbert",
            KindleClippings.parseTitleLine("Dune (Deluxe Edition) (Frank Herbert)")
        )
    }

    // --- deduplication -------------------------------------------------------

    @Test
    fun extendedHighlightCollapsesToTheLongest() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1200 | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1201 | Added on Monday, 3 March 2025 14:21:00",
            "Call me Ishmael. Some years ago"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1202 | Added on Monday, 3 March 2025 14:22:00",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        )

        val result = KindleClippings.parse(file)
        assertEquals(3, result.entriesFound)
        assertEquals(2, result.entriesMerged)

        val clipping = result.books.single().clippings.single()
        assertEquals("Call me Ishmael. Some years ago—never mind how long precisely.", clipping.text)
        assertEquals(1200, clipping.locationStart)
        assertEquals(1202, clipping.locationEnd)
        // Latest revision's timestamp, not the first sip's.
        assertEquals(
            KindleClippings.parseDate("Monday, 3 March 2025 14:22:00"),
            clipping.addedAt
        )
    }

    @Test
    fun collapsesARevisionThatGrewBackwards() {
        // Extending a selection leftwards makes the new text a SUFFIX-superstring, and
        // gives it a lower start location than the entry it replaces.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1202-1203 | Added on Monday, 3 March 2025 14:20:00",
            "never mind how long precisely."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1203 | Added on Monday, 3 March 2025 14:25:00",
            "Some years ago—never mind how long precisely."
        )

        val clipping = KindleClippings.parse(file).books.single().clippings.single()
        assertEquals("Some years ago—never mind how long precisely.", clipping.text)
        assertEquals(1200, clipping.locationStart)
    }

    @Test
    fun collapsesTransitivelyAcrossAdjacentLocations() {
        // a touches b, b touches c, a does not touch c: all three are still one passage.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1201 | Added on Monday, 3 March 2025 14:20:00",
            "There now is your insular city of the Manhattoes"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1202 | Added on Monday, 3 March 2025 14:21:00",
            "There now is your insular city of the Manhattoes, belted round by wharves"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1201-1204 | Added on Monday, 3 March 2025 14:22:00",
            "There now is your insular city of the Manhattoes, belted round by wharves as Indian isles by coral reefs"
        )

        val book = KindleClippings.parse(file).books.single()
        assertEquals(1, book.clippings.size)
        assertEquals(1204, book.clippings.single().locationEnd)
    }

    @Test
    fun collapsesARevisionThatStartsOneLocationPastTheOld() {
        // Growing a highlight over a location boundary leaves the old entry ending at
        // 1201 and the new one starting at 1202: no overlap at all, still one passage.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1201 | Added on Monday, 3 March 2025 14:20:00",
            "There now is your insular city of the Manhattoes"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1202-1203 | Added on Monday, 3 March 2025 14:21:00",
            "There now is your insular city of the Manhattoes, belted round by wharves"
        )

        val book = KindleClippings.parse(file).books.single()
        assertEquals(1, book.clippings.size)
        assertEquals(1200, book.clippings.single().locationStart)
        assertEquals(1203, book.clippings.single().locationEnd)
    }

    @Test
    fun distinctNearbyHighlightsAreNeverCollapsed() {
        // Same location span, two different sentences. Position alone would fuse these;
        // requiring text containment as well is what saves them.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1205 | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1204-1210 | Added on Monday, 3 March 2025 14:21:00",
            "having little or no money in my purse, and nothing particular to interest me on shore."
        )

        val result = KindleClippings.parse(file)
        assertEquals(0, result.entriesMerged)
        assertEquals(2, result.books.single().clippings.size)
    }

    @Test
    fun aRepeatedShortPhraseIsNotTreatedAsARevision() {
        // "the whale" sits inside the longer quote and the spans touch, but it is far
        // too short for containment to mean anything. Two annotations must survive.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 900-901 | Added on Monday, 3 March 2025 14:20:00",
            "the whale"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 901-903 | Added on Monday, 3 March 2025 14:21:00",
            "It was the whale that first taught him the meaning of patience."
        )

        assertEquals(2, KindleClippings.parse(file).books.single().clippings.size)
    }

    @Test
    fun identicalTextFarApartIsNotCollapsed() {
        // A refrain the reader highlighted in two chapters. Containment is total; the
        // locations are nowhere near each other, so they stay two annotations.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 100-101 | Added on Monday, 3 March 2025 14:20:00",
            "and I only am escaped alone to tell thee"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 9000-9001 | Added on Monday, 3 March 2025 14:21:00",
            "and I only am escaped alone to tell thee"
        )

        assertEquals(2, KindleClippings.parse(file).books.single().clippings.size)
    }

    @Test
    fun highlightsWithoutLocationsAreNeverMerged() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight | Added on Monday, 3 March 2025 14:21:00",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        )

        val result = KindleClippings.parse(file)
        assertEquals(0, result.entriesMerged)
        assertEquals(2, result.books.single().clippings.size)
    }

    // --- notes and bookmarks --------------------------------------------------

    @Test
    fun aNoteInsideAHighlightBecomesThatHighlightsNote() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1210 | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Note on Location 1205 | Added on Monday, 3 March 2025 14:21:00",
            "The most famous opening in English."
        )

        val clipping = KindleClippings.parse(file).books.single().clippings.single()
        assertEquals(AnnotationType.HIGHLIGHT, clipping.type)
        assertEquals("The most famous opening in English.", clipping.note)
    }

    @Test
    fun aNoteGoesToTheTightestHighlightEnclosingIt() {
        // A long highlight and a short distinct one inside its span. The note was
        // written against the short one; handing it to the long one would file the
        // reader's thought under the wrong passage.
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1220 | Added on Monday, 3 March 2025 14:20:00",
            "A long passage running over many locations and saying a great deal at length."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1205-1206 | Added on Monday, 3 March 2025 14:21:00",
            "Whenever it is a damp, drizzly November in my soul."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Note on Location 1205 | Added on Monday, 3 March 2025 14:22:00",
            "This is the line I keep coming back to."
        )

        val clippings = KindleClippings.parse(file).books.single().clippings
        assertEquals(2, clippings.size)
        val short = clippings.single { it.locationStart == 1205 }
        assertEquals("This is the line I keep coming back to.", short.note)
        assertNull(clippings.single { it.locationStart == 1200 }.note)
    }

    @Test
    fun aNoteMatchingNoHighlightStandsAlone() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1210 | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael. Some years ago—never mind how long precisely."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Note on Location 8000 | Added on Monday, 3 March 2025 14:21:00",
            "Ahab is not in this chapter at all."
        )

        val clippings = KindleClippings.parse(file).books.single().clippings
        assertEquals(2, clippings.size)
        val note = clippings.single { it.type == AnnotationType.NOTE }
        assertEquals("Ahab is not in this chapter at all.", note.text)
        assertNull(clippings.single { it.type == AnnotationType.HIGHLIGHT }.note)
    }

    @Test
    fun aBookmarkSurvivesWithNoBody() {
        val file = "Moby-Dick (Herman Melville)\n" +
            "- Your Bookmark on page 5 | Location 60 | Added on Monday, 3 March 2025 14:23:11\n" +
            "\n" +
            "==========\n"

        val clipping = KindleClippings.parse(file).books.single().clippings.single()
        assertEquals(AnnotationType.BOOKMARK, clipping.type)
        assertEquals("", clipping.text)
        assertEquals(60, clipping.locationStart)
    }

    // --- things that must be dropped -----------------------------------------

    @Test
    fun clippingLimitNoticesAreSkipped() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1200-1201 | Added on Monday, 3 March 2025 14:20:00",
            "<You have reached the clipping limit for this item>"
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1300-1301 | Added on Monday, 3 March 2025 14:21:00",
            "Real text that should survive the notice above."
        )

        val result = KindleClippings.parse(file)
        assertEquals(1, result.entriesSkipped)
        assertEquals("Real text that should survive the notice above.", result.books.single().clippings.single().text)
    }

    @Test
    fun malformedEntriesAreSkippedWithoutKillingTheImport() {
        val file = "Just a title and nothing else\n==========\n" +
            "Moby-Dick (Herman Melville)\nrandom prose where a header should be\n\nbody\n==========\n" +
            entry(
                "Moby-Dick (Herman Melville)",
                "- Your Highlight on Location 1300-1301 | Added on Monday, 3 March 2025 14:21:00",
                "The one good entry in a damaged file."
            ) +
            entry(
                "Moby-Dick (Herman Melville)",
                "- Your Highlight on Location 1400-1401 | Added on Monday, 3 March 2025 14:22:00",
                "   "
            )

        val result = KindleClippings.parse(file)
        assertEquals(4, result.entriesFound)
        assertEquals(3, result.entriesSkipped)
        assertEquals("The one good entry in a damaged file.", result.books.single().clippings.single().text)
    }

    // --- dates ----------------------------------------------------------------

    @Test
    fun parsesTheCommonEnglishDateForms() {
        assertNotNull(KindleClippings.parseDate("Monday, 3 March 2025 14:23:11"))
        assertNotNull(KindleClippings.parseDate("Monday, March 3, 2025 2:23:11 PM"))
        assertNotNull(KindleClippings.parseDate("Monday, 3 March 2025 2:23:11 PM"))
        // Same instant expressed two ways must agree, or the index sorts imports wrongly.
        assertEquals(
            KindleClippings.parseDate("Monday, 3 March 2025 14:23:11"),
            KindleClippings.parseDate("Monday, March 3, 2025 2:23:11 PM")
        )
    }

    @Test
    fun anUnparseableDateStillImportsTheClipping() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on Location 1234-1240 | Ajouté le lundi 3 mars 2025 14:23:11",
            "Call me Ishmael, in any locale whatsoever."
        )

        val result = KindleClippings.parse(file)
        assertEquals(0, result.entriesSkipped)
        assertEquals(1, result.undatedEntries)

        val clipping = result.books.single().clippings.single()
        assertNull(clipping.addedAt)
        assertEquals(1234, clipping.locationStart)

        // And it must still reach the database with a usable createdAt.
        val row = KindleClippings.toAnnotations(result.books.single(), "book-1", now = 99L).single()
        assertEquals(99L, row.createdAt)
    }

    // --- mapping to rows -------------------------------------------------------

    @Test
    fun mapsToAnnotationRowsWithNoPageNumberAndAnEstimatedProgression() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on page 12 | Location 500-505 | Added on Monday, 3 March 2025 14:20:00",
            "Call me Ishmael, roughly a quarter of the way in."
        ) + entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight on page 90 | Location 2000-2000 | Added on Monday, 3 March 2025 14:21:00",
            "The furthest point this file knows about."
        )

        val book = KindleClippings.parse(file).books.single()
        var n = 0
        val rows = KindleClippings.toAnnotations(book, "book-1", now = 1_000L) { "id-${n++}" }

        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertEquals("book-1", row.bookId)
            assertEquals(AnnotationSource.KINDLE, row.source)
            // Invariant 4 and the reflowable-EPUB gotcha: a digital book has no pages,
            // whatever the Kindle claims on the metadata line.
            assertNull(row.pageNumber)
            assertNull(row.href)
            assertNull(row.cssSelector)
            assertNull(row.startOffset)
            assertNull(row.endOffset)
            assertEquals("", row.prefix)
            assertEquals("", row.suffix)
            assertEquals("", row.contextBefore)
            assertEquals("", row.contextAfter)
            assertEquals(1_000L, row.updatedAt)
            assertTrue(row.progression in 0.0..1.0)
        }

        assertEquals("id-0", rows[0].id)
        assertEquals("Location 500-505", rows[0].sourceRef)
        assertEquals("Call me Ishmael, roughly a quarter of the way in.", rows[0].quote)
        // 500 / 2000 — an estimate, because the file never says how long the book is.
        assertEquals(0.25, rows[0].progression, 1e-9)
        assertEquals(1.0, rows[1].progression, 1e-9)
    }

    @Test
    fun progressionIsZeroWhenThereIsNothingToScaleAgainst() {
        val file = entry(
            "Moby-Dick (Herman Melville)",
            "- Your Highlight | Added on Monday, 3 March 2025 14:20:00",
            "A clipping with no location at all."
        )
        val book = KindleClippings.parse(file).books.single()
        val row = KindleClippings.toAnnotations(book, "book-1", now = 1L).single()
        assertEquals(0.0, row.progression, 1e-9)
        assertNull(row.sourceRef)
    }
}
