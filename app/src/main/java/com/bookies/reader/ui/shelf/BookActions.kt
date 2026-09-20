package com.bookies.reader.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.BookFormat
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.export.ExportShare

/**
 * Per-book actions: archive to Drive, restore from it, export the annotations.
 *
 * Deliberately a visible mark on the cover rather than a long-press. Archiving is the
 * feature the whole app is built around and a gesture with no affordance is a feature
 * nobody finds; a long-press also competes with the tap that opens the book, on a shelf
 * that is already dropping touch events (see the obscured-touch note in CLAUDE.md).
 *
 * Nothing here talks to Drive. Getting a scoped token means an activity result and a
 * possible consent screen, both of which outlive this composition, so [onArchive] and
 * [onRestore] are handed up to the activity that owns that flow.
 */

/** The affordance itself, drawn in a corner of the cover. */
@Composable
fun BookActionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(32.dp)
            .clip(CircleShape)
            // A dark disc rather than a bare glyph: the cover underneath is arbitrary
            // artwork, and white-on-white is the one combination that must not happen.
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Book actions" },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "⋯", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The action sheet, and the archive confirmation it escalates to.
 *
 * [transfer] is the book's in-flight transfer, if any: while one is running the two
 * transfer actions are replaced by its progress label, because a second archive of the
 * same book would be rejected by [com.bookies.reader.drive.ArchiveManager] anyway and
 * an action that silently does nothing is worse than an absent one.
 */
@Composable
fun BookActionsDialog(
    book: BookEntity,
    transfer: ShelfViewModel.Transfer?,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed on the book so reopening the sheet on a different cover cannot inherit a
    // half-answered confirmation from the previous one.
    var confirmingArchive by remember(book.id) { mutableStateOf(false) }

    if (confirmingArchive) {
        ArchiveConfirmation(
            book = book,
            onConfirm = {
                confirmingArchive = false
                onArchive()
                onDismiss()
            },
            onCancel = { confirmingArchive = false }
        )
        return
    }

    // A physical book has no file to send anywhere; ArchiveManager refuses one outright.
    // This is the only place format is consulted, and it is about the existence of a
    // file, not about how the book is treated — export below is identical for both.
    val archivable = book.format == BookFormat.EPUB && book.storageState == StorageState.LOCAL
    val restorable =
        book.storageState == StorageState.ARCHIVED || book.storageState == StorageState.MISSING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    transfer != null -> ActionRow(
                        label = "${transfer.label}…",
                        enabled = false,
                        onClick = {}
                    )
                    archivable -> ActionRow(
                        label = "Archive to Drive",
                        detail = "Frees the file from this phone. Your notes stay.",
                        onClick = { confirmingArchive = true }
                    )
                    restorable -> ActionRow(
                        label = "Restore from Drive",
                        detail = if (book.storageState == StorageState.MISSING)
                            "The last attempt could not find the backup."
                        else
                            "Brings the book back onto this phone.",
                        onClick = { onRestore(); onDismiss() }
                    )
                }

                Divider()

                ActionRow(
                    label = "Export annotations",
                    detail = "Markdown, to wherever you like.",
                    onClick = { onExport(); onDismiss() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}

/**
 * Archiving is the one action that removes something, so it asks first — and the asking
 * has to be accurate about what is removed, which is less than it sounds: the upload is
 * verified against Drive's own checksum before the local EPUB goes, and the annotations
 * are never touched at all.
 */
@Composable
private fun ArchiveConfirmation(book: BookEntity, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Archive “${book.title}”?") },
        text = {
            Text(
                "The book is packed and uploaded to your Google Drive. Only once Drive " +
                    "confirms the upload is the copy on this phone deleted — if anything " +
                    "goes wrong, nothing is removed.\n\n" +
                    "Your highlights and notes stay on this phone either way, and the book " +
                    "stays on the shelf. Tap it to bring the file back.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Archive") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background
    )
}

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

/**
 * Hands a rendered export to the share sheet the moment one appears.
 *
 * An effect rather than a button because the render is asynchronous: the tap happens on
 * the index screen or the action sheet, the Markdown arrives later, and whatever is on
 * screen by then should not have to care.
 */
@Composable
fun ExportEffect(export: ShelfViewModel.Export?, onDone: (error: String?) -> Unit) {
    val context = LocalContext.current
    // The activity survives the chooser; this composition may not. Reading the callback
    // through rememberUpdatedState keeps a recomposition from firing the share twice.
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(export) {
        if (export != null) done(ExportShare.share(context, export.book, export.markdown))
    }
}
