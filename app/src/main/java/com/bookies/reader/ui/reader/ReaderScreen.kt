package com.bookies.reader.ui.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.AnnotationType
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The reading surface.
 *
 * A thin shell: the publication, the navigator's factory, the debounced position writes
 * and annotation creation all live in [ReaderViewModel], because none of that survives a
 * rotation if it is held in composition and all of it is worth not re-doing.
 *
 * Deliberately no BackHandler. MainActivity owns a single app-wide one; a second handler
 * here would resolve by composition order, which is the bug this app has already been
 * through once. Leaving is [onClose]'s job and the caller's decision.
 */
@Composable
fun ReaderScreen(
    book: BookEntity,
    openAt: AnnotationEntity?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as BookiesApp
    val activity = remember(context) { context.findFragmentActivity() }

    // Keyed on the book, not on [openAt]: one ViewModel per book means one open archive
    // per book, however many times the index sends the reader back into it.
    val viewModel: ReaderViewModel = viewModel(
        key = "reader:${book.id}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(app, book) as T
        }
    )

    LaunchedEffect(openAt?.id) { viewModel.openOn(openAt) }

    // The last few seconds of reading are always inside the write throttle's window, so
    // leaving has to push them through or the book reopens a page or two behind.
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingSelection by viewModel.pendingSelection.collectAsStateWithLifecycle()
    val scrolling by viewModel.scroll.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            ReaderBar(
                title = book.title,
                canBookmark = state is ReaderViewModel.State.Ready,
                scrolling = scrolling,
                onClose = onClose,
                onToggleScroll = viewModel::toggleScroll,
                onBookmark = viewModel::bookmarkHere
            )

            // weight, not fillMaxSize: the bar above has already taken its 48dp out of
            // the column, and a second child asking for the whole height pushes the
            // navigator off the bottom of the screen.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (val current = state) {
                    is ReaderViewModel.State.Loading ->
                        Notice("Opening “${book.title}”…")

                    is ReaderViewModel.State.Failed ->
                        Notice(current.reason)

                    is ReaderViewModel.State.Ready ->
                        if (activity == null) {
                            // Worth naming rather than showing a blank page: AndroidFragment
                            // needs a FragmentActivity to find a FragmentManager in, and a
                            // plain ComponentActivity gives it nowhere to look.
                            Notice("The reader needs a FragmentActivity host")
                        } else {
                            Navigator(
                                activity = activity,
                                ready = current,
                                onFragment = viewModel::bind
                            )
                        }
                }
            }
        }

        if (pendingSelection != null) {
            SelectionToolbar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onHighlight = { tint -> viewModel.saveSelection(AnnotationType.HIGHLIGHT, tint) },
                onNote = { tint, note -> viewModel.saveSelection(AnnotationType.NOTE, tint, note) },
                onBookmark = { viewModel.saveSelection(AnnotationType.BOOKMARK, null) },
                onDismiss = viewModel::discardSelection
            )
        }

        message?.let { text ->
            // Same five-second contract the shelf uses, and deliberately not a full-screen
            // host: an invisible one over the navigator would eat the page-turn taps.
            LaunchedEffect(text) {
                delay(3_000)
                viewModel.messageShown()
            }
            Text(
                text = text,
                color = MaterialTheme.colorScheme.background,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Hosts Readium's navigator fragment.
 *
 * The factory has to be on the FragmentManager *before* `AndroidFragment` composes:
 * AndroidFragment asks `fragmentManager.fragmentFactory` for the fragment by class name,
 * and EpubNavigatorFragment cannot be built without its publication. That is why this is
 * a `remember` doing work rather than a `LaunchedEffect` — effects run after composition,
 * by which time AndroidFragment has already asked.
 */
@Composable
private fun Navigator(
    activity: FragmentActivity,
    ready: ReaderViewModel.State.Ready,
    onFragment: (EpubNavigatorFragment) -> Unit
) {
    remember(ready.fragmentFactory) {
        val manager = activity.supportFragmentManager
        manager.discardRestoredNavigator()
        NavigatorFragments.delegate = ready.fragmentFactory
        manager.fragmentFactory = NavigatorFragments
        ready.fragmentFactory
    }

    DisposableEffect(ready.fragmentFactory) {
        onDispose {
            // The publication behind this factory is about to be closed; leaving the
            // factory installed would let a later restore build a navigator over it.
            NavigatorFragments.delegate = null
        }
    }

    AndroidFragment<EpubNavigatorFragment>(
        modifier = Modifier.fillMaxSize(),
        onUpdate = onFragment
    )
}

@Composable
private fun ReaderBar(
    title: String,
    canBookmark: Boolean,
    scrolling: Boolean,
    onClose: () -> Unit,
    onToggleScroll: () -> Unit,
    onBookmark: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onClose) { Text("Index") }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (canBookmark) {
            // Named for what tapping it gives you, not for the state you are in: a
            // control labelled with its current mode is read as a label half the time.
            TextButton(onClick = onToggleScroll) {
                Text(if (scrolling) "Pages" else "Scroll")
            }
            TextButton(onClick = onBookmark) { Text("Bookmark") }
        }
    }
}

/**
 * What a selection turns into.
 *
 * Readium's own selection UI is an Android action mode, which can hold a row of words and
 * nothing else — no palette. So the action mode carries a single "Annotate" item whose
 * only job is to capture the locator, and the actual choice is made here, where a colour
 * can be a colour.
 */
@Composable
private fun SelectionToolbar(
    modifier: Modifier = Modifier,
    onHighlight: (Int) -> Unit,
    onNote: (Int, String) -> Unit,
    onBookmark: () -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }
    var tint by remember { mutableStateOf(DEFAULT_TINT) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (option in HIGHLIGHT_TINTS) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(option.argb))
                        .border(
                            width = if (option.argb == tint && writing) 2.dp else 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (option.argb == tint && writing) 0.9f else 0.2f
                            ),
                            shape = CircleShape
                        )
                        .clickable {
                            // Mid-note, a swatch changes the note's colour rather than
                            // saving a bare highlight and throwing the typing away.
                            if (writing) tint = option.argb else onHighlight(option.argb)
                        }
                )
            }
        }

        if (writing) {
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                decorationBox = { field ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(10.dp)
                    ) {
                        if (note.isEmpty()) {
                            Text(
                                "What about this passage?",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        field()
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (writing) onNote(tint, note) else writing = true }) {
                Text(if (writing) "Save note" else "Note")
            }
            TextButton(onClick = onBookmark) { Text("Bookmark") }
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(32.dp)
        )
    }
}

/**
 * Compose's LocalContext is whatever activity or wrapper is hosting us; `AndroidFragment`
 * needs the FragmentActivity underneath it, and there may be theme wrappers in between.
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
