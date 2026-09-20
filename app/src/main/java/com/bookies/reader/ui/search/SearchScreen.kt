package com.bookies.reader.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.data.model.StorageState
import com.bookies.reader.ui.index.kindLabel
import com.bookies.reader.ui.index.locationLabel
import java.util.Locale

/**
 * Search across every annotation in the library.
 *
 * This is what invariant 6 buys. Annotations stay on the device when a book is archived
 * to Drive, so a search here reaches passages from books whose EPUB is no longer on the
 * phone at all — and a hit from one of those is drawn exactly like any other, apart from
 * the same small cloud mark the shelf uses. The alternative, an index covering only what
 * happens to be resident, would make the feature untrustworthy: a search that silently
 * omits half the library is worse than no search.
 *
 * No BackHandler here. MainActivity owns a single app-wide one, because nested handlers
 * resolve by composition order and quietly stop working when the tree is rearranged.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenResult: (BookEntity, AnnotationEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // Manual DI, as everywhere else: the graph hangs off the Application object.
    val app = LocalContext.current.applicationContext as BookiesApp
    val factory = remember(app) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(app) as T
        }
    }
    val viewModel: SearchViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        SearchHeader(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onBack = onBack
        )

        ResultSummary(state)

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            results(state.groups, onOpenResult)
        }
    }
}

// --- header ---------------------------------------------------------------------------

@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val focus = remember { FocusRequester() }
    // Arriving on this screen means intending to type. Anything else is a tap wasted.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        Text(
            text = "←  Shelf",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp)
        )

        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // BasicTextField for the same reason the index uses one: a Material TextField
            // brings a container, a label slot and an indicator line that this does not want.
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search every book",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

/**
 * One quiet line under the field. It carries the count, but its real job is the empty
 * cases: a query that sanitised away to nothing looks identical to one that simply found
 * nothing, and without this the screen would just sit there.
 */
@Composable
private fun ResultSummary(state: SearchViewModel.UiState) {
    val text = when {
        state.query.isBlank() -> "Highlights, notes and bookmarks from the whole library."
        sanitiseFtsQuery(state.query) == null -> "Nothing to search for yet."
        state.searching -> "Searching…"
        state.hitCount == 0 -> "No annotations match."
        else -> buildString {
            append(state.hitCount)
            append(if (state.hitCount == 1) " annotation in " else " annotations in ")
            append(state.groups.size)
            append(if (state.groups.size == 1) " book" else " books")
            if (state.truncated) append(" · showing the first ${state.hitCount}")
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
    )
}

// --- results --------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.results(
    groups: List<BookGroup>,
    onOpenResult: (BookEntity, AnnotationEntity) -> Unit
) {
    for (group in groups) {
        stickyHeader(key = "book:${group.book.id}") {
            BookHeader(group)
        }
        items(group.hits, key = { it.annotation.id }) { hit ->
            HitRow(hit = hit, onClick = { onOpenResult(hit.book, hit.annotation) })
        }
    }
}

@Composable
private fun BookHeader(group: BookGroup) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Opaque: a sticky header floats over the rows scrolling beneath it.
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 18.dp, bottom = 8.dp)
    ) {
        Text(
            text = group.book.title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        // The only thing that distinguishes a book whose EPUB has gone to Drive. Its
        // annotations are here and searchable either way — that is the point of keeping
        // them — so the mark is information, not a warning.
        if (group.book.storageState != StorageState.LOCAL) {
            Text(
                text = if (group.book.storageState == StorageState.MISSING) "!" else "☁",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = group.hits.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onBackground.copy(alpha = 0.55f)
    val annotation = hit.annotation
    val rail = annotation.colorArgb?.let { Color(it) } ?: scheme.primary

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (hit.snippet.text.length > 120) 64.dp else 40.dp)
                    .background(rail)
            )
            if (annotation.type == AnnotationType.BOOKMARK && hit.snippet.text.isBlank()) {
                // A bookmark selects no text, so there may be nothing to quote at all.
                Text(
                    text = "Bookmark — ${locationLabel(annotation)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = muted,
                    modifier = Modifier.padding(start = 12.dp)
                )
            } else {
                Text(
                    text = marked(hit.snippet, scheme.primary),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onBackground,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        Text(
            // Location and kind, exactly as the index words them — a search result and an
            // index row describe the same thing and should read the same way. No page
            // number means a percentage, and nothing here asks what format the book is.
            text = "${kindLabel(annotation)} · ${locationLabel(annotation)}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 8.dp, start = 14.dp)
        )
    }
}

/** The matched ranges in bold and in the accent colour, so a hit is findable by eye. */
@Composable
private fun marked(snippet: Snippet, accent: Color) = buildAnnotatedString {
    append(snippet.text)
    for (range in snippet.matches) {
        // Defensive: the ranges were computed from this exact string, but a bad range
        // would throw inside composition, and a wrong bold span is a far cheaper failure.
        if (range.first < 0 || range.last >= snippet.text.length) continue
        addStyle(
            SpanStyle(fontWeight = FontWeight.SemiBold, color = accent),
            range.first,
            range.last + 1
        )
    }
}
