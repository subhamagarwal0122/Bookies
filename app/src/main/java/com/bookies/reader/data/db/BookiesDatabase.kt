package com.bookies.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookEntity::class,
        AnnotationEntity::class,
        AnnotationFts::class,
        TagEntity::class,
        AnnotationTagCrossRef::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class BookiesDatabase : RoomDatabase() {

    abstract fun books(): BookDao
    abstract fun annotations(): AnnotationDao

    companion object {
        @Volatile private var instance: BookiesDatabase? = null

        fun get(context: Context): BookiesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookiesDatabase::class.java,
                    "bookies.db"
                )
                    // No fallbackToDestructiveMigration: this database is the library.
                    // Every schema change gets a real migration.
                    .build()
                    .also { instance = it }
            }
    }
}
