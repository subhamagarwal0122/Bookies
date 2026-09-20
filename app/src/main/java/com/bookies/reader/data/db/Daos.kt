package com.bookies.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.bookies.reader.data.model.StorageState
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE deletedAt IS NULL ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun shelf(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun byId(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE fileHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun byHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE isbn = :isbn AND deletedAt IS NULL LIMIT 1")
    suspend fun byIsbn(isbn: String): BookEntity?

    /** Soft identity: lets a re-downloaded edition find its old annotations. */
    @Query("SELECT * FROM books WHERE opfIdentifier = :identifier AND deletedAt IS NULL LIMIT 1")
    suspend fun byOpfIdentifier(identifier: String): BookEntity?

    /** Transfers interrupted by process death, found on next launch. */
    @Query("SELECT * FROM books WHERE storageState IN ('ARCHIVING','RESTORING')")
    suspend fun inFlight(): List<BookEntity>

    @Upsert suspend fun upsert(book: BookEntity)
    @Update suspend fun update(book: BookEntity)

    @Query("UPDATE books SET storageState = :state, updatedAt = :now WHERE id = :id")
    suspend fun setStorageState(id: String, state: StorageState, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET progression = :progression, lastLocatorJson = :locatorJson, lastOpenedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun saveProgress(id: String, progression: Double, locatorJson: String?, now: Long = System.currentTimeMillis())
}

@Dao
interface AnnotationDao {

    /**
     * The index screen, in reading order. Chapter grouping happens in the UI from
     * chapterTitle; the ordering is served entirely by the (bookId, href, progression) index.
     */
    @Query("""
        SELECT * FROM annotations
        WHERE bookId = :bookId AND deletedAt IS NULL
        ORDER BY href, progression
    """)
    fun forBook(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY href, progression")
    suspend fun forBookOnce(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun byId(id: String): AnnotationEntity?

    /**
     * Cross-library search. Works for archived books too, which is the whole reason
     * annotations stay on the device after the EPUB leaves.
     *
     * [query] is FTS4 MATCH syntax — callers should sanitise user input and may append
     * '*' for prefix matching.
     */
    @Query("""
        SELECT a.* FROM annotations AS a
        JOIN annotations_fts AS fts ON fts.rowid = a.rowid
        WHERE annotations_fts MATCH :query AND a.deletedAt IS NULL
        ORDER BY a.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 200): List<AnnotationEntity>

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId AND deletedAt IS NULL")
    fun countForBook(bookId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(annotations: List<AnnotationEntity>)

    @Upsert suspend fun upsert(annotation: AnnotationEntity)

    /** Tombstone rather than delete, so a future sync can propagate the removal. */
    @Query("UPDATE annotations SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())
}
