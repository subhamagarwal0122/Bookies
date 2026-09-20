package com.bookies.reader.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A minimal Google Drive v3 client — four operations, spoken directly over REST.
 *
 * Deliberately not using google-api-services-drive: that library is enormous, pulls in
 * dated transitive dependencies, and fights R8. We need create, update, stat and
 * download, which is about 150 lines by hand.
 *
 * Every call needs a `drive.file`-scoped access token from [DriveAuth].
 */
class DriveClient(
    private val http: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /** Drive's own limit for simple multipart upload; above this we must go resumable. */
        const val MULTIPART_LIMIT_BYTES = 5L * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            // Generous: a bundle upload on a poor connection is still better than a retry.
            .callTimeout(10, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    data class DriveFile(
        val id: String,
        val name: String,
        val md5Checksum: String?,
        val size: Long?,
        val trashed: Boolean = false
    )

    /**
     * Finds the app's archive folder by name, creating it if absent.
     *
     * Note the `drive.file` consequence: this only ever finds a folder *this app*
     * created. If the user deletes it in Drive, we transparently make a new one — which
     * is the right behaviour, but means an old folder full of bundles can be orphaned.
     * Store the returned id and reuse it.
     */
    suspend fun ensureFolder(token: String, name: String, parentId: String? = null): String =
        withContext(Dispatchers.IO) {
            val query = buildString {
                append("mimeType='").append(FOLDER_MIME).append("'")
                append(" and name='").append(name.replace("'", "\\'")).append("'")
                append(" and trashed=false")
                parentId?.let { append(" and '").append(it).append("' in parents") }
            }
            val url = FILES.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id,name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val existing = json.parseToJsonElement(get(token, url.toString()))
                .jsonObject["files"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("id")?.jsonPrimitive?.content
            existing ?: createFolder(token, name, parentId)
        }

    private suspend fun createFolder(token: String, name: String, parentId: String?): String =
        withContext(Dispatchers.IO) {
            val metadata = buildString {
                append("""{"name":${name.jsonString()},"mimeType":"$FOLDER_MIME"""")
                parentId?.let { append(""","parents":["$it"]""") }
                append("}")
            }
            val request = Request.Builder()
                .url("$FILES?fields=id")
                .header("Authorization", "Bearer $token")
                .post(metadata.toRequestBody("application/json".toMediaType()))
                .build()
            json.parseToJsonElement(execute(request)).jsonObject["id"]!!.jsonPrimitive.content
        }

    /**
     * Uploads a new file and returns its Drive metadata, including the server-computed
     * md5 that the caller must compare against the local bundle before deleting anything.
     */
    suspend fun upload(token: String, file: File, name: String, parentId: String, mimeType: String): DriveFile =
        withContext(Dispatchers.IO) {
            require(file.length() <= MULTIPART_LIMIT_BYTES) {
                "File exceeds Drive's multipart limit; use resumable upload (see TODO in ArchiveManager)"
            }
            val metadata = """{"name":${name.jsonString()},"parents":["$parentId"]}"""
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(file.asRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$UPLOAD?uploadType=multipart&fields=id,name,md5Checksum,size,trashed")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            parseFile(execute(request))
        }

    /** Replaces the contents of an existing bundle, keeping its Drive id and revision history. */
    suspend fun updateContents(token: String, fileId: String, file: File, mimeType: String): DriveFile =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$UPLOAD/$fileId?uploadType=media&fields=id,name,md5Checksum,size,trashed")
                .header("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mimeType.toMediaType()))
                .build()
            parseFile(execute(request))
        }

    /** Returns null when the bundle has been deleted from Drive by hand. */
    suspend fun stat(token: String, fileId: String): DriveFile? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES/$fileId?fields=id,name,md5Checksum,size,trashed")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Drive stat failed: ${response.code} ${response.message}")
                else -> parseFile(response.body!!.string())
            }
        }
    }

    suspend fun download(token: String, fileId: String, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$FILES/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Drive download failed: ${response.code}")
                val total = response.body!!.contentLength()
                var copied = 0L
                destination.outputStream().buffered().use { out ->
                    response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
            }
        }

    private fun parseFile(body: String): DriveFile {
        val o: JsonObject = json.parseToJsonElement(body).jsonObject
        return DriveFile(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content.orEmpty(),
            md5Checksum = o["md5Checksum"]?.jsonPrimitive?.content,
            size = o["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            trashed = o["trashed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    private fun get(token: String, url: String): String =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build())

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Drive ${request.method} failed: ${response.code} $body")
            body
        }

    private fun String.jsonString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
