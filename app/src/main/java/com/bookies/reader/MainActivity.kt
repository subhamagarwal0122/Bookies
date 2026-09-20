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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app: BookiesApp get() = application as BookiesApp

    /**
     * SAF picker — the "+" route into the library.
     *
     * Multiple, because adding a shelf's worth of books one modal at a time is absurd.
     * And unfiltered, because a great many providers report an EPUB as
     * application/octet-stream: filtering on application/epub+zip greys the file out and
     * there is no way to tell that from the app simply refusing to work. [EpubImporter]
     * validates the bytes anyway and now says so out loud when they are not an EPUB.
     */
    private val pickEpubs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        pendingImports.trySend(uris)
    }

    /**
     * The picker result arrives before composition can hand it to the ViewModel, and the
     * activity has no reference to that ViewModel. A channel bridges the two.
     */
    private val pendingImports = Channel<List<Uri>>(Channel.BUFFERED)

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
                val message by viewModel.message.collectAsStateWithLifecycle()

                // Picked files arrive on a channel from the activity's result callback.
                LaunchedEffect(viewModel) {
                    for (uris in pendingImports) viewModel.import(contentResolver, uris)
                }

                // Three states, in the order a reader moves through them: the shelf, the
                // cover swinging open onto the index, and the passage itself.
                var selectedId by remember { mutableStateOf<String?>(null) }
                var opening by remember { mutableStateOf(false) }
                var reading by remember { mutableStateOf<BookEntity?>(null) }

                // Re-read the row from the shelf flow each recomposition: a restore
                // rewrites storageState, and a stale copy would leave the cover held
                // part-open forever.
                val book = selectedId?.let { id -> books.firstOrNull { it.id == id } }

                // One handler for the whole app rather than one per screen. Nested
                // BackHandlers resolve by composition order, which is exactly the kind of
                // thing that silently stops working when the tree is rearranged; with a
                // single enabled handler there is nothing to get wrong. Disabled on the
                // shelf so back still leaves the app from there.
                BackHandler(enabled = reading != null || book != null) {
                    if (reading != null) reading = null else opening = false
                }

                val inReader = reading
                if (inReader != null) {
                    ReaderScreen(book = inReader)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        ShelfScreen(
                            viewModel = viewModel,
                            onOpenBook = { tapped ->
                                viewModel.select(tapped)
                                selectedId = tapped.id
                                opening = true
                                // Start the download now, not after the animation: the
                                // point of the hold-at-0.3 design is that the two happen
                                // at the same time.
                                if (viewModel.needsRestore(tapped)) {
                                    withDriveToken { token ->
                                        // A restore that fails has to shut the cover. It
                                        // is held at the 0.3 mark waiting for bytes that
                                        // are never coming, and nothing else will move it.
                                        viewModel.restore(tapped, token) { ok ->
                                            if (!ok && selectedId == tapped.id) opening = false
                                        }
                                    }
                                }
                            },
                            onAddBook = ::promptForEpub
                        )

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
                                        onRead = { reading = book },
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

                        // Over both the shelf and the index, and deliberately not
                        // full-screen: an invisible full-size host would swallow taps
                        // exactly the way the open cover used to.
                        message?.let { text ->
                            LaunchedEffect(text) {
                                delay(5_000)
                                viewModel.messageShown()
                            }
                            Snackbar(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.background,
                                action = {
                                    TextButton(onClick = viewModel::messageShown) {
                                        Text(
                                            "OK",
                                            color = MaterialTheme.colorScheme.background
                                        )
                                    }
                                }
                            ) { Text(text) }
                        }
                    }
                }
            }
        }
    }

    /**
     * A share into an already-running Bookies. Without this, a second EPUB shared while
     * the app is open is handed to an activity that has already run onCreate, and is
     * dropped on the floor.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    fun promptForEpub() = pickEpubs.launch(arrayOf("*/*"))

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
        pendingImports.trySend(listOf(uri))
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
