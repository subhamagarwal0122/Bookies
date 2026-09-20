package com.bookies.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.ui.index.AnnotationIndexScreen
import com.bookies.reader.ui.reader.ReaderScreen
import com.bookies.reader.ui.shelf.BookCover
import com.bookies.reader.ui.shelf.BookOpenTransition
import com.bookies.reader.ui.shelf.ShelfScreen
import com.bookies.reader.ui.shelf.ShelfViewModel
import com.bookies.reader.ui.shelf.rememberBookOpenProgress
import com.bookies.reader.ui.theme.BookiesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app: BookiesApp get() = application as BookiesApp

    /** SAF picker — the "+" route into the library. */
    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { app.importer.import(contentResolver, uri) }
    }

    /**
     * Retry for whatever asked for a Drive token and was told the user has to consent
     * first. Held on the activity rather than in composition because the consent flow
     * outlives the composition that started it.
     */
    private var afterConsent: (() -> Unit)? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val retry = afterConsent
        afterConsent = null
        if (result.resultCode == RESULT_OK) retry?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on a genuinely fresh launch. A rotation or a process restart re-delivers
        // the same intent, and importing an EPUB twice on every config change is exactly
        // the kind of thing nobody notices until the shelf has four copies of a book.
        if (savedInstanceState == null) importFromIntent(intent)

        setContent {
            BookiesTheme {
                val viewModel: ShelfViewModel = viewModel(factory = factory())
                val books by viewModel.books.collectAsStateWithLifecycle()
                val transfers by viewModel.transfers.collectAsStateWithLifecycle()

                // Three states, in the order a reader moves through them: the shelf, the
                // cover swinging open onto the index, and the passage itself.
                var selectedId by remember { mutableStateOf<String?>(null) }
                var opening by remember { mutableStateOf(false) }
                var reading by remember { mutableStateOf<BookEntity?>(null) }

                val inReader = reading
                if (inReader != null) {
                    BackHandler { reading = null }
                    ReaderScreen(book = inReader)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        ShelfScreen(
                            viewModel = viewModel,
                            onOpenBook = { book ->
                                viewModel.select(book)
                                selectedId = book.id
                                opening = true
                                // Start the download now, not after the animation: the
                                // point of the hold-at-0.3 design is that the two happen
                                // at the same time.
                                if (viewModel.needsRestore(book)) {
                                    withDriveToken { token -> viewModel.restore(book, token) }
                                }
                            },
                            onAddBook = ::promptForEpub
                        )

                        // Re-read the row from the shelf flow each recomposition: a
                        // restore rewrites storageState, and a stale copy would leave the
                        // cover held part-open forever.
                        val book = selectedId?.let { id -> books.firstOrNull { it.id == id } }
                        if (book != null) {
                            val annotations by viewModel.selectedAnnotations.collectAsStateWithLifecycle()

                            // null means "nothing to wait for, open in one motion". A book
                            // that is not yet local reports the transfer instead, and the
                            // cover creeps open in step with it.
                            val restoreProgress =
                                if (book.storageState == StorageState.LOCAL) null
                                else transfers[book.id]?.fraction ?: 0f

                            val progress = rememberBookOpenProgress(
                                opening = opening,
                                restoreProgress = restoreProgress,
                                onOpened = {}
                            )

                            BackHandler { opening = false }

                            // Fully shut again: drop the index so the shelf takes input.
                            if (!opening && progress == 0f) {
                                LaunchedEffect(Unit) {
                                    selectedId = null
                                    viewModel.select(null)
                                }
                            }

                            BookOpenTransition(
                                progress = progress,
                                modifier = Modifier.fillMaxSize(),
                                page = {
                                    AnnotationIndexScreen(
                                        book = book,
                                        annotations = annotations,
                                        onBack = { opening = false },
                                        // The reader cannot take a locator yet; once it
                                        // can, the tapped annotation is what it opens on.
                                        onOpenAnnotation = { reading = book },
                                        onExport = { viewModel.export(book) },
                                        // Opaque, or the shelf shows through the page the
                                        // cover is lifting off.
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.background)
                                    )
                                },
                                cover = { BookCover(book = book, modifier = Modifier.fillMaxSize()) }
                            )
                        }
                    }
                }
            }
        }
    }

    fun promptForEpub() = pickEpub.launch(arrayOf("application/epub+zip"))

    /**
     * The "open with" and share-sheet routes.
     *
     * ACTION_VIEW puts the EPUB in the intent's data; ACTION_SEND puts it in EXTRA_STREAM.
     * Reading only `data` is why Bookies could appear in the share sheet, accept a book
     * and then do nothing at all.
     */
    private fun importFromIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_IMPORT_CONSUMED, false)) return

        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent.data
        }
        uri ?: return

        // Belt and braces with the savedInstanceState check: the flag rides on the intent
        // itself, which is the same object every recreation sees.
        intent.putExtra(EXTRA_IMPORT_CONSUMED, true)
        lifecycleScope.launch { app.importer.import(contentResolver, uri) }
    }

    /**
     * Runs [onToken] with a `drive.file` access token, prompting for consent first if this
     * is the first transfer. A refusal is silent: the book stays archived.
     */
    private fun withDriveToken(onToken: (String) -> Unit) {
        lifecycleScope.launch {
            val token = runCatching {
                app.driveAuth.accessToken(this@MainActivity) { sender ->
                    afterConsent = { withDriveToken(onToken) }
                    consent.launch(IntentSenderRequest.Builder(sender).build())
                }
            }.getOrNull()
            token?.let(onToken)
        }
    }

    private fun factory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShelfViewModel(app) as T
    }

    private companion object {
        const val EXTRA_IMPORT_CONSUMED = "com.bookies.reader.IMPORT_CONSUMED"
    }
}
