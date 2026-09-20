package com.bookies.reader.data.model

import kotlinx.serialization.Serializable

/**
 * A redundant anchor into an EPUB, modelled on the W3C Web Annotation Data Model.
 *
 * Nothing here is authoritative on its own. Resolution walks down the chain:
 *
 *   1. [cssSelector] + [startOffset]/[endOffset]  — exact, but breaks if the file changes
 *   2. [quote] with [prefix]/[suffix]             — survives re-pagination and re-encoding
 *   3. [progression]                              — last resort, lands you near the passage
 *
 * Step 2 is what lets a highlight made in one build of an EPUB re-attach to a different
 * build years later, and what lets a Kindle highlight (which only gives us the text)
 * become a real clickable locator. See TextAnchoring.
 */
@Serializable
data class Anchor(
    /** Spine item path inside the EPUB, e.g. "OEBPS/ch07.xhtml". */
    val href: String,
    val cssSelector: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,

    /** The selected text itself. Never empty for highlights. */
    val quote: String,
    /** ~32 chars immediately before/after [quote] — the disambiguators. */
    val prefix: String = "",
    val suffix: String = "",

    /** 0.0–1.0 through the spine item. Drives index ordering, so always populate it. */
    val progression: Double = 0.0
)

/**
 * The surrounding prose, captured once at creation time.
 *
 * This is what makes an annotation readable when the EPUB is gone — the whole point of
 * the archive model. A couple of KB per annotation; do not be tempted to trim it.
 */
@Serializable
data class Context(
    val before: String = "",
    val after: String = ""
)
