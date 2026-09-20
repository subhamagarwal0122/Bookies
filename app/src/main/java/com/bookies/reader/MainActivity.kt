package com.bookies.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.ui.reader.ReaderScreen
import com.bookies.reader.ui.shelf.ShelfScreen
import com.bookies.reader.ui.shelf.ShelfViewModel
import com.bookies.reader.ui.theme.BookiesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app: BookiesApp get() = application as BookiesApp

    /** SAF picker — the "+" route into the library. */
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { app.importer.import(contentResolver, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Share-sheet / "open with" route. Handled here so a downloaded EPUB can go
        // straight from the browser into the library.
        intent?.data?.let { uri ->
            lifecycleScope.launch { app.importer.import(contentResolver, uri) }
        }

        setContent {
            BookiesTheme {
                val viewModel: ShelfViewModel = viewModel(factory = factory())
                var open by remember { mutableStateOf<BookEntity?>(null) }

                val current = open
                if (current == null) {
                    ShelfScreen(viewModel = viewModel, onOpenBook = { open = it })
                } else {
                    // TODO wire BookOpenTransition between these two states, driving
                    // rememberBookOpenProgress from viewModel.transfers[book.id] so an
                    // archived book restores while the cover is already swinging open.
                    ReaderScreen(book = current)
                }
            }
        }
    }

    fun promptForEpub() = pickEpub.launch(arrayOf("application/epub+zip"))

    private fun factory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShelfViewModel(app) as T
    }
}
