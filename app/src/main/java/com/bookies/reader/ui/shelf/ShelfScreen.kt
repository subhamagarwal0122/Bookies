package com.bookies.reader.ui.shelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.StorageState

/**
 * The shelf. Archived books look identical to local ones apart from a small cloud mark —
 * the whole point of keeping the cover, title and reading status on the device is that
 * the library stays visually complete no matter what has been offloaded.
 */
@Composable
fun ShelfScreen(
    viewModel: ShelfViewModel,
    onOpenBook: (BookEntity) -> Unit,
    onAddBook: () -> Unit,
    onBookActions: (BookEntity) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize().systemBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            // Extra room at the foot so the last row never sits under the add button.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(books, key = { it.id }) { book ->
                BookCell(
                    book = book,
                    transfer = transfers[book.id],
                    onClick = { onOpenBook(book) },
                    onActions = { onBookActions(book) },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (books.isEmpty()) {
            Text(
                text = "Nothing on the shelf yet.\nAdd an EPUB with +, or share one to Bookies.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            )
        }

        // Small, and in the shelf's own colours rather than Material's default container:
        // the library is the subject here, these are only the ways in. Glyphs rather than
        // Icons keep material-icons off the dependency list.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            // Search spans the whole library, archived books included — which is the
            // point of keeping annotations on the device after the EPUB leaves. It
            // belongs on the shelf rather than inside a book for exactly that reason.
            SmallFloatingActionButton(
                onClick = onSearch,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                MagnifierGlyph(tint = MaterialTheme.colorScheme.primary)
            }

            SmallFloatingActionButton(
                onClick = onAddBook,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.background
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/**
 * A magnifier, drawn rather than typed.
 *
 * U+2315 renders as a broken box in the system font on Android 15, and pulling in
 * material-icons for one shape is a dependency this project deliberately does without.
 * Two strokes are cheaper than either.
 */
@Composable
private fun MagnifierGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val r = size.minDimension * 0.34f
        val centre = Offset(size.width * 0.42f, size.height * 0.42f)
        val stroke = size.minDimension * 0.10f
        drawCircle(color = tint, radius = r, center = centre, style = Stroke(width = stroke))
        drawLine(
            color = tint,
            start = Offset(centre.x + r * 0.72f, centre.y + r * 0.72f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * A cover: the art, a spine shadow down the hinge edge, and the title on a plain board
 * when there is no art. Shared by the shelf cell and by the leaf [BookOpenTransition]
 * swings, so the thing that opens is visibly the thing that was tapped.
 */
@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
            .background(MaterialTheme.colorScheme.primary)
            // A darker band down the left edge reads as the spine and gives the
            // hinge something to pivot around when the cover opens.
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.06f to Color.Transparent
                    )
                )
            }
    ) {
        if (book.coverPath != null) {
            AsyncImage(
                model = book.coverPath,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = book.title,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun BookCell(
    book: BookEntity,
    transfer: ShelfViewModel.Transfer?,
    onClick: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shelfColor = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                // The plank is drawn behind each cell rather than as a composable row:
                // one draw call, and it survives the grid's arbitrary column count.
                .drawBehind {
                    val plank = 10.dp.toPx()
                    drawRect(
                        color = shelfColor,
                        topLeft = Offset(0f, size.height),
                        size = Size(size.width, plank)
                    )
                }
        ) {
            BookCover(book = book, modifier = Modifier.fillMaxSize())

            if (book.storageState != StorageState.LOCAL) {
                Text(
                    text = when (book.storageState) {
                        StorageState.MISSING -> "!"
                        else -> "☁"
                    },
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                )
            }

            transfer?.let { t ->
                LinearProgressIndicator(
                    progress = { t.fraction ?: 0f },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }

            // Archive, restore and export live here rather than behind a long-press:
            // archiving is the point of the app, and an invisible gesture is not a way in.
            BookActionsButton(
                onClick = onActions,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            )
        }
    }
}
