package com.bookies.reader.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookies.reader.BookiesApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the search screen. All the thinking is in SearchModel; this is the part that
 * cannot be unit-tested here, so there is deliberately almost nothing of it.
 */
internal class SearchViewModel(private val app: BookiesApp) : ViewModel() {

    data class UiState(
        val query: String = "",
        val searching: Boolean = false,
        val groups: List<BookGroup> = emptyList(),
        val hitCount: Int = 0,
        /** The DAO's LIMIT was reached, so the reader is not seeing everything. */
        val truncated: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * A keystroke. The debounce is not about database load — it is that FTS is being asked
     * for a prefix match on a word the reader is still in the middle of spelling, and
     * re-ranking the whole library on every character makes the list flicker.
     */
    fun onQueryChange(raw: String) {
        _state.value = _state.value.copy(query = raw)
        searchJob?.cancel()

        val match = sanitiseFtsQuery(raw)
        if (match == null) {
            // Nothing searchable survived sanitising ("AND", `***`, a lone quote). Showing
            // the empty prompt is right, and sending "" to MATCH would be a syntax error.
            _state.value = _state.value.copy(
                searching = false, groups = emptyList(), hitCount = 0, truncated = false
            )
            return
        }

        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            run(raw, match)
        }
    }

    private suspend fun run(raw: String, match: String) {
        val rows = app.database.annotations().search(match, LIMIT)

        // The shelf query, not a per-row lookup: it already excludes tombstoned books and
        // already includes archived ones, which is precisely the set a hit may come from.
        // Resolving each hit's book individually would be one round trip per result.
        val books = app.database.books().shelf().first().associateBy { it.id }

        val groups = buildResults(raw, rows, books)
        _state.value = _state.value.copy(
            searching = false,
            groups = groups,
            hitCount = groups.sumOf { it.hits.size },
            truncated = rows.size >= LIMIT
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 180L
        const val LIMIT = 200
    }
}
