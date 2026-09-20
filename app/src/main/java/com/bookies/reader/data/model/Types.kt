package com.bookies.reader.data.model

/**
 * Where a book's EPUB file currently is.
 *
 * The transitional states are persisted deliberately: if the process dies mid-transfer
 * we can tell the difference between "never started" and "half done" on next launch
 * and resume or roll back. See ArchiveManager.
 */
enum class StorageState {
    LOCAL,      // epub is on the device, fully usable
    ARCHIVING,  // bundle is uploading; local file still present and authoritative
    ARCHIVED,   // bundle verified in Drive, local epub deleted, stub retained
    RESTORING,  // bundle is downloading
    MISSING     // marked ARCHIVED but the Drive bundle could not be found
}

enum class ReadingStatus { UNREAD, READING, FINISHED, ABANDONED }

/**
 * Whether there is a file behind this book at all.
 *
 * A PHYSICAL book is a real object on a shelf: it has a cover, a title and annotations,
 * but no spine items and nothing to archive or restore. Everything downstream — the
 * shelf, the index screen, search, tags, Markdown export — treats both kinds
 * identically, which is why they share one table rather than living in parallel ones.
 */
enum class BookFormat { EPUB, PHYSICAL }

enum class AnnotationType { HIGHLIGHT, NOTE, BOOKMARK }

/** Where an annotation came from, so imports stay distinguishable later. */
enum class AnnotationSource {
    ANDROID,    // selected in the reader
    WEB,
    KINDLE,     // parsed from My Clippings.txt
    OCR,        // photographed from a paper page and recognised
    TYPED,      // keyed in by hand from a paper book
    IMPORT      // restored from a bundle of unknown provenance
}
