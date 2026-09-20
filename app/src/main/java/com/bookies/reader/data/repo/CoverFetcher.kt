package com.bookies.reader.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads a remote cover into [FileStore], which is where `BookEntity.coverPath` points.
 *
 * [OpenLibrary] deliberately returns a remote URL and stops there, so that the part with
 * the parsing in it stays pure and testable. This is the other half: the one file write
 * that turns a URL into something the shelf can draw.
 *
 * A failure is not an error. `BookCover` falls back to the title on a plain board when
 * there is no artwork, so a cover that will not download costs a nice-looking shelf cell
 * and nothing else — certainly not the book.
 */
class CoverFetcher(
    private val files: FileStore,
    private val http: OkHttpClient = defaultClient()
) {

    /**
     * @param key what to file the image under; for a paper book, its ISBN. Sharing
     *   [FileStore]'s cover directory with the EPUBs is safe because an EPUB's key is a
     *   SHA-256 and an ISBN cannot collide with one.
     * @return the local path, or null if anything at all went wrong.
     */
    suspend fun download(url: String, key: String): String? = withContext(Dispatchers.IO) {
        val target = files.coverFile(key)
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@withContext null

                // Open Library answers 200 with a zero-length body for an ISBN it has no
                // artwork for, and a zero-byte file is worse than no file: Coil would fail
                // on it for ever after, while a null coverPath draws the title board.
                val bytes = body.bytes()
                if (bytes.isEmpty()) return@withContext null
                target.writeBytes(bytes)
            }
            target.absolutePath
        }.getOrElse {
            // A half-written file would be indistinguishable from a good one next launch.
            target.delete()
            null
        }
    }

    private companion object {
        const val USER_AGENT = "Bookies/1.0 (personal EPUB reader)"

        fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
