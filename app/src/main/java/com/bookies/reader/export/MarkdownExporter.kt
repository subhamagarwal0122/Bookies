package com.bookies.reader.export

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a book's annotations as Markdown.
 *
 * This is the portability guarantee: it goes inside every Drive bundle and can be
 * written out on demand, so the notes stay readable in any editor with Bookies gone.
 * Deliberately plain — no app-specific syntax, nothing that needs parsing back.
 */
object MarkdownExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun render(book: BookEntity, annotations: List<AnnotationEntity>): String = buildString {
        appendLine("# ${book.title}")
        if (book.authors.isNotBlank()) appendLine("*${book.authors}*")
        appendLine()
        appendLine("> ${annotations.size} annotations · exported ${dateFormat.format(Date())}")
        book.opfIdentifier?.let { appendLine("> Identifier: `$it`") }
        appendLine()
        appendLine("---")
        appendLine()

        var currentChapter: String? = null
        var first = true
        for (annotation in annotations) {
            val chapter = annotation.chapterTitle ?: annotation.href
            if (first || chapter != currentChapter) {
                appendLine("## $chapter")
                appendLine()
                currentChapter = chapter
                first = false
            }
            appendAnnotation(annotation)
        }
    }

    private fun StringBuilder.appendAnnotation(a: AnnotationEntity) {
        when (a.type) {
            AnnotationType.BOOKMARK -> {
                appendLine("🔖 *Bookmark* — ${a.pageNumber?.let { "p. $it" } ?: percent(a.progression)}")
                appendLine()
            }
            else -> {
                // Blockquote the passage; prefix/suffix stay out of it so the quote is
                // exactly what was selected and can be copied cleanly.
                a.quote.lines().forEach { appendLine("> ${it.trim()}") }
                appendLine()
                a.note?.takeIf { it.isNotBlank() }?.let {
                    appendLine(it.trim())
                    appendLine()
                }
            }
        }
        // A paper book has a real page number; an EPUB only has a position.
        val locationLabel = a.pageNumber?.let { "p. $it" } ?: percent(a.progression)
        appendLine("<sub>${dateFormat.format(Date(a.createdAt))} · $locationLabel</sub>")
        appendLine()
    }

    private fun percent(progression: Double) = "${(progression * 100).toInt()}%"
}
