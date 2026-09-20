package com.bookies.reader.epub

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * On-disk shapes for the Drive bundle.
 *
 * These are intentionally separate from the Room entities. The database can be
 * refactored freely; this format is a contract with every bundle ever written, so it
 * changes only additively and always with [version] bumped.
 */

const val BUNDLE_FORMAT_VERSION = 1

@Serializable
data class BundleMetadata(
    val version: Int = BUNDLE_FORMAT_VERSION,
    val bookId: String,
    val title: String,
    val authors: String,
    val opfIdentifier: String? = null,
    val format: String = "EPUB",
    val isbn: String? = null,
    val pageCount: Int? = null,
    /** SHA-256 of book.epub. Empty for a physical book, whose bundle has no EPUB. */
    val epubSha256: String,
    val readingStatus: String,
    val progression: Double,
    val lastLocatorJson: String? = null,
    val annotationCount: Int,
    val archivedAt: Long
) {
    companion object {
        fun from(book: BookEntity, annotationCount: Int) = BundleMetadata(
            bookId = book.id,
            title = book.title,
            authors = book.authors,
            opfIdentifier = book.opfIdentifier,
            format = book.format.name,
            isbn = book.isbn,
            pageCount = book.pageCount,
            epubSha256 = book.fileHash,
            readingStatus = book.readingStatus.name,
            progression = book.progression,
            lastLocatorJson = book.lastLocatorJson,
            annotationCount = annotationCount,
            archivedAt = System.currentTimeMillis()
        )
    }
}

/**
 * One annotation, shaped after the W3C Web Annotation Data Model so the file is
 * meaningful to tools that have never heard of Bookies.
 */
@Serializable
data class BundledAnnotation(
    val id: String,
    val type: String,
    @SerialName("created") val createdAt: Long,
    @SerialName("modified") val updatedAt: Long,
    val source: String,
    val sourceRef: String? = null,
    val colorArgb: Int? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
    val target: Target,
    val context: Context
) {
    @Serializable
    data class Target(
        /** Spine item path within the EPUB. Null for a physical book. */
        val source: String? = null,
        val selectors: List<Selector> = emptyList(),
        val progression: Double,
        /** Physical books only. */
        val pageNumber: Int? = null
    )

    /** Mirrors the W3C selector union; we emit the two we can always produce. */
    @Serializable
    data class Selector(
        val type: String,               // "TextQuoteSelector" | "TextPositionSelector" | "CssSelector"
        val exact: String? = null,
        val prefix: String? = null,
        val suffix: String? = null,
        val start: Int? = null,
        val end: Int? = null,
        val value: String? = null
    )

    /** The surrounding paragraphs — not part of the W3C model, but the reason this works offline. */
    @Serializable
    data class Context(val before: String = "", val after: String = "")

    companion object {
        fun from(a: AnnotationEntity) = BundledAnnotation(
            id = a.id,
            type = a.type.name.lowercase(),
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
            source = a.source.name.lowercase(),
            sourceRef = a.sourceRef,
            colorArgb = a.colorArgb,
            note = a.note,
            chapterTitle = a.chapterTitle,
            target = Target(
                source = a.href,
                progression = a.progression,
                pageNumber = a.pageNumber,
                selectors = buildList {
                    add(Selector(type = "TextQuoteSelector", exact = a.quote, prefix = a.prefix, suffix = a.suffix))
                    if (a.startOffset != null && a.endOffset != null) {
                        add(Selector(type = "TextPositionSelector", start = a.startOffset, end = a.endOffset))
                    }
                    a.cssSelector?.let { add(Selector(type = "CssSelector", value = it)) }
                }
            ),
            context = Context(a.contextBefore, a.contextAfter)
        )
    }

    fun toEntity(bookId: String): AnnotationEntity {
        val quote = selector("TextQuoteSelector")
        val position = selector("TextPositionSelector")
        val css = selector("CssSelector")
        return AnnotationEntity(
            id = id.ifBlank { UUID.randomUUID().toString() },
            bookId = bookId,
            type = runCatching { AnnotationType.valueOf(type.uppercase()) }.getOrDefault(AnnotationType.HIGHLIGHT),
            colorArgb = colorArgb,
            href = target.source,
            pageNumber = target.pageNumber,
            cssSelector = css?.value,
            startOffset = position?.start,
            endOffset = position?.end,
            progression = target.progression,
            quote = quote?.exact.orEmpty(),
            prefix = quote?.prefix.orEmpty(),
            suffix = quote?.suffix.orEmpty(),
            contextBefore = context.before,
            contextAfter = context.after,
            chapterTitle = chapterTitle,
            note = note,
            locatorJson = null,
            source = runCatching { AnnotationSource.valueOf(source.uppercase()) }.getOrDefault(AnnotationSource.IMPORT),
            sourceRef = sourceRef,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun selector(type: String) = target.selectors.firstOrNull { it.type == type }
}
