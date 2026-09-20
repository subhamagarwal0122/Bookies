package com.bookies.reader.export

import com.bookies.reader.data.db.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export file name is the only thing the receiving app shows before the file is
 * opened, and it has to survive being written to a FAT card, a Drive folder and a
 * Windows sync client. Titles are arbitrary text, so the edge cases are the point.
 */
class ExportNamingTest {

    private fun book(title: String) = BookEntity(
        id = "b1",
        title = title,
        authors = "",
        coverPath = null,
        fileHash = "",
        opfIdentifier = null,
        localFilePath = null,
        addedAt = 0L,
        updatedAt = 0L
    )

    @Test fun `plain title keeps its words`() {
        assertEquals("The-Sea-The-Sea.md", ExportNaming.fileName(book("The Sea, The Sea")))
    }

    @Test fun `illegal characters become separators`() {
        val name = ExportNaming.fileName(book("""Notes: a/b\c*d?e"f<g>h|i"""))
        assertFalse(name.any { it in """/\*?"<>|:""" })
    }

    @Test fun `runs of punctuation collapse to one separator`() {
        assertEquals("Wolf-Hall.md", ExportNaming.fileName(book("Wolf   ---  Hall")))
    }

    @Test fun `no leading or trailing separator`() {
        val name = ExportNaming.fileName(book("  ...Ulysses...  "))
        assertEquals("Ulysses.md", name)
    }

    @Test fun `windows-hostile trailing dot and space cannot survive`() {
        val name = ExportNaming.fileName(book("Middlemarch. ")).removeSuffix(".md")
        assertFalse(name.endsWith(".") || name.endsWith(" "))
    }

    @Test fun `a title of pure punctuation still yields a usable name`() {
        assertEquals("book.md", ExportNaming.fileName(book("!!! ??? ...")))
    }

    @Test fun `an empty title still yields a usable name`() {
        assertEquals("book.md", ExportNaming.fileName(book("")))
    }

    @Test fun `non-latin titles survive rather than being flattened`() {
        assertEquals("戦争と平和.md", ExportNaming.fileName(book("戦争と平和")))
    }

    @Test fun `a very long title is truncated well short of the filesystem limit`() {
        val name = ExportNaming.fileName(book("word ".repeat(200)))
        assertTrue("was ${name.length}", name.length <= 64)
        assertTrue(name.endsWith(".md"))
    }

    @Test fun `truncation does not leave a trailing separator`() {
        // Truncating mid-run is exactly where a dangling "-" would appear.
        val name = ExportNaming.fileName(book((1..40).joinToString(" ") { "ab" })).removeSuffix(".md")
        assertFalse(name.endsWith("-"))
    }

    @Test fun `two books with the same title get different directories`() {
        val one = book("Ulysses").copy(id = "aaa")
        val two = book("Ulysses").copy(id = "bbb")
        assertEquals(ExportNaming.fileName(one), ExportNaming.fileName(two))
        assertTrue(ExportNaming.directoryName(one) != ExportNaming.directoryName(two))
    }
}
