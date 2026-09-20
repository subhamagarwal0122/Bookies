package com.bookies.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bookies.reader.data.db.BookEntity

/**
 * The reading surface.
 *
 * STILL A STUB — but no longer a guess. Every signature below was read out of the
 * resolved artifacts (`javap` over readium-{shared,streamer,navigator} 3.0.3), so what
 * remains is Compose glue that needs a real compiler, not API archaeology.
 *
 *  1. Open the publication
 *     `AssetRetriever(contentResolver, DefaultHttpClient())`, then
 *     `.retrieve(file)` → `Try<Asset, RetrieveError>`. Feed the Asset to
 *     `PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory))`
 *     and `.open(asset, allowUserInteraction = false)` → `Try<Publication, OpenError>`.
 *     Both are suspend and both return Try, so neither throws — branch on the failure.
 *
 *  2. Host the navigator
 *     NOT `EpubNavigatorFragment.createFactory(...)`; that no longer exists. In 3.x it is
 *     `EpubNavigatorFactory(publication, EpubNavigatorFactory.Configuration())
 *         .createFragmentFactory(initialLocator, readingOrder, preferences, listener, ...)`
 *     which hands back an `androidx.fragment.app.FragmentFactory`. Place it in Compose via
 *     `AndroidFragment` from androidx.fragment-compose. `Listener` is a marker interface —
 *     it only inherits from OverflowableNavigator.Listener and HyperlinkNavigator.Listener,
 *     so there is nothing mandatory to implement.
 *
 *  3. Persist position
 *     Read `locator.locations.totalProgression`, NOT `locations.progression` — the latter
 *     is progress within the current spine item and would make chapter two of a twelve
 *     chapter book look 80% read. `BookEntity.progression` and `AnnotationEntity.progression`
 *     are both whole-book values. Debounce to a few seconds; this must not write per page turn.
 *
 *  4. Capture a selection
 *     `currentSelection()` is suspend and returns `Selection(locator, rect)`.
 *     `locator.text` is `Locator.Text(before, highlight, after)` — that maps one-to-one onto
 *     `Anchor.prefix/quote/suffix`. Widen to the full surrounding paragraphs before saving:
 *     Readium's default window is too narrow to keep an annotation readable once the EPUB
 *     has been archived, which is the whole standalone-readability guarantee.
 *     Note `locator.href` is a `Url`, not a String; `AnnotationEntity.href` is a String.
 *
 *  5. Draw highlights
 *     `applyDecorations(decorations, group = "highlights")` — suspend, on DecorableNavigator,
 *     which EpubNavigatorFragment implements. One `Decoration(id, locator, style)` per
 *     annotation with `Decoration.Style.Highlight(tint = colorArgb, isActive = false)`.
 *
 *  6. Jump from the index
 *     `go(locator, animated)` returns Boolean and is NOT suspend. A false return is the
 *     signal that the stored locator no longer resolves — a different build of the book —
 *     so that is where `TextAnchoring.resolve` takes over against the chapter text and
 *     rebuilds a locator from the returned range.
 *
 * Remaining unknown: how `AndroidFragment` and the FragmentFactory interact across
 * configuration changes. That needs a compiler and an emulator, not another jar.
 */
@Composable
fun ReaderScreen(book: BookEntity, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Text("Reader for “${book.title}” — Readium navigator goes here.")
    }
}
