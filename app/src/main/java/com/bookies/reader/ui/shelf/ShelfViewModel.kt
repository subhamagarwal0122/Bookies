package com.bookies.reader.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.drive.ArchiveManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShelfViewModel(private val app: BookiesApp) : ViewModel() {

    val books: StateFlow<List<BookEntity>> =
        app.database.books().shelf()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-book transfer progress, keyed by book id, so the shelf can show it inline. */
    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers = _transfers.asStateFlow()

    data class Transfer(val label: String, val fraction: Float?)

    fun archive(book: BookEntity, token: String) = viewModelScope.launch {
        setTransfer(book.id, Transfer("Packing", null))
        val result = app.archiveManager.archive(book.id, token) { label ->
            setTransfer(book.id, Transfer(label, null))
        }
        clearTransfer(book.id, result)
    }

    fun restore(book: BookEntity, token: String, onReady: () -> Unit = {}) = viewModelScope.launch {
        setTransfer(book.id, Transfer("Locating", 0f))
        val result = app.archiveManager.restore(book.id, token) { label, fraction ->
            setTransfer(book.id, Transfer(label, fraction))
        }
        clearTransfer(book.id, result)
        if (result is ArchiveManager.Result.Success) onReady()
    }

    /**
     * True when tapping this book needs the network before the reader can open.
     * Drives the paused book-opening animation.
     */
    fun needsRestore(book: BookEntity) = book.storageState == StorageState.ARCHIVED

    private fun setTransfer(id: String, transfer: Transfer) {
        _transfers.value = _transfers.value + (id to transfer)
    }

    private fun clearTransfer(id: String, result: ArchiveManager.Result) {
        // TODO surface ArchiveManager.Result.Failed / BundleMissing to the UI as a snackbar
        _transfers.value = _transfers.value - id
    }
}
