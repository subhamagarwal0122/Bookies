package com.bookies.reader.data.repo

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Everything Bookies writes lives in app-private storage, which means no permissions and
 * no file-manager clutter — but also that uninstalling wipes it. That is precisely why
 * the Drive bundle and the Markdown export are not optional extras.
 */
class FileStore(context: Context) {

    private val root: File = context.filesDir

    val epubs = File(root, "epubs").apply { mkdirs() }
    val covers = File(root, "covers").apply { mkdirs() }
    val staging = File(context.cacheDir, "staging").apply { mkdirs() }

    fun epubFile(hash: String) = File(epubs, "$hash.epub")
    fun coverFile(hash: String) = File(covers, "$hash.jpg")

    /** Scratch space for a single archive/restore run; caller deletes it when done. */
    fun workDir(bookId: String) = File(staging, bookId).apply { mkdirs() }

    fun clearStaging() = staging.listFiles()?.forEach { it.deleteRecursively() }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
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
    }
}
