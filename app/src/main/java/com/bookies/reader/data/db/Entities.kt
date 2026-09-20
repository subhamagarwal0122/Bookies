package com.bookies.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.ReadingStatus
import com.bookies.reader.data.model.StorageState

/**
 * A book. Survives archiving: when the EPUB goes to Drive this row and the cover stay,
 * so the shelf looks identical whether or not the file is on the device.
 */
@Entity(
    tableName = "books",
    indices = [Index("fileHash"), Index("storageState"), Index("lastOpenedAt"), Index("isbn")]
)
data class BookEntity(
    @PrimaryKey val id: String,                 // UUID, generated client-side

    val format: BookFormat = BookFormat.EPUB,

    val title: String,
    val authors: String,                        // joined with "; " — one reader, no need to normalise
    val coverPath: String?,                     // always local, even when ARCHIVED

    /** Physical books only: scanned from the barcode, resolves cover and metadata. */
    val isbn: String? = null,
    /** Physical books only: lets a page number be expressed as a progression. */
    val pageCount: Int? = null,

    /** SHA-256 of the original EPUB. Empty for a physical book. */
    val fileHash: String,
    /** dc:identifier from the OPF (often an ISBN/UUID): soft identity across editions. */
    val opfIdentifier: String?,

    val localFilePath: String?,                 // null while ARCHIVED
    val storageState: StorageState = StorageState.LOCAL,
    val readingStatus: ReadingStatus = ReadingStatus.UNREAD,

    val progression: Double = 0.0,              // 0.0–1.0 through the whole book
    val lastLocatorJson: String? = null,        // Readium Locator for "resume where I left off"

    // --- Drive archive ---
    val driveFileId: String? = null,
    val driveFolderId: String? = null,
    val bundleMd5: String? = null,              // verified against Drive before local delete
    val bundleSizeBytes: Long? = null,
    val archivedAt: Long? = null,

    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null                 // tombstone, never hard-delete
)

/**
 * An annotation. Kept locally even when its book is archived, so search and the index
 * cover the entire library. A copy also travels inside the Drive bundle.
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        // The index screen reads in reading order; this covers that query entirely.
        Index(value = ["bookId", "href", "progression"]),
        Index("updatedAt")
    ]
)
data class AnnotationEntity(
    @PrimaryKey val id: String,                 // UUID, generated client-side
    val bookId: String,

    val type: AnnotationType,
    val colorArgb: Int? = null,

    // --- anchor (flattened from Anchor so it is queryable) ---
    /** Spine item path. Null for a physical book, which has no spine. */
    val href: String?,
    val cssSelector: String?,
    val startOffset: Int?,
    val endOffset: Int?,

    /**
     * Position in the book, 0.0–1.0.
     *
     * For an EPUB this comes from the reader. For a physical book it is derived as
     * pageNumber / pageCount. Deriving it rather than special-casing is what lets the
     * index screen, the ordering index and every export treat both kinds the same.
     */
    val progression: Double,

    /** Physical books only: the real page number, because paper genuinely has them. */
    val pageNumber: Int? = null,

    val quote: String,
    val prefix: String,
    val suffix: String,

    /** Full surrounding paragraphs — the standalone-readability guarantee. */
    val contextBefore: String,
    val contextAfter: String,

    val chapterTitle: String?,
    val note: String?,

    /** Readium's own Locator JSON, kept verbatim for a lossless round-trip. */
    val locatorJson: String?,

    val source: AnnotationSource = AnnotationSource.ANDROID,
    /** e.g. a Kindle location number — provenance we cannot otherwise express. */
    val sourceRef: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

/**
 * Full-text search over annotations.
 *
 * FTS4 rather than FTS5: Room has no @Fts5 annotation, and FTS5 is not guaranteed to be
 * compiled into the system SQLite on every device. FTS4 covers prefix and phrase search
 * fine; the only real loss is bm25 ranking. Room maintains the sync triggers for us
 * because this is an external-content table.
 */
@Fts4(contentEntity = AnnotationEntity::class)
@Entity(tableName = "annotations_fts")
data class AnnotationFts(
    val quote: String,
    val note: String?,
    val contextBefore: String,
    val contextAfter: String
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "annotation_tags",
    primaryKeys = ["annotationId", "tagId"],
    indices = [Index("tagId")]
)
data class AnnotationTagCrossRef(
    val annotationId: String,
    val tagId: String
)
