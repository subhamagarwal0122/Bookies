package com.bookies.reader.ui.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
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
    modifier: Modifier = Modifier
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(books, key = { it.id }) { book ->
            BookCell(
                book = book,
                transfer = transfers[book.id],
                onClick = { onOpenBook(book) },
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun BookCell(
    book: BookEntity,
    transfer: ShelfViewModel.Transfer?,
    onClick: () -> Unit,
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
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
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
            AsyncImage(
                model = book.coverPath,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

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
        }
    }
}
