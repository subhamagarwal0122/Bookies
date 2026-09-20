package com.bookies.reader.ui.index

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.BookFormat
import java.util.Locale


/**
 * The annotation index — what a cover opens onto.
 *
 * Everything here reads a book through its annotations rather than its pages, so a
 * physical book and an EPUB render through exactly the same code: the only thing that
 * decides how a location is labelled is whether the annotation carries a page number,
 * never [com.bookies.reader.data.model.BookFormat].
 */
@Composable
fun AnnotationIndexScreen(
    book: BookEntity,
    annotations: List<AnnotationEntity>,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onOpenAnnotation: (AnnotationEntity) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(IndexFilter.ALL) }

    // Sorting and grouping are O(n log n) over the whole book; keyed so a keystroke that
    // changes nothing about the inputs does not redo it.
    val groups = remember(annotations, query, filter) {
        groupForDisplay(annotations, query, filter)
    }

    Column(modifier.fillMaxSize()) {
        IndexHeader(
            book = book,
            annotationCount = annotations.size,
            onBack = onBack,
            onRead = onRead
        )

        ToolsRow(
            query = query,
            onQueryChange = { query = it },
            onExport = onExport,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        FilterRow(
            selected = filter,
            onSelect = { filter = it },
            modifier = Modifier.padding(top = 12.dp)
        )

        if (groups.isEmpty()) {
            EmptyState(hasAnnotations = annotations.isNotEmpty())
        } else {
            AnnotationList(groups = groups, onOpenAnnotation = onOpenAnnotation)
        }
    }
}

// --- header ---------------------------------------------------------------------------

@Composable
private fun IndexHeader(
    book: BookEntity,
    annotationCount: Int,
    onBack: () -> Unit,
    onRead: () -> Unit
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "←  Shelf",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp)
            )

            Spacer(Modifier.weight(1f))

            // The index is a way back into the text, but until now the only door was an
            // annotation — and annotations can only be made from inside the reader. A
            // freshly imported book was therefore unopenable. This is that door.
            if (book.format == BookFormat.EPUB) {
                TextButton(onClick = onRead) {
                    Text(
                        text = if (book.progression > 0.0) "Continue" else "Read",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (book.authors.isNotBlank()) {
            Text(
                text = book.authors,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp)
        ) {
            Text(
                text = "$annotationCount annotation${if (annotationCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            ProgressTrack(
                progression = book.progression,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            Text(
                text = positionLabel(book),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Two boxes rather than LinearProgressIndicator: Material3's indicator draws a stop dot
 * and a gap at this size, which reads as a control rather than as a quiet rule.
 */
@Composable
private fun ProgressTrack(progression: Double, modifier: Modifier = Modifier) {
    val fraction = progression.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

// --- tools ----------------------------------------------------------------------------

@Composable
private fun ToolsRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // BasicTextField rather than a Material TextField: the filled TextField brings
            // its own container, label slot and indicator line, none of which this needs.
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search this book",
                            style = MaterialTheme.typography.bodyMedium,
                            color = hint
                        )
                    }
                    innerTextField()
                }
            )
        }

        Spacer(Modifier.width(8.dp))

        TextButton(onClick = onExport) {
            Text("Export", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FilterRow(
    selected: IndexFilter,
    onSelect: (IndexFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (option in IndexFilter.entries) {
            FilterPill(
                label = option.label,
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) scheme.onPrimary else scheme.onBackground.copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary else scheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

// --- list -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnotationList(
    groups: List<Pair<String, List<AnnotationEntity>>>,
    onOpenAnnotation: (AnnotationEntity) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        for ((chapter, entries) in groups) {
            stickyHeader(key = "header:$chapter") {
                Text(
                    text = chapter.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Opaque background: a sticky header floats over the rows beneath it.
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(entries, key = { it.id }) { annotation ->
                AnnotationRow(
                    annotation = annotation,
                    onClick = { onOpenAnnotation(annotation) }
                )
            }
        }
    }
}

@Composable
private fun AnnotationRow(annotation: AnnotationEntity, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onBackground.copy(alpha = 0.55f)
    // A highlight keeps the colour it was made in; anything without one borrows the theme
    // so the rail never disappears against the page.
    val rail = annotation.colorArgb?.let { Color(it) } ?: scheme.primary

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        if (annotation.type == AnnotationType.BOOKMARK) {
            // A bookmark has no selected text, so the quote block would be an empty rail.
            Text(
                text = "Bookmark — ${locationLabel(annotation)}",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = muted
            )
        } else {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(if (annotation.quote.length > 120) 64.dp else 40.dp)
                        .background(rail)
                )
                Text(
                    text = annotation.quote,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onBackground,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        annotation.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 14.dp)
            )
        }

        Text(
            text = footerLine(annotation),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 8.dp, start = 14.dp)
        )
    }
}

@Composable
private fun EmptyState(hasAnnotations: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (hasAnnotations) "Nothing matches." else "No annotations yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}

