package com.bookies.reader.ui.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookies.reader.BookiesApp
import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.db.BookEntity
import com.bookies.reader.data.model.Anchor
import com.bookies.reader.data.model.AnnotationSource
import com.bookies.reader.data.model.AnnotationType
import com.bookies.reader.epub.TextAnchoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.UUID

/**
 * Everything the reading surface does that is not layout.
 *
 * It owns the [Publication] (which holds an open zip and must be closed), the navigator
 * fragment's factory, the debounced position writes and annotation creation. The screen
 * itself stays a thin Compose shell over this, because a ViewModel survives the
 * configuration changes that would otherwise re-open the EPUB on every rotation.
 */
internal class ReaderViewModel(
    private val app: BookiesApp,
    private val book: BookEntity
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(
            val publication: Publication,
            val fragmentFactory: FragmentFactory
        ) : State
        data class Failed(val reason: String) : State
    }

    private val books = app.database.books()
    private val annotationsDao = app.database.annotations()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** One line of feedback, shown and dropped — the same contract the shelf uses. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /**
     * A captured selection waiting for the reader to say what to do with it.
     *
     * Captured, not live: the moment the annotate toolbar appears the WebView's own
     * selection is cleared, so the locator has to be held here or choosing a highlight
     * colour would have nothing left to attach to.
     */
    private val _pendingSelection = MutableStateFlow<Locator?>(null)
    val pendingSelection: StateFlow<Locator?> = _pendingSelection.asStateFlow()

    val annotations: StateFlow<List<AnnotationEntity>> =
        annotationsDao.forBook(book.id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var navigator: EpubNavigatorFragment? = null
    private var navigatorJobs: Job? = null

    /**
     * The annotation the index asked us to open on, until it has been jumped to.
     *
     * Held rather than taken as a constructor argument so that one ViewModel — and so one
     * open publication — serves every trip into this book. Re-entering the reader from a
     * different annotation reuses the archive that is already open.
     */
    private var pendingJump: AnnotationEntity? = null

    private val throttle = ProgressThrottle()

    private val prefs = ReaderPrefs(app)

    /** One continuous scroll, or turned pages. Readium's own default is pages. */
    private val _scroll = MutableStateFlow(prefs.scroll)
    val scroll: StateFlow<Boolean> = _scroll.asStateFlow()

    /**
     * Held so it can be taken off the previous fragment. A configuration change hands us
     * a new navigator over the same publication, and listeners do not follow it across.
     */
    private var tapListener: InputListener? = null

    /** Never regress the shelf to 0% because one locator arrived without a position. */
    private var lastProgression: Double = book.progression

    /**
     * Position writes run here rather than on [viewModelScope].
     *
     * The last write is the one that matters — it happens as the reader closes, which is
     * exactly when the ViewModel is being cleared and its scope cancelled. A bounded
     * scope that outlives the ViewModel by one database write is the cheapest way to not
     * lose the position the reader most wants back.
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch { open() }
    }

    // --- opening ------------------------------------------------------------------------

    private suspend fun open() {
        val path = book.localFilePath
        if (path == null) {
            _state.value = State.Failed("This book's file is not on the device")
            return
        }

        val result = withContext(Dispatchers.IO) { openPublication(File(path)) }
        _state.value = result.fold(
            onSuccess = { publication ->
                State.Ready(
                    publication = publication,
                    fragmentFactory = EpubNavigatorFactory(
                        publication = publication,
                        configuration = EpubNavigatorFactory.Configuration()
                    ).createFragmentFactory(
                        initialLocator = startingLocator(),
                        initialPreferences = EpubPreferences(scroll = _scroll.value),
                        configuration = EpubNavigatorFragment.Configuration {
                            // Replacing the system text-selection menu is the only hook
                            // Readium gives into a WebView selection. It carries a single
                            // item, because the real choices — which colour, note or
                            // bookmark — belong in the app's own toolbar rather than in a
                            // platform popup that cannot render a palette.
                            selectionActionModeCallback = annotateActionMode()
                        }
                    )
                )
            },
            onFailure = { State.Failed(it.message ?: "This EPUB could not be opened") }
        )
    }

    /**
     * Both halves return [org.readium.r2.shared.util.Try] rather than throwing, so every
     * failure is a value here and none of it can escape as an exception.
     */
    private suspend fun openPublication(file: File): Result<Publication> {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(app.contentResolver, httpClient)

        val asset = assetRetriever.retrieve(file).getOrNull()
            ?: return Result.failure(IllegalStateException("The file could not be read"))

        val opener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = app,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )
        )
        val opened = opener.open(asset, allowUserInteraction = false)
        val publication = opened.getOrNull()
            ?: return Result.failure(
                IllegalStateException(opened.failureOrNull()?.message ?: "Unsupported file")
            )
        return Result.success(publication)
    }

    /**
     * Where the book opens: the annotation the index tapped, else where reading stopped.
     *
     * Only ever a hint. If the stored locator no longer addresses anything in this build
     * of the book, Readium lands at the start of the resource and [jumpToOpenAt] takes
     * over with the re-anchoring path.
     */
    private fun startingLocator(): Locator? =
        pendingJump?.let(::storedLocator) ?: book.lastLocatorJson?.let(::parseLocator)

    /**
     * Backlog item 4 arriving from the index: open on this annotation rather than where
     * reading stopped. Null resumes, which is what the "Read" button wants.
     */
    fun openOn(annotation: AnnotationEntity?) {
        if (annotation == null) return
        pendingJump = annotation
        navigator?.let { fragment -> viewModelScope.launch { consumeJump(fragment) } }
    }

    // --- the navigator ------------------------------------------------------------------

    /**
     * Called every time Compose hands us the fragment, including after a configuration
     * change, when it is a different instance wrapping the same publication.
     */
    fun bind(fragment: EpubNavigatorFragment) {
        if (navigator === fragment) return
        tapListener?.let { navigator?.removeInputListener(it) }
        navigator = fragment
        installTapToTurn(fragment)
        navigatorJobs?.cancel()
        navigatorJobs = viewModelScope.launch {
            launch { observePosition(fragment) }
            launch { observeDecorations(fragment) }
            consumeJump(fragment)
        }
    }

    private suspend fun consumeJump(fragment: EpubNavigatorFragment) {
        val target = pendingJump ?: return
        pendingJump = null
        jumpToOpenAt(fragment, target)
    }

    private suspend fun observePosition(fragment: EpubNavigatorFragment) {
        fragment.currentLocator.collect { locator ->
            // totalProgression, never progression: the latter is progress within the
            // current spine item, and chapter two of twelve would be written to the
            // database, the bundles and every export as 80% read.
            locator.locations.totalProgression?.let { lastProgression = it }
            val json = locator.toJSON().toString()
            if (throttle.shouldWrite(json, System.currentTimeMillis())) {
                writeScope.launch { books.saveProgress(book.id, lastProgression, json) }
            }
        }
    }

    private suspend fun observeDecorations(fragment: EpubNavigatorFragment) {
        annotations.collect { rows ->
            fragment.applyDecorations(decorationsFor(rows), group = DECORATION_GROUP)
        }
    }

    /**
     * Tapping the outer quarter of the page turns it.
     *
     * Without this the only way to move through a paginated book is a horizontal swipe,
     * which nothing on screen suggests — the first thing most readers try is a tap, and
     * the second is a scroll. The middle half is left alone so that tapping a word still
     * reaches the text selection underneath.
     */
    private fun installTapToTurn(fragment: EpubNavigatorFragment) {
        val listener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                // In scroll mode a tap is not a page turn; the content moves under the
                // finger instead, and stealing the tap would break link taps.
                if (_scroll.value) return false
                val width = fragment.view?.width?.toFloat() ?: return false
                if (width <= 0f) return false
                return when {
                    event.point.x < width * EDGE -> fragment.goBackward(animated = true)
                    event.point.x > width * (1f - EDGE) -> fragment.goForward(animated = true)
                    else -> false
                }
            }
        }
        fragment.addInputListener(listener)
        tapListener = listener
    }

    /**
     * Switches between pages and one continuous scroll, live. The navigator keeps its
     * position across the change, so this does not cost the reader their place.
     */
    fun toggleScroll() {
        val next = !_scroll.value
        _scroll.value = next
        prefs.scroll = next
        navigator?.submitPreferences(EpubPreferences(scroll = next))
    }

    /**
     * Flushes the position the throttle is holding back. Called as the reader closes.
     */
    fun flush() {
        val locator = navigator?.currentLocator?.value ?: return
        val json = locator.toJSON().toString()
        if (throttle.shouldWrite(json, System.currentTimeMillis(), force = true)) {
            writeScope.launch { books.saveProgress(book.id, lastProgression, json) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        flush()
        navigator = null
        // The publication holds an open archive; leaking it leaks a file handle per book
        // opened, which on a long session is a real limit rather than a theoretical one.
        (state.value as? State.Ready)?.publication?.close()
    }

    // --- jumping ------------------------------------------------------------------------

    /**
     * Backlog item 5, and the only caller [TextAnchoring.resolve] has ever had.
     *
     * `go()` returning false means the stored href does not address anything in this
     * build of the book — the EPUB was re-downloaded and its spine split differently, or
     * the annotation came from Kindle and never had a locator at all. The quote and its
     * context are what survive that, so they are what we search on.
     */
    private suspend fun jumpToOpenAt(fragment: EpubNavigatorFragment, annotation: AnnotationEntity) {
        val stored = storedLocator(annotation)
        if (stored != null && fragment.go(stored, false)) return

        val rebuilt = reanchor(annotation)
        if (rebuilt == null || !fragment.go(rebuilt, false)) {
            _message.value = "That passage is not in this copy of the book"
            return
        }
        // Worth saying: the reader is looking at text that was found by similarity, not
        // by the address the annotation was made with.
        _message.value = "Found that passage again in a different edition"
    }

    fun jumpTo(annotation: AnnotationEntity) = viewModelScope.launch {
        navigator?.let { jumpToOpenAt(it, annotation) }
    }

    /**
     * Re-finds an annotation's text in the open publication.
     *
     * Its own spine item first: in the ordinary case — the same book, a rebuilt file —
     * that is one resource's worth of work rather than the whole reading order. Only a
     * complete miss pays for the full scan.
     */
    private suspend fun reanchor(annotation: AnnotationEntity): Locator? = withContext(Dispatchers.IO) {
        val publication = (state.value as? State.Ready)?.publication ?: return@withContext null
        val anchor = anchorOf(annotation)
        if (anchor.quote.isBlank()) return@withContext null

        val spine = publication.readingOrder.map { hrefOf(it) }

        annotation.href
            ?.let { own -> chapterText(publication, own)?.let { reanchorIn(own, it, anchor) } }
            ?.let { return@withContext it.toLocator() }

        val found = spine.mapNotNull { href ->
            chapterText(publication, href)?.let { reanchorIn(href, it, anchor) }
        }
        bestReanchor(found, spine)?.toLocator()
    }

    private fun Reanchored.toLocator(): Locator? {
        val url = Url(href) ?: return null
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            // progression here, not totalProgression: a Locator's `progression` is
            // within-resource by definition, and a chapter's text is all we searched.
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(
                before = passage.prefix,
                highlight = passage.quote,
                after = passage.suffix
            )
        )
    }

    // --- annotations --------------------------------------------------------------------

    /**
     * The one item on the selection action mode. Capturing is a suspend call, so the mode
     * is finished from inside the coroutine — finishing first would clear the selection
     * before it had been read.
     *
     * Built on demand rather than held in a property: `init` starts the open immediately,
     * and a property declared below it would still be null when the factory asked for it.
     */
    private fun annotateActionMode() = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ANNOTATE_ITEM, Menu.NONE, "Annotate")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ANNOTATE_ITEM) return false
            captureSelection { mode.finish() }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private fun captureSelection(onDone: () -> Unit) = viewModelScope.launch {
        val fragment = navigator
        val selection = fragment?.currentSelection()
        if (selection == null) {
            _message.value = "The selection was lost before it could be saved"
        } else {
            _pendingSelection.value = selection.locator
        }
        fragment?.clearSelection()
        onDone()
    }

    fun discardSelection() {
        _pendingSelection.value = null
    }

    /** Saves the captured selection. [note] turns a highlight into a note. */
    fun saveSelection(type: AnnotationType, colorArgb: Int?, note: String? = null) = viewModelScope.launch {
        val locator = _pendingSelection.value ?: return@launch
        _pendingSelection.value = null
        persist(locator, type, colorArgb, note)
    }

    /**
     * A bookmark marks where the reader is, so it needs no selection. The first visible
     * element gives it a quote, without which it would be an unreadable row in the index.
     */
    fun bookmarkHere() = viewModelScope.launch {
        val fragment = navigator ?: return@launch
        val locator = fragment.firstVisibleElementLocator() ?: fragment.currentLocator.value
        persist(locator, AnnotationType.BOOKMARK, null, null)
    }

    private suspend fun persist(
        locator: Locator,
        type: AnnotationType,
        colorArgb: Int?,
        note: String?
    ) {
        val publication = (state.value as? State.Ready)?.publication ?: return
        val href = locator.href.toString()
        val quote = locator.text.highlight.orEmpty().trim()
        val before = locator.text.before.orEmpty()
        val after = locator.text.after.orEmpty()

        val passage = withContext(Dispatchers.IO) { widen(publication, href, locator, quote, before, after) }

        val now = System.currentTimeMillis()
        annotationsDao.upsert(
            AnnotationEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                type = type,
                colorArgb = colorArgb,
                href = href,
                // Readium addresses a selection by text and progression, not by a CSS
                // selector, so there is nothing honest to put in these three. The quote
                // and its context are the anchor, which is what TextAnchoring resolves on.
                cssSelector = null,
                startOffset = null,
                endOffset = null,
                progression = locator.locations.totalProgression ?: lastProgression,
                quote = passage.quote.ifBlank { quote },
                prefix = passage.prefix,
                suffix = passage.suffix,
                contextBefore = passage.contextBefore,
                contextAfter = passage.contextAfter,
                chapterTitle = locator.title ?: titleFor(publication, href),
                note = note?.takeIf { it.isNotBlank() },
                // Verbatim, so a future reader of this row can navigate with it even if
                // every heuristic in this file has been rewritten by then.
                locatorJson = locator.toJSON().toString(),
                source = AnnotationSource.ANDROID,
                createdAt = now,
                updatedAt = now
            )
        )
        _message.value = when (type) {
            AnnotationType.BOOKMARK -> "Bookmarked"
            AnnotationType.NOTE -> "Note saved"
            AnnotationType.HIGHLIGHT -> "Highlighted"
        }
    }

    /**
     * Turns Readium's narrow selection window into the surrounding paragraphs.
     *
     * The probe deliberately carries `locations.progression`, the within-resource figure,
     * because [TextAnchoring] is searching one chapter's text. This is the one place in
     * the app where that value is the right one, and it never leaves this function.
     */
    private suspend fun widen(
        publication: Publication,
        href: String,
        locator: Locator,
        quote: String,
        before: String,
        after: String
    ): Passage {
        val fallback = Passage(
            quote = quote,
            prefix = before.takeLast(EDGE_CHARS),
            suffix = after.take(EDGE_CHARS),
            contextBefore = before,
            contextAfter = after
        )
        if (quote.isBlank()) return fallback

        val text = chapterText(publication, href) ?: return fallback
        val probe = Anchor(
            href = href,
            quote = quote,
            prefix = before,
            suffix = after,
            progression = locator.locations.progression ?: 0.0
        )
        val match = TextAnchoring.resolve(text, probe) ?: return fallback
        return passageAt(text, match.range)
    }

    // --- decorations --------------------------------------------------------------------

    private fun decorationsFor(rows: List<AnnotationEntity>): List<Decoration> =
        rows.mapNotNull { row ->
            // A bookmark marks a place, not a span; drawing it as a highlight would tint
            // whatever paragraph happened to be at the top of the screen when it was made.
            if (row.type == AnnotationType.BOOKMARK) return@mapNotNull null
            val locator = storedLocator(row) ?: return@mapNotNull null
            Decoration(
                id = row.id,
                locator = locator,
                style = Decoration.Style.Highlight(
                    tint = row.colorArgb ?: DEFAULT_TINT,
                    isActive = false
                )
            )
        }

    // --- helpers ------------------------------------------------------------------------

    private fun storedLocator(annotation: AnnotationEntity): Locator? =
        annotation.locatorJson?.let(::parseLocator)

    private fun parseLocator(json: String): Locator? =
        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    private suspend fun chapterText(publication: Publication, href: String): String? {
        val url = Url(href.substringBefore('#')) ?: return null
        val resource = publication.get(url) ?: return null
        return try {
            resource.read().getOrNull()?.let { plainText(String(it, Charsets.UTF_8)) }
        } finally {
            resource.close()
        }
    }

    private fun hrefOf(link: Link): String = link.href.toString().substringBefore('#')

    /** Chapter titles come from the table of contents, falling back to the spine's own. */
    private fun titleFor(publication: Publication, href: String): String? {
        val target = href.substringBefore('#')
        fun search(links: List<Link>): String? {
            for (link in links) {
                if (hrefOf(link) == target && !link.title.isNullOrBlank()) return link.title
                search(link.children)?.let { return it }
            }
            return null
        }
        return search(publication.tableOfContents)
            ?: publication.readingOrder.firstOrNull { hrefOf(it) == target }?.title
    }

    private companion object {
        /** Readium keys decorations by group; ours are all highlights of annotations. */
        const val DECORATION_GROUP = "annotations"
        const val ANNOTATE_ITEM = 1

        /**
         * How much of each side turns the page. A quarter is wide enough to hit without
         * aiming and narrow enough to leave the text in the middle selectable.
         */
        const val EDGE = 0.25f
    }
}
