package com.bookies.reader.data.db

import androidx.room.TypeConverter
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.ReadingStatus
import com.bookies.reader.data.model.StorageState

/** Enums are stored as names, not ordinals — reordering an enum must not corrupt data. */
class Converters {
    @TypeConverter fun toStorageState(v: String) = StorageState.valueOf(v)
    @TypeConverter fun fromStorageState(v: StorageState) = v.name

    @TypeConverter fun toBookFormat(v: String) = BookFormat.valueOf(v)
    @TypeConverter fun fromBookFormat(v: BookFormat) = v.name

    @TypeConverter fun toReadingStatus(v: String) = ReadingStatus.valueOf(v)
    @TypeConverter fun fromReadingStatus(v: ReadingStatus) = v.name

    @TypeConverter fun toAnnotationType(v: String) = AnnotationType.valueOf(v)
    @TypeConverter fun fromAnnotationType(v: AnnotationType) = v.name

    @TypeConverter fun toAnnotationSource(v: String) = AnnotationSource.valueOf(v)
    @TypeConverter fun fromAnnotationSource(v: AnnotationSource) = v.name
}
