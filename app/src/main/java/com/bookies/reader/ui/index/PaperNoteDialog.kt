package com.bookies.reader.ui.index

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Writing down a passage from a paper book.
 *
 * The counterpart to selecting text in the reader, and the only way an annotation gets
 * onto a physical book. Everything it writes is ordinary: the row that comes out of this
 * is stored in the same table, indexed by the same FTS, sorted by the same progression and
 * exported by the same Markdown as a highlight made on a screen. That is invariant 4 doing
 * its job — the page number is turned into a progression once, in `PhysicalBooks`, and
 * nothing downstream ever finds out which kind of book it came from.
 */
@Composable
internal fun PaperNoteDialog(
    pageCount: Int?,
    onSave: (quote: String, note: String?, pageNumber: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(PaperNoteDraft()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write down a passage", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = draft.quote,
                    onValueChange = { draft = draft.copy(quote = it); error = null },
                    label = { Text("Passage") },
                    // Multi-line, and the line breaks it collects are thrown away by
                    // tidyQuote: they come from the width of the page, not the sentence.
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                )

                OutlinedTextField(
                    value = draft.page,
                    onValueChange = { draft = draft.copy(page = it); error = null },
                    label = { Text("Page") },
                    placeholder = {
                        Text(if (pageCount != null) "1–$pageCount" else "Optional")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it); error = null },
                    label = { Text("Note") },
                    placeholder = { Text("Optional") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validatePaperNote(draft, pageCount)
                    .onFailure { error = it.message }
                    .onSuccess { note ->
                        onSave(note.quote, note.note, note.pageNumber)
                        onDismiss()
                    }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.background
    )
}
