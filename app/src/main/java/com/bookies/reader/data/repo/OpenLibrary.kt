package com.bookies.reader.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ISBN → book metadata, for adding a paper book by scanning its barcode.
 *
 * Open Library's `jscmd=data` endpoint needs no key, no account and no attribution header,
 * which is the whole reason it is used here: this app has no billing account and no server.
 *
 * The split below is deliberate. [parse] is a pure function from a response body to a
 * [Result], and [normaliseIsbn] is pure arithmetic, so the only part that cannot be unit
 * tested without a device is the six-line [Fetcher] implementation. Everything with a
 * decision in it is testable on the JVM.
 */
class OpenLibrary(
    private val fetcher: Fetcher = OkHttpFetcher(),
    private val json: Json = defaultJson()
) {

    /**
     * The one impure seam. Implemented over OkHttp in production and faked in tests;
     * an implementation may block, because [lookup] only ever calls it off the main thread.
     */
    fun interface Fetcher {
        /** @throws IOException on any transport or non-2xx failure. */
        fun get(url: String): String
    }

    /** What the add-a-book screen has to tell the user apart. */
    sealed interface Result {
        data class Found(val book: Metadata) : Result

        /** A well-formed ISBN that Open Library simply has no record of — offer manual entry. */
        data object NotFound : Result

        /**
         * The scan or the typing was wrong. Distinct from [NotFound] because the remedy is
         * different ("rescan") and because we detect it before spending a request.
         */
        data class InvalidIsbn(val input: String) : Result

        /** Transport or parse failure — the only case where "try again" is honest advice. */
        data class Error(val reason: String, val cause: Throwable? = null) : Result
    }

    /**
     * Shaped to feed [PhysicalBooks.add] directly: [authors] is already joined the way
     * `BookEntity.authors` stores it, and [pageCount] is already an `Int?`.
     */
    data class Metadata(
        /** Normalised, so it matches what gets written to `BookEntity.isbn`. */
        val isbn: String,
        val title: String,
        val authors: String,
        val pageCount: Int?,
        /**
         * A *remote* URL. `PhysicalBooks.add` wants a local `coverPath`, so whoever calls
         * this must download the image through [FileStore] first; fetching it here would
         * put a file write behind a function whose whole value is being pure.
         */
        val coverUrl: String?,
        val publishDate: String?,
        val publisher: String?
    )

    /**
     * Resolves an ISBN, normalising and check-digit-validating it first.
     *
     * A mis-scanned barcode is the common failure, not a missing record, so it is rejected
     * locally: no request, no waiting on a timeout to be told what arithmetic already knew.
     */
    suspend fun lookup(rawIsbn: String): Result {
        val isbn = normaliseIsbn(rawIsbn) ?: return Result.InvalidIsbn(rawIsbn)
        val body = try {
            withContext(Dispatchers.IO) { fetcher.get(urlFor(isbn)) }
        } catch (e: IOException) {
            return Result.Error("Could not reach Open Library", e)
        }
        return parse(isbn, body)
    }

    /**
     * Pure: response body → [Result]. No network, no Android, no OkHttp.
     *
     * [isbn] is the normalised key we asked for; it is carried into [Metadata] rather than
     * read back out of the response, which reports its own identifiers inconsistently
     * (an ISBN-13 query can come back with only `isbn_10` populated).
     */
    fun parse(isbn: String, body: String): Result {
        val records = try {
            json.decodeFromString<Map<String, OlRecord>>(body)
        } catch (e: Exception) {
            return Result.Error("Unreadable response from Open Library", e)
        }

        // An unknown ISBN is `{}` — a successful, empty answer, not a failure.
        val record = records["ISBN:$isbn"]
        // Fall back to the sole entry: the endpoint echoes the bibkey we sent, but nothing
        // in its contract promises the exact spelling back, and we only ever ask for one.
            ?: records.values.singleOrNull()
            ?: return Result.NotFound

        // Everything else is optional, but a book with no title is not something the shelf
        // can render, and silently inventing one would be worse than making the user type it.
        val title = record.title?.takeIf { it.isNotBlank() }
            ?: return Result.Error("Open Library record has no title")

        return Result.Found(
            Metadata(
                isbn = isbn,
                title = title,
                authors = record.authors.mapNotNull { it.name?.takeIf(String::isNotBlank) }
                    .joinToString("; "),
                pageCount = record.numberOfPages?.takeIf { it > 0 },
                // Largest available: it is the cover of a shelf, and Open Library's -S is tiny.
                coverUrl = record.cover?.let { it.large ?: it.medium ?: it.small },
                publishDate = record.publishDate?.takeIf { it.isNotBlank() },
                publisher = record.publishers.firstNotNullOfOrNull {
                    it.name?.takeIf(String::isNotBlank)
                }
            )
        )
    }

    companion object {
        private const val ENDPOINT = "https://openlibrary.org/api/books"

        fun urlFor(isbn: String) = "$ENDPOINT?bibkeys=ISBN:$isbn&format=json&jscmd=data"

        /** Open Library returns a great deal we do not want; without this, real responses throw. */
        fun defaultJson() = Json { ignoreUnknownKeys = true }

        /**
         * Strips separators, uppercases a trailing `x`, and verifies the check digit.
         *
         * Returns null for anything that is not a valid ISBN-10 or ISBN-13. The check digit
         * is what makes a single mis-read barcode digit detectable, which is exactly the
         * error a phone camera makes.
         */
        fun normaliseIsbn(raw: String): String? {
            val cleaned = raw.filterNot { it == '-' || it == ' ' || it == ' ' }.uppercase()
            return when {
                cleaned.length == 13 && cleaned.all(Char::isDigit) && isbn13Valid(cleaned) -> cleaned
                cleaned.length == 10 && isbn10Valid(cleaned) -> cleaned
                else -> null
            }
        }

        /** Weights alternate 1, 3; the total must be a multiple of 10. */
        private fun isbn13Valid(isbn: String): Boolean {
            // A real ISBN-13 is a Bookland EAN. Other EAN-13s (a magazine, a cereal box)
            // pass the checksum, so prefixes outside 978/979 are rejected on sight.
            if (!isbn.startsWith("978") && !isbn.startsWith("979")) return false
            val sum = isbn.mapIndexed { i, c ->
                (c - '0') * if (i % 2 == 0) 1 else 3
            }.sum()
            return sum % 10 == 0
        }

        /** Weights count down 10..1, 'X' is 10 in the final position only; total mod 11 == 0. */
        private fun isbn10Valid(isbn: String): Boolean {
            var sum = 0
            for ((i, c) in isbn.withIndex()) {
                val value = when {
                    c.isDigit() -> c - '0'
                    c == 'X' && i == 9 -> 10
                    else -> return false
                }
                sum += value * (10 - i)
            }
            return sum % 11 == 0
        }
    }

    /**
     * The production [Fetcher]. Same shape as [com.bookies.reader.drive.DriveClient]: one
     * OkHttp client owned here, explicit timeouts, non-2xx raised as [IOException].
     */
    class OkHttpFetcher(
        private val http: OkHttpClient = defaultClient()
    ) : Fetcher {

        override fun get(url: String): String {
            val request = Request.Builder()
                .url(url)
                // Open Library asks unauthenticated clients to identify themselves so it
                // can contact whoever is misbehaving instead of blanket-blocking.
                .header("User-Agent", USER_AGENT)
                .build()
            return http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Open Library lookup failed: ${response.code}")
                }
                body
            }
        }

        companion object {
            private const val USER_AGENT = "Bookies/1.0 (personal EPUB reader)"

            // Short: this runs while the user is holding a book up to the camera, and
            // falling back to manual entry quickly beats a long hang.
            private fun defaultClient() = OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}

// --- wire shapes ---------------------------------------------------------------------
// Only the fields the shelf uses. Every one is optional: Open Library's records are
// crowd-sourced and any of them can be missing from an otherwise good record.

@Serializable
internal data class OlRecord(
    val title: String? = null,
    val authors: List<OlNamed> = emptyList(),
    @SerialName("number_of_pages") val numberOfPages: Int? = null,
    val cover: OlCover? = null,
    @SerialName("publish_date") val publishDate: String? = null,
    /** Objects with a `name`, not bare strings — same shape as `authors`. */
    val publishers: List<OlNamed> = emptyList()
)

@Serializable
internal data class OlNamed(val name: String? = null)

@Serializable
internal data class OlCover(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null
)
