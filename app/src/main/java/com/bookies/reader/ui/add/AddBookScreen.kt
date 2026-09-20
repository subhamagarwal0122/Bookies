package com.bookies.reader.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.repo.OpenLibrary
import com.bookies.reader.scan.IsbnScanner

/**
 * Putting a paper book on the shelf.
 *
 * A dialog rather than a screen, and not by accident: MainActivity owns exactly one
 * BackHandler for the whole app and nested handlers here would resolve by composition
 * order, which is the failure this project has already paid for once. An AlertDialog takes
 * back through its own dismiss and leaves that arrangement alone.
 *
 * Three faces, all in here because they are one task: scan or type an ISBN, confirm what
 * Open Library returned, or fill it in by hand. Every failure lands on the last of those
 * rather than on a dead end — the ISBN is a shortcut to typing four fields, never the only
 * way in.
 */

/**
 * The fork the "+" button now opens onto: a file, or an object.
 *
 * Worth a step even though it costs a tap. The shelf holds both kinds, and until now
 * nothing on it said so.
 */
@Composable
fun AddSourceDialog(
    onPickEpub: () -> Unit,
    onAddPaper: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a book", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ActionRow(
                    label = "An EPUB",
                    detail = "From this phone, or anywhere a file picker can reach.",
                    onClick = onPickEpub
                )
                Divider()
                ActionRow(
                    label = "A paper book",
                    detail = "Scan the barcode. It gets the same index as the rest.",
                    onClick = onAddPaper
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background
    )
}

/**
 * @param onScan hands the scan up to the activity, because Play services' scanner brings
 *   its own activity to the front and the flow outlives this composition — the same reason
 *   the Drive consent flow is owned up there.
 */
@Composable
fun AddBookDialog(
    onScan: ((IsbnScanner.Outcome) -> Unit) -> Unit,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Manual DI, as everywhere else: the graph hangs off the Application object.
    val app = LocalContext.current.applicationContext as BookiesApp
    val factory = remember(app) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AddBookViewModel(app) as T
        }
    }
    val viewModel: AddBookViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val added by viewModel.added.collectAsStateWithLifecycle()

    // The save finishes off-composition; this is what closes the dialog once it has.
    val addedHandler by rememberUpdatedState(onAdded)
    LaunchedEffect(added) { added?.let(addedHandler) }

    when (val step = state.step) {
        AddStep.Entry -> IsbnEntry(
            state = state,
            onIsbnChange = viewModel::onIsbnChange,
            onScan = { onScan(viewModel::onScan) },
            onLookUp = viewModel::lookUp,
            onByHand = viewModel::enterByHand,
            onDismiss = onDismiss
        )

        is AddStep.Confirm -> ConfirmMatch(
            meta = step.meta,
            busy = state.busy,
            onAdd = { viewModel.confirm(step.meta) },
            onEdit = { viewModel.editMatch(step.meta) },
            onDismiss = onDismiss
        )

        is AddStep.Manual -> ManualEntry(
            state = state,
            reason = step.reason,
            onChange = viewModel::onDraftChange,
            onSave = viewModel::saveDraft,
            onBack = viewModel::backToEntry,
            onDismiss = onDismiss
        )

        is AddStep.AlreadyHere -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Already on the shelf") },
            text = {
                Text(
                    "“${step.title}” is here already. Its annotations are on its own index.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            containerColor = MaterialTheme.colorScheme.background
        )
    }
}

@Composable
private fun IsbnEntry(
    state: AddBookState,
    onIsbnChange: (String) -> Unit,
    onScan: () -> Unit,
    onLookUp: () -> Unit,
    onByHand: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a paper book", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Scan the barcode on the back, or type the ISBN under it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.isbn,
                        onValueChange = onIsbnChange,
                        singleLine = true,
                        enabled = !state.busy,
                        label = { Text("ISBN") },
                        // A number pad, because an ISBN is digits — bar the ISBN-10 check
                        // character, which is why the keyboard is not restricted outright.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    TextButton(onClick = onScan, enabled = !state.busy) { Text("Scan") }
                }

                // The one line that reports everything: how far through the digits we are,
                // a failed checksum, a scanner that could not start, a lookup that timed
                // out. All of them leave the reader on this field, because all of them are
                // fixed here.
                state.hint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (state.busy) {
                    Text(
                        text = "Looking it up…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Divider()

                ActionRow(
                    label = "Add it by hand instead",
                    detail = "For a book with no barcode, or one Open Library has never heard of.",
                    enabled = !state.busy,
                    onClick = onByHand
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onLookUp, enabled = !state.busy && canLookUp(state.isbn)) {
                Text("Look up")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background
    )
}

/**
 * Open Library is crowd-sourced and an ISBN can resolve to a different printing of the
 * same book, so what came back is proposed rather than assumed. The page count is called
 * out because it is the one field that will silently distort every annotation made later:
 * it is the denominator under every page number.
 */
@Composable
private fun ConfirmMatch(
    meta: OpenLibrary.Metadata,
    busy: Boolean,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Is this it?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(meta.title, style = MaterialTheme.typography.titleMedium)

                if (meta.authors.isNotBlank()) {
                    Text(
                        text = meta.authors,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                val summary = metaSummary(meta)
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (meta.pageCount == null) {
                    Text(
                        text = "No page count. Add one by hand if you want your page " +
                            "numbers to place notes through the book.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Divider()

                ActionRow(
                    label = "Not quite — let me edit it",
                    enabled = !busy,
                    onClick = onEdit
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = !busy) {
                Text(if (busy) "Adding…" else "Add to shelf")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background
    )
}

@Composable
private fun ManualEntry(
    state: AddBookState,
    reason: String?,
    onChange: (ManualDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val draft = state.draft

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book details", style = MaterialTheme.typography.titleMedium) },
        text = {
            // Scrollable: three fields plus the keyboard leaves very little dialog on a
            // short phone, and a field you cannot scroll to is a field you cannot fill.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                reason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.authors,
                    onValueChange = { onChange(draft.copy(authors = it)) },
                    label = { Text("Authors") },
                    // Commas are what people type; joinAuthors turns them into the "; "
                    // the rest of the app stores.
                    placeholder = { Text("Separated by commas") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.pageCount,
                    onValueChange = { onChange(draft.copy(pageCount = it)) },
                    label = { Text("Pages") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "The page count is what turns a page number into a position in " +
                        "the book, so notes sit in the index where they belong. Without it " +
                        "they gather at the front.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )

                state.draftError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.busy) {
                Text(if (state.busy) "Adding…" else "Add to shelf")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("Back") }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}

// --- shared bits, same shapes as the book-actions sheet ---------------------------------

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
    enabled: Boolean = true
) {
    val tint = if (enabled) 1f else 0.45f
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = tint)
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f * tint)
            )
        }
    }
}

/** A hairline, drawn rather than imported: material3's own divider name has moved twice. */
@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    )
}
