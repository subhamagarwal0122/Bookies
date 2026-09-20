package com.bookies.reader.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.repo.OpenLibrary
import com.bookies.reader.scan.IsbnScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the add-a-paper-book dialog. As with [com.bookies.reader.ui.search.SearchViewModel],
 * the thinking is next door in [AddBookModel] and there is deliberately little here: this
 * class is the part that cannot be run on a development machine.
 *
 * It outlives the dialog's composition on purpose. The scanner brings its own activity to
 * the front, and on a phone short of memory that can take this one away entirely; holding
 * the half-filled state in a ViewModel means coming back to the dialog as it was rather
 * than to an empty field.
 */
internal class AddBookViewModel(private val app: BookiesApp) : ViewModel() {

    private val _state = MutableStateFlow(AddBookState())
    val state: StateFlow<AddBookState> = _state.asStateFlow()

    /** Set once the book is on the shelf, so the host can close the dialog and say so. */
    private val _added = MutableStateFlow<String?>(null)
    val added: StateFlow<String?> = _added.asStateFlow()

    fun onIsbnChange(raw: String) {
        val isbn = filterIsbnInput(raw)
        _state.value = _state.value.copy(isbn = isbn, hint = isbnHint(isbn))
    }

    /** The camera's answer, handed back by the activity that launched the scanner. */
    fun onScan(outcome: IsbnScanner.Outcome) {
        when (outcome) {
            is IsbnScanner.Outcome.Scanned -> {
                val isbn = filterIsbnInput(outcome.raw)
                _state.value = _state.value.copy(isbn = isbn, hint = isbnHint(isbn))
                // Straight into the lookup. A scan is an unambiguous "this book", and
                // making the reader then press Look up would be asking twice.
                if (canLookUp(isbn)) lookUp()
            }
            IsbnScanner.Outcome.Cancelled -> Unit
            is IsbnScanner.Outcome.Unavailable ->
                _state.value = _state.value.copy(hint = outcome.reason)
        }
    }

    fun lookUp() {
        val isbn = _state.value.isbn
        if (_state.value.busy || !canLookUp(isbn)) return

        _state.value = _state.value.copy(busy = true, hint = null)
        viewModelScope.launch {
            // The shelf is checked first and separately from the lookup, so a book that is
            // already here is reported as such even when Open Library is unreachable.
            val normalised = OpenLibrary.normaliseIsbn(isbn)
            val existing = normalised?.let { app.database.books().byIsbn(it) }
            val outcome = afterLookup(app.openLibrary.lookup(isbn), existing?.title)
            _state.value = _state.value.copy(
                busy = false,
                step = outcome.step,
                hint = outcome.hint
            )
        }
    }

    /** "Not this edition" — carry what came back into the fields rather than start over. */
    fun editMatch(meta: OpenLibrary.Metadata) {
        _state.value = _state.value.copy(
            step = AddStep.Manual(null),
            draft = draftFrom(meta),
            draftError = null
        )
    }

    /** "Add it by hand" from the ISBN field, keeping a scanned ISBN if there was one. */
    fun enterByHand() {
        _state.value = _state.value.copy(
            step = AddStep.Manual(null),
            draft = draftFor(_state.value.isbn),
            draftError = null
        )
    }

    fun onDraftChange(draft: ManualDraft) {
        // The error goes the moment anything is edited: leaving it under a field the reader
        // has already fixed reads as though the fix was rejected.
        _state.value = _state.value.copy(draft = draft, draftError = null)
    }

    fun backToEntry() {
        _state.value = _state.value.copy(step = AddStep.Entry, draftError = null)
    }

    /** Saves the match Open Library returned, cover and all. */
    fun confirm(meta: OpenLibrary.Metadata) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val coverPath = meta.coverUrl?.let { app.covers.download(it, meta.isbn) }
            save(
                NewPhysicalBook(meta.title, meta.authors, meta.isbn, meta.pageCount),
                coverPath
            )
        }
    }

    /** Saves hand-entered details. No cover: there is no URL to fetch one from. */
    fun saveDraft() {
        if (_state.value.busy) return
        validate(_state.value.draft)
            .onFailure { error ->
                _state.value = _state.value.copy(draftError = error.message)
            }
            .onSuccess { book ->
                _state.value = _state.value.copy(busy = true, draftError = null)
                viewModelScope.launch { save(book, coverPath = null) }
            }
    }

    private suspend fun save(book: NewPhysicalBook, coverPath: String?) {
        val added = app.physicalBooks.add(
            title = book.title,
            authors = book.authors,
            isbn = book.isbn,
            pageCount = book.pageCount,
            coverPath = coverPath
        )
        _state.value = _state.value.copy(busy = false)
        _added.value = added.title
    }
}
