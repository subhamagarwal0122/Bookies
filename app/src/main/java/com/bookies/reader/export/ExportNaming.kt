package com.bookies.reader.export

import com.bookies.reader.data.db.BookEntity

/**
 * Names the file a Markdown export lands in.
 *
 * The name is the only part of the export the receiving app shows before it is opened,
 * so it is derived from the title rather than from the book's UUID. Kept Android-free —
 * and therefore testable — because the interesting cases are all string handling:
 * punctuation that is illegal on FAT and on every cloud drive, titles that are entirely
 * punctuation, and titles long enough to trip a filesystem's name limit.
 */
object ExportNaming {

    /** Comfortably under every filesystem's 255-byte limit even at 4 bytes per char. */
    private const val MAX_STEM = 60

    fun fileName(book: BookEntity): String = "${slug(book.title)}.md"

    /**
     * Sub-directory for one book's exports, so two books whose titles slug identically
     * cannot overwrite each other's file while a share target is still reading it. The
     * id never reaches the user: it names the directory, not the file.
     */
    fun directoryName(book: BookEntity): String = book.id

    private fun slug(title: String): String {
        val out = StringBuilder(MAX_STEM)
        var pendingSeparator = false
        for (ch in title) {
            // isLetterOrDigit rather than an ASCII range: a Cyrillic or CJK title should
            // survive as itself, not be flattened to the fallback name.
            if (ch.isLetterOrDigit()) {
                if (pendingSeparator && out.isNotEmpty()) out.append('-')
                pendingSeparator = false
                out.append(ch)
                if (out.length >= MAX_STEM) break
            } else {
                // Collapse any run of punctuation or space into a single separator, and
                // never emit a trailing one — a name ending in "." or " " is rejected
                // outright by Windows, which is where these files often end up.
                pendingSeparator = true
            }
        }
        return if (out.isEmpty()) "book" else out.toString()
    }
}
