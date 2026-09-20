package com.bookies.reader.export

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bookies.reader.data.db.BookEntity
import java.io.File

/**
 * Gets a rendered export out of the app.
 *
 * Everything Bookies writes is app-private, which is the whole reason this exists:
 * rendering Markdown into a StateFlow is not an export, it is a string nobody can reach.
 * The share sheet is the destination that needs no permission and no new dependency —
 * Drive, Obsidian, mail and "save to Files" are all already on it.
 */
object ExportShare {

    /**
     * text/plain, not text/markdown, and the ".md" lives in the file name instead.
     * Hardly anything on Android declares an intent filter for text/markdown, so the
     * more accurate type produces a nearly empty chooser — an export that appears to
     * have nowhere to go. Mail, Drive, Obsidian and Files all take text/plain.
     */
    private const val MIME_TYPE = "text/plain"

    /** @return null when the chooser was shown, otherwise a reason to put in front of the reader. */
    fun share(context: Context, book: BookEntity, markdown: String): String? {
        val file = try {
            write(context, book, markdown)
        } catch (t: Throwable) {
            return "Could not write the export: ${t.message ?: "unknown error"}"
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: IllegalArgumentException) {
            // Thrown when the file sits outside every path declared in res/xml/file_paths.xml,
            // which is a wiring mistake rather than anything the reader did.
            return "Export path is not shareable: ${t.message ?: file.name}"
        }

        val fileName = ExportNaming.fileName(book)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            // Subject for mail, title for the chooser's own preview row.
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            putExtra(Intent.EXTRA_TITLE, fileName)
            // The uri also goes in the ClipData: that is what the platform reads when it
            // propagates the read grant to whichever target the chooser picks.
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(send, "Export “${book.title}”").apply {
            // The grant has to ride on the chooser too, or the target it launches gets a
            // uri it is not permitted to open.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Composition is normally hosted by the activity, but a non-activity context
            // cannot start one without its own task.
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(chooser)
            null
        } catch (e: ActivityNotFoundException) {
            "Nothing on this device can receive a file"
        }
    }

    /**
     * Writes into the cache rather than filesDir: once the chooser's target has copied
     * the bytes the file is dead weight, and the system is free to reclaim it. Replacing
     * the previous export of the same book is deliberate — one stale copy per book at
     * worst, instead of an ever-growing pile.
     */
    private fun write(context: Context, book: BookEntity, markdown: String): File {
        val dir = File(File(context.cacheDir, "exports"), ExportNaming.directoryName(book))
        dir.mkdirs()
        return File(dir, ExportNaming.fileName(book)).apply { writeText(markdown) }
    }
}
