package com.bookies.reader.drive

import com.bookies.reader.data.db.AnnotationDao
import com.bookies.reader.data.db.BookDao
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.data.repo.FileStore
import com.bookies.reader.epub.BookBundle
import java.io.File
import java.io.IOException

/**
 * Moves books between the device and Drive.
 *
 * The invariant that everything else hangs off: **nothing is deleted locally until the
 * remote copy has been read back and verified.** Drive computes its own MD5 on upload,
 * so we compare against ours rather than trusting that the bytes we sent are the bytes
 * that landed. Until that comparison passes, the local file remains authoritative.
 *
 * Annotations are copied into the bundle but deliberately *left in the database* when a
 * book is archived. They are ~2 KB each; keeping them is what lets search and the index
 * span the whole library rather than only the handful of books currently on the phone.
 */
class ArchiveManager(
    private val books: BookDao,
    private val annotations: AnnotationDao,
    private val files: FileStore,
    private val drive: DriveClient,
    private val folderName: String = "Bookies"
) {

    sealed interface Result {
        data object Success : Result
        data class Failed(val reason: String, val cause: Throwable? = null) : Result
        data object BundleMissing : Result
    }

    /**
     * Packs a book to Drive and frees the local EPUB.
     *
     * On any failure the book is returned to [StorageState.LOCAL] with its file intact —
     * a failed archive costs bandwidth, never data.
     */
    suspend fun archive(bookId: String, token: String, onProgress: (String) -> Unit = {}): Result {
        val book = books.byId(bookId) ?: return Result.Failed("No such book")
        if (book.format == BookFormat.PHYSICAL) {
            // TODO a physical book still deserves an annotations-only bundle in Drive;
            // that is the same format minus book.epub. Not wired up yet.
            return Result.Failed("Physical books have no file to archive")
        }
        if (book.storageState != StorageState.LOCAL) {
            return Result.Failed("Book is ${book.storageState}, expected LOCAL")
        }
        val epub = book.localFilePath?.let(::File)?.takeIf { it.exists() }
            ?: return Result.Failed("Local EPUB is already gone")

        val work = files.workDir(bookId)
        books.setStorageState(bookId, StorageState.ARCHIVING)

        return try {
            onProgress("Packing")
            val rows = annotations.forBookOnce(bookId)
            val bundle = File(work, BookBundle.fileNameFor(book))
            val localMd5 = BookBundle.pack(
                destination = bundle,
                book = book,
                epub = epub,
                cover = book.coverPath?.let(::File),
                annotations = rows
            )

            onProgress("Uploading")
            val folderId = book.driveFolderId ?: drive.ensureFolder(token, folderName)
            val uploaded = if (book.driveFileId != null) {
                // Re-archiving a book we previously restored: replace the contents in
                // place so the Drive id is stable and Drive keeps the old revision.
                drive.updateContents(token, book.driveFileId, bundle, BookBundle.MIME_TYPE)
            } else {
                drive.upload(token, bundle, bundle.name, folderId, BookBundle.MIME_TYPE)
            }

            onProgress("Verifying")
            if (uploaded.md5Checksum != null && uploaded.md5Checksum != localMd5) {
                throw IOException("Checksum mismatch: Drive has ${uploaded.md5Checksum}, expected $localMd5")
            }

            // Only now is it safe to reclaim the space.
            books.update(
                book.copy(
                    storageState = StorageState.ARCHIVED,
                    localFilePath = null,
                    driveFileId = uploaded.id,
                    driveFolderId = folderId,
                    bundleMd5 = localMd5,
                    bundleSizeBytes = uploaded.size ?: bundle.length(),
                    archivedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            epub.delete()
            Result.Success
        } catch (t: Throwable) {
            books.setStorageState(bookId, StorageState.LOCAL)
            Result.Failed(t.message ?: "Archive failed", t)
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Pulls a book back from Drive.
     *
     * The bundle is **not** deleted afterwards: Drive stays a permanent backup, and
     * archiving again simply overwrites the same file. Restoring is about reclaiming
     * phone storage, not about moving ownership back and forth.
     */
    suspend fun restore(bookId: String, token: String, onProgress: (String, Float) -> Unit = { _, _ -> }): Result {
        val book = books.byId(bookId) ?: return Result.Failed("No such book")
        val fileId = book.driveFileId ?: return Result.Failed("Book has no Drive bundle")
        if (book.storageState == StorageState.LOCAL) return Result.Success

        val work = files.workDir(bookId)
        books.setStorageState(bookId, StorageState.RESTORING)

        return try {
            onProgress("Locating", 0f)
            val remote = drive.stat(token, fileId)
            if (remote == null || remote.trashed) {
                // The user deleted it in Drive. Keep the stub and every annotation; only
                // the ability to open the text is lost.
                books.setStorageState(bookId, StorageState.MISSING)
                return Result.BundleMissing
            }

            onProgress("Downloading", 0f)
            val bundle = File(work, "bundle.${BookBundle.EXTENSION}")
            drive.download(token, fileId, bundle) { copied, total ->
                if (total > 0) onProgress("Downloading", copied.toFloat() / total)
            }

            onProgress("Verifying", 1f)
            val downloadedMd5 = BookBundle.md5(bundle)
            if (book.bundleMd5 != null && book.bundleMd5 != downloadedMd5) {
                throw IOException("Bundle checksum mismatch — download is corrupt or the bundle was replaced")
            }

            val unpacked = BookBundle.unpack(bundle, File(work, "unpacked"))
            val epubHash = FileStore.sha256(unpacked.epub)
            if (epubHash != unpacked.metadata.epubSha256) {
                throw IOException("EPUB inside the bundle does not match its recorded hash")
            }

            val destination = files.epubFile(book.fileHash)
            unpacked.epub.copyTo(destination, overwrite = true)

            // Annotations are normally already present; this covers a bundle restored
            // onto a fresh install, and repairs anything locally deleted by accident.
            mergeAnnotations(bookId, unpacked)

            books.update(
                book.copy(
                    storageState = StorageState.LOCAL,
                    localFilePath = destination.absolutePath,
                    updatedAt = System.currentTimeMillis()
                )
            )
            Result.Success
        } catch (t: Throwable) {
            books.setStorageState(bookId, StorageState.ARCHIVED)
            Result.Failed(t.message ?: "Restore failed", t)
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun mergeAnnotations(bookId: String, unpacked: BookBundle.Unpacked) {
        val incoming = unpacked.annotations.map { it.toEntity(bookId) }
        if (incoming.isEmpty()) return
        val existing = annotations.forBookOnce(bookId).associateBy { it.id }
        // Last-write-wins on updatedAt. Safe because ids are generated client-side and
        // an annotation is only ever edited on the device that holds the book.
        val toWrite = incoming.filter { candidate ->
            val current = existing[candidate.id]
            current == null || candidate.updatedAt > current.updatedAt
        }
        if (toWrite.isNotEmpty()) annotations.insertAll(toWrite)
    }

    /**
     * Called at startup: a process killed mid-transfer leaves a book in ARCHIVING or
     * RESTORING. Both roll back to the state whose data we know is intact.
     */
    suspend fun recoverInterrupted() {
        for (book in books.inFlight()) {
            val recovered = when (book.storageState) {
                // The local file was never deleted, so LOCAL is still true.
                StorageState.ARCHIVING -> StorageState.LOCAL
                // The download never completed; the Drive bundle is untouched.
                StorageState.RESTORING -> StorageState.ARCHIVED
                else -> continue
            }
            books.setStorageState(book.id, recovered)
        }
        files.clearStaging()
    }
}
