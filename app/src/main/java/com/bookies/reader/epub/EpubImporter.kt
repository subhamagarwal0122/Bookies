package com.bookies.reader.epub

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.bookies.reader.data.db.BookDao
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.data.repo.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Brings an EPUB into the library, from the SAF picker or a share/open intent.
 *
 * Metadata is read with a small hand-rolled OPF parser rather than through Readium. The
 * import path runs before anything else works, and a dependency-free parser here means
 * import cannot break when the toolkit version moves. It reads title, authors, the
 * package identifier and the cover — nothing else is needed at this stage.
 */
class EpubImporter(
    private val books: BookDao,
    private val files: FileStore
) {

    sealed interface Outcome {
        data class Imported(val book: BookEntity) : Outcome
        /** Same file hash: the book is already on the shelf, annotations intact. */
        data class AlreadyPresent(val book: BookEntity) : Outcome
        /**
         * Same dc:identifier, different bytes — a different build of a book we know.
         * The caller should offer to re-attach the existing annotations, which will be
         * re-anchored by quote text.
         */
        data class EditionConflict(val existing: BookEntity, val incoming: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun import(resolver: ContentResolver, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val staged = File(files.staging, "import-${UUID.randomUUID()}.epub")
        try {
            resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().buffered().use { input.copyTo(it) }
            } ?: return@withContext Outcome.Failed("Could not read the selected file")

            val hash = FileStore.sha256(staged)
            books.byHash(hash)?.let { return@withContext Outcome.AlreadyPresent(it) }

            val metadata = readMetadata(staged)
                ?: return@withContext Outcome.Failed("Not a valid EPUB, or it is DRM-protected")

            metadata.identifier
                ?.let { books.byOpfIdentifier(it) }
                ?.let { return@withContext Outcome.EditionConflict(it, staged) }

            val destination = files.epubFile(hash)
            staged.copyTo(destination, overwrite = true)

            val cover = metadata.coverBytes?.let { bytes ->
                files.coverFile(hash).also { it.writeBytes(bytes) }
            }

            val now = System.currentTimeMillis()
            val book = BookEntity(
                id = UUID.randomUUID().toString(),
                format = BookFormat.EPUB,
                title = metadata.title,
                authors = metadata.authors.joinToString("; "),
                coverPath = cover?.absolutePath,
                fileHash = hash,
                opfIdentifier = metadata.identifier,
                localFilePath = destination.absolutePath,
                storageState = StorageState.LOCAL,
                addedAt = now,
                updatedAt = now
            )
            books.upsert(book)
            Outcome.Imported(book)
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: "Import failed")
        } finally {
            staged.delete()
        }
    }

    // --- minimal OPF reading -------------------------------------------------

    data class EpubMetadata(
        val title: String,
        val authors: List<String>,
        val identifier: String?,
        val coverBytes: ByteArray?
    )

    fun readMetadata(epub: File): EpubMetadata? = runCatching {
        ZipFile(epub).use { zip ->
            // container.xml is the only entry whose path the spec fixes; it points at the OPF.
            val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
            val opfPath = zip.getInputStream(containerEntry).use { findRootfilePath(it) } ?: return null
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opfDir = opfPath.substringBeforeLast('/', "")

            var title = epub.nameWithoutExtension
            val authors = mutableListOf<String>()
            var identifier: String? = null
            var coverId: String? = null
            var coverHref: String? = null
            val manifest = mutableMapOf<String, Pair<String, String?>>() // id -> href, properties

            zip.getInputStream(opfEntry).use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(input, null)

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> parser.nextText().takeIf { it.isNotBlank() }?.let { title = it }
                            "creator" -> parser.nextText().takeIf { it.isNotBlank() }?.let { authors += it }
                            "identifier" -> if (identifier == null) identifier = parser.nextText().takeIf { it.isNotBlank() }
                            // EPUB 2 records the cover as <meta name="cover" content="<id>">.
                            "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val properties = parser.getAttributeValue(null, "properties")
                                if (id != null && href != null) manifest[id] = href to properties
                                // EPUB 3 marks it with properties="cover-image".
                                if (properties?.contains("cover-image") == true) coverHref = href
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            val resolvedCover = coverHref ?: coverId?.let { manifest[it]?.first }
            val coverBytes = resolvedCover?.let { href ->
                val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
            }

            EpubMetadata(title, authors, identifier, coverBytes)
        }
    }.getOrNull()

    private fun findRootfilePath(input: java.io.InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            event = parser.next()
        }
        return null
    }
}
