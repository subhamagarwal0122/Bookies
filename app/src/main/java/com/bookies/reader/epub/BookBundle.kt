package com.bookies.reader.epub

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.ReadingStatus
import com.bookies.reader.export.MarkdownExporter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The archive format: one self-contained zip per book.
 *
 *   book.epub          the original file, byte for byte
 *   annotations.json   machine-readable, W3C-shaped
 *   annotations.md     human-readable, for when you open this on a laptop
 *   metadata.json      title, authors, identifier, hash, reading status, progression
 *   cover.jpg          so a bundle can rebuild a shelf entry from nothing
 *
 * One file means one Drive id, one upload, one download, and no way to end up with a
 * half-restored book or an orphaned sidecar.
 */
object BookBundle {

    const val EXTENSION = "bookies"
    const val MIME_TYPE = "application/zip"

    private const val ENTRY_EPUB = "book.epub"
    private const val ENTRY_ANNOTATIONS_JSON = "annotations.json"
    private const val ENTRY_ANNOTATIONS_MD = "annotations.md"
    private const val ENTRY_METADATA = "metadata.json"
    private const val ENTRY_COVER = "cover.jpg"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Bundle filename as it will appear in the user's Drive folder. */
    fun fileNameFor(book: BookEntity): String {
        val stem = buildString {
            append(book.title)
            if (book.authors.isNotBlank()) append(" — ").append(book.authors)
        }
        return "${sanitize(stem)}.$EXTENSION"
    }

    /**
     * Writes a bundle to [destination] and returns its MD5, which the caller must verify
     * against Drive before deleting anything locally.
     */
    fun pack(
        destination: File,
        book: BookEntity,
        epub: File,
        cover: File?,
        annotations: List<AnnotationEntity>
    ): String {
        ZipOutputStream(FileOutputStream(destination).buffered()).use { zip ->
            // An EPUB is itself a zip, so deflating again buys almost nothing — but
            // STORED would require computing size and CRC up front, which costs an extra
            // pass over the file. Default compression is the cheaper trade here.
            zip.putEntry(ENTRY_EPUB) { out -> epub.inputStream().use { it.copyTo(out) } }

            zip.putEntry(ENTRY_ANNOTATIONS_JSON) { out ->
                out.write(json.encodeToString(annotations.map(BundledAnnotation::from)).toByteArray())
            }
            zip.putEntry(ENTRY_ANNOTATIONS_MD) { out ->
                out.write(MarkdownExporter.render(book, annotations).toByteArray())
            }
            zip.putEntry(ENTRY_METADATA) { out ->
                out.write(json.encodeToString(BundleMetadata.from(book, annotations.size)).toByteArray())
            }
            cover?.takeIf { it.exists() }?.let { c ->
                zip.putEntry(ENTRY_COVER) { out -> c.inputStream().use { it.copyTo(out) } }
            }
        }
        return md5(destination)
    }

    data class Unpacked(
        val metadata: BundleMetadata,
        val epub: File,
        val cover: File?,
        val annotations: List<BundledAnnotation>
    )

    /** Extracts a bundle into [workDir]. Caller owns cleanup of [workDir]. */
    fun unpack(bundle: File, workDir: File): Unpacked {
        workDir.mkdirs()
        var metadata: BundleMetadata? = null
        var annotations: List<BundledAnnotation> = emptyList()
        var epub: File? = null
        var cover: File? = null

        ZipInputStream(bundle.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY_EPUB -> epub = File(workDir, ENTRY_EPUB).also { it.writeFrom(zip) }
                    ENTRY_COVER -> cover = File(workDir, ENTRY_COVER).also { it.writeFrom(zip) }
                    ENTRY_METADATA -> metadata = json.decodeFromString(zip.readBytes().decodeToString())
                    ENTRY_ANNOTATIONS_JSON -> annotations = json.decodeFromString(zip.readBytes().decodeToString())
                    // annotations.md is derived; ignored on the way back in.
                }
                zip.closeEntry()
            }
        }

        return Unpacked(
            metadata = requireNotNull(metadata) { "bundle has no $ENTRY_METADATA" },
            epub = requireNotNull(epub) { "bundle has no $ENTRY_EPUB" },
            cover = cover,
            annotations = annotations
        )
    }

    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim().take(120)

    private inline fun ZipOutputStream.putEntry(name: String, write: (ZipOutputStream) -> Unit) {
        putNextEntry(ZipEntry(name))
        write(this)
        closeEntry()
    }

    private fun File.writeFrom(zip: ZipInputStream) {
        outputStream().buffered().use { zip.copyTo(it) }
    }
}
