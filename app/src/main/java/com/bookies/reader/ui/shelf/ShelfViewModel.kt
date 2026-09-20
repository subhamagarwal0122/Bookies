package com.bookies.reader.ui.shelf

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.drive.ArchiveManager
import com.bookies.reader.epub.EpubImporter
import com.bookies.reader.export.MarkdownExporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShelfViewModel(private val app: BookiesApp) : ViewModel() {

    val books: StateFlow<List<BookEntity>> =
        app.database.books().shelf()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The book whose cover is currently open, if any. Held as an id rather than a row so
     * that a restore rewriting storageState cannot leave a stale copy behind it.
     */
    private val _selectedBookId = MutableStateFlow<String?>(null)

    /** The open book's annotations, in reading order. Empty while the shelf is showing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedAnnotations: StateFlow<List<AnnotationEntity>> =
        _selectedBookId
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else app.database.annotations().forBook(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A rendered export that has not left the app yet.
     *
     * Rendering the Markdown was never the hard part; it used to stop here, in a flow
     * nothing collected, which is indistinguishable from the button doing nothing. The
     * UI consumes this, hands it to the share sheet and reports back through
     * [exportHandled] — an export only counts once it is out of app-private storage.
     */
    data class Export(val book: BookEntity, val markdown: String)

    private val _pendingExport = MutableStateFlow<Export?>(null)
    val pendingExport: StateFlow<Export?> = _pendingExport.asStateFlow()

    fun select(book: BookEntity?) {
        _selectedBookId.value = book?.id
    }

    /**
     * One line of feedback for the shelf to show and then drop. Import used to discard
     * its [EpubImporter.Outcome] entirely, which made a corrupt file, a duplicate and a
     * book that imported perfectly all look identical: nothing happens.
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /**
     * A paper book has landed on the shelf. Reported here rather than from the add dialog
     * because the dialog closes on success, and a confirmation that vanishes with the
     * thing confirming it is no confirmation at all.
     */
    fun bookAdded(title: String) {
        _message.value = "Added “$title”"
    }

    /**
     * Imports one or more picked EPUBs, sequentially so two imports cannot race on the
     * same hash. Every outcome is reported; a batch reports only the last one plus a
     * count, because a snackbar is not a log.
     */
    fun import(resolver: ContentResolver, uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        var imported = 0
        var last: String? = null
        for (uri in uris) {
            when (val outcome = app.importer.import(resolver, uri)) {
                is EpubImporter.Outcome.Imported -> {
                    imported++
                    last = "Added “${outcome.book.title}”"
                }
                is EpubImporter.Outcome.AlreadyPresent ->
                    last = "“${outcome.book.title}” is already on the shelf"
                is EpubImporter.Outcome.EditionConflict ->
                    last = "Another edition of “${outcome.existing.title}” is already here"
                is EpubImporter.Outcome.Failed ->
                    last = "Import failed: ${outcome.reason}"
            }
        }
        _message.value = if (uris.size > 1) "$imported of ${uris.size} added · $last" else last
    }

    /**
     * Renders a book's annotations and queues them for a destination.
     *
     * No branch on [com.bookies.reader.data.model.BookFormat] anywhere in here: a paper
     * book exports exactly the way an EPUB does, which is the point of the two sharing
     * one table.
     */
    fun export(book: BookEntity) = viewModelScope.launch {
        val rows = app.database.annotations().forBookOnce(book.id)
        if (rows.isEmpty()) {
            // A share sheet carrying a header and no passages reads as a broken export,
            // so say why instead of handing one over.
            _message.value = "“${book.title}” has no annotations to export"
            return@launch
        }
        _pendingExport.value = Export(book, MarkdownExporter.render(book, rows))
    }

    /**
     * Called once the export has been offered to a destination. [error] is non-null when
     * it never got there — silence would leave the reader believing a file exists
     * somewhere that does not.
     */
    fun exportHandled(error: String? = null) {
        _pendingExport.value = null
        if (error != null) _message.value = error
    }

    /**
     * Writes down a passage from a paper book.
     *
     * Goes through `PhysicalBooks` rather than straight to the DAO because that is where
     * the page number becomes a progression. After this call the row is indistinguishable
     * from one made by selecting text in the reader, which is the entire point.
     */
    fun addNote(
        book: BookEntity,
        quote: String,
        note: String?,
        pageNumber: Int?
    ) = viewModelScope.launch {
        app.physicalBooks.annotate(
            book = book,
            quote = quote,
            pageNumber = pageNumber,
            note = note
        )
        // Said out loud because the index sorts by position, so a note without a page
        // number lands at the top rather than at the bottom where it was just written.
        _message.value = if (pageNumber != null) "Noted on p. $pageNumber" else "Noted"
    }

    /** Per-book transfer progress, keyed by book id, so the shelf can show it inline. */
    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers = _transfers.asStateFlow()

    data class Transfer(val label: String, val fraction: Float?)

    /**
     * Uploads the book and frees its local EPUB. [ArchiveManager] does not delete
     * anything until Drive's own MD5 of what landed matches the bundle we packed, so a
     * failure here costs bandwidth and leaves the book exactly where it was.
     */
    fun archive(book: BookEntity, token: String) = viewModelScope.launch {
        setTransfer(book.id, Transfer("Packing", null))
        val result = app.archiveManager.archive(book.id, token) { label ->
            setTransfer(book.id, Transfer(label, null))
        }
        // Worth saying out loud, because the shelf looks identical afterwards: the file
        // went, the notes did not.
        clearTransfer(book.id, result, "“${book.title}” is in Drive · annotations stay here")
    }

    /**
     * [onDone] is told whether the book actually arrived. The caller needs the failure
     * case as much as the success one: the cover is held part-open waiting on this, and
     * without the signal a failed restore leaves it stuck at the hold point for ever.
     */
    fun restore(book: BookEntity, token: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        setTransfer(book.id, Transfer("Locating", 0f))
        val result = app.archiveManager.restore(book.id, token) { label, fraction ->
            setTransfer(book.id, Transfer(label, fraction))
        }
        clearTransfer(book.id, result)
        onDone(result is ArchiveManager.Result.Success)
    }

    /**
     * True when tapping this book needs the network before the reader can open.
     * Drives the paused book-opening animation.
     */
    fun needsRestore(book: BookEntity) = book.storageState == StorageState.ARCHIVED

    private fun setTransfer(id: String, transfer: Transfer) {
        _transfers.value = _transfers.value + (id to transfer)
    }

    private fun clearTransfer(id: String, result: ArchiveManager.Result, onSuccess: String? = null) {
        _transfers.value = _transfers.value - id
        _message.value = when (result) {
            is ArchiveManager.Result.Success -> onSuccess
            is ArchiveManager.Result.Failed -> "Transfer failed: ${result.reason}"
            is ArchiveManager.Result.BundleMissing -> "That book's backup is missing from Drive"
        }
    }
}
