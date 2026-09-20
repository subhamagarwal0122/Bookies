package com.bookies.reader.ui.reader

import com.bookies.reader.data.db.AnnotationEntity
import com.bookies.reader.data.model.Anchor
import com.bookies.reader.data.model.AnnotationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three things in the reader that can be wrong without a compiler noticing:
 * the XHTML flattening TextAnchoring is fed, the paragraph widening that makes an
 * annotation readable after its EPUB is archived, and the write throttle.
 */
class ReaderModelTest {

    // --- plainText ---------------------------------------------------------------------

    @Test fun `paragraphs become blank-line separated`() {
        val text = plainText("<html><body><p>One two.</p><p>Three four.</p></body></html>")
        assertEquals("One two.\n\nThree four.", text)
    }

    @Test fun `script and style bodies never reach the text`() {
        val text = plainText(
            "<head><style>p { color: red; }</style><script>var x = 'hidden';</script></head>" +
                "<body><p>Visible.</p></body>"
        )
        assertEquals("Visible.", text)
    }

    @Test fun `inline tags do not split words`() {
        assertEquals("something", plainText("<p>some<em>thing</em></p>"))
    }

    @Test fun `entities are decoded`() {
        assertEquals(
            "Tom & Jerry — “quoted” <tag>",
            plainText("<p>Tom &amp; Jerry &mdash; &ldquo;quoted&rdquo; &lt;tag&gt;</p>")
        )
    }

    @Test fun `numeric entities are decoded in both bases`() {
        assertEquals("AéA", plainText("<p>&#65;&#xe9;&#0065;</p>"))
    }

    @Test fun `comments are dropped`() {
        assertEquals("Kept.", plainText("<p><!-- a note to the typesetter -->Kept.</p>"))
    }

    @Test fun `br becomes a single newline, not a paragraph`() {
        assertEquals("One\nTwo", plainText("<p>One<br/>Two</p>"))
    }

    @Test fun `runs of whitespace collapse but paragraph breaks survive`() {
        val text = plainText("<p>One   \n  two.</p>\n\n\n<p>  Three.  </p>")
        assertEquals("One\ntwo.\n\nThree.", text)
    }

    @Test fun `empty input is empty output`() {
        assertEquals("", plainText(""))
    }

    // --- paragraphSpan -----------------------------------------------------------------

    @Test fun `paragraph span covers only its own paragraph`() {
        val text = "First para.\n\nSecond para here.\n\nThird."
        val at = text.indexOf("para here")
        val span = paragraphSpan(text, at..(at + 3))
        assertEquals("Second para here.", text.substring(span.first, span.last + 1))
    }

    @Test fun `a selection crossing a boundary widens across both paragraphs`() {
        val text = "Alpha one.\n\nBeta two.\n\nGamma three."
        val from = text.indexOf("one")
        val to = text.indexOf("two") + 2
        val span = paragraphSpan(text, from..to)
        assertEquals("Alpha one.\n\nBeta two.", text.substring(span.first, span.last + 1))
    }

    // --- passageAt ---------------------------------------------------------------------

    private val chapter = plainText(
        """
        <p>The first paragraph sets the scene at some length, so that there is genuinely
        something to quote and something to quote around when the selection lands.</p>
        <p>The second paragraph contains the sentence we care about. It was the best of
        times, it was the worst of times, and then it carried on for a while longer.</p>
        <p>The third paragraph follows on afterwards with more prose.</p>
        """.trimIndent()
    )

    private fun rangeOf(needle: String): IntRange {
        val at = chapter.indexOf(needle)
        check(at >= 0) { "fixture does not contain: $needle" }
        return at..(at + needle.length - 1)
    }

    @Test fun `the quote is exactly the selected text`() {
        val needle = "it was the worst of times"
        assertEquals(needle, passageAt(chapter, rangeOf(needle)).quote)
    }

    @Test fun `context reaches the whole surrounding paragraph, not Readium's window`() {
        val passage = passageAt(chapter, rangeOf("it was the worst of times"))
        // Far wider than the few dozen characters a navigator selection carries.
        assertTrue(passage.contextBefore.length > EDGE_CHARS * 2)
        assertTrue(passage.contextBefore.contains("The second paragraph contains"))
        assertTrue(passage.contextAfter.contains("carried on for a while longer"))
    }

    @Test fun `context never runs past the end of its paragraph`() {
        val passage = passageAt(chapter, rangeOf("it was the worst of times"))
        assertFalse(passage.contextAfter.contains("The third paragraph"))
    }

    @Test fun `a quote opening its paragraph still gets lead-in from the one before`() {
        val passage = passageAt(chapter, rangeOf("The second paragraph contains"))
        assertTrue(
            "expected the previous paragraph as lead-in, got: '${passage.contextBefore}'",
            passage.contextBefore.contains("sets the scene")
        )
    }

    @Test fun `prefix and suffix are the short disambiguators`() {
        val passage = passageAt(chapter, rangeOf("it was the worst of times"))
        assertTrue(passage.prefix.length <= EDGE_CHARS)
        assertTrue(passage.suffix.length <= EDGE_CHARS)
        assertTrue(passage.contextBefore.endsWith(passage.prefix))
        assertTrue(passage.contextAfter.startsWith(passage.suffix))
    }

    @Test fun `context is capped so an annotation cannot swallow a chapter`() {
        val long = "word ".repeat(2_000).trim()
        val text = "$long\n\nQUOTE\n\n$long"
        val at = text.indexOf("QUOTE")
        val passage = passageAt(text, at..(at + 4))
        assertTrue(passage.contextBefore.length <= MAX_CONTEXT_CHARS)
        assertTrue(passage.contextAfter.length <= MAX_CONTEXT_CHARS)
    }

    @Test fun `an empty range yields an empty passage rather than throwing`() {
        val passage = passageAt(chapter, IntRange.EMPTY)
        assertEquals("", passage.quote)
    }

    // --- re-anchoring ------------------------------------------------------------------

    private fun annotation(quote: String, prefix: String, suffix: String, progression: Double) =
        AnnotationEntity(
            id = "a1", bookId = "b1", type = AnnotationType.HIGHLIGHT,
            href = "OEBPS/ch02.xhtml", cssSelector = null, startOffset = null, endOffset = null,
            progression = progression, quote = quote, prefix = prefix, suffix = suffix,
            contextBefore = "", contextAfter = "", chapterTitle = null, note = null,
            locatorJson = null, createdAt = 0L, updatedAt = 0L
        )

    @Test fun `a stored annotation re-anchors into a redrawn chapter`() {
        val anchor = anchorOf(annotation("it was the worst of times", "best of times, ", ", and then", 0.5))
        val found = reanchorIn("OEBPS/ch02.xhtml", chapter, anchor)
        assertNotNull(found)
        assertEquals("it was the worst of times", chapter.substring(found!!.range.first, found.range.last + 1))
    }

    @Test fun `re-anchoring survives an edition that repunctuates`() {
        // A curly apostrophe and a spelling change: the same passage, a different build.
        val drifted = chapter.replace("worst", "worste")
        val anchor = anchorOf(annotation("it was the worst of times", "best of times, ", ", and then", 0.5))
        val found = reanchorIn("OEBPS/ch02.xhtml", drifted, anchor)
        assertNotNull(found)
        assertTrue(found!!.similarity < 1.0)
        assertTrue(drifted.substring(found.range.first, found.range.last + 1).contains("worste"))
    }

    @Test fun `text that is simply not there re-anchors to nothing`() {
        val anchor = anchorOf(annotation("a sentence from an entirely different book", "", "", 0.5))
        assertNull(reanchorIn("OEBPS/ch02.xhtml", chapter, anchor))
    }

    @Test fun `re-anchoring reports within-resource progression, never whole-book`() {
        val anchor = anchorOf(annotation("The third paragraph follows", "", "", 0.02))
        val found = reanchorIn("OEBPS/ch02.xhtml", chapter, anchor)!!
        // The stored whole-book progression was 0.02; this figure is about the chapter,
        // and mixing the two is the trap Locator.progression vs totalProgression sets.
        assertTrue(found.progression > 0.5)
        assertTrue(found.progression <= 1.0)
    }

    @Test fun `re-anchoring carries the widened passage with it`() {
        val anchor = anchorOf(annotation("it was the worst of times", "", "", 0.5))
        val found = reanchorIn("OEBPS/ch02.xhtml", chapter, anchor)!!
        assertTrue(found.passage.contextBefore.contains("The second paragraph"))
    }

    @Test fun `progression within a resource is measured from the start of the quote`() {
        assertEquals(0.0, progressionWithin(0..10, 100), 1e-9)
        assertEquals(0.5, progressionWithin(50..60, 100), 1e-9)
        assertEquals(0.0, progressionWithin(0..0, 0), 1e-9)
    }

    @Test fun `the best re-anchor wins on similarity`() {
        val weak = Reanchored("a.xhtml", 0..5, 0.80, 0.1, Passage("", "", "", "", ""))
        val strong = Reanchored("b.xhtml", 0..5, 1.0, 0.1, Passage("", "", "", "", ""))
        assertEquals("b.xhtml", bestReanchor(listOf(weak, strong), listOf("a.xhtml", "b.xhtml"))!!.href)
    }

    @Test fun `an exact tie goes to the earlier spine item`() {
        val front = Reanchored("front.xhtml", 0..5, 1.0, 0.1, Passage("", "", "", "", ""))
        val body = Reanchored("ch09.xhtml", 0..5, 1.0, 0.1, Passage("", "", "", "", ""))
        val order = listOf("front.xhtml", "ch09.xhtml")
        assertEquals("front.xhtml", bestReanchor(listOf(body, front), order)!!.href)
    }

    @Test fun `no candidates means no jump`() {
        assertNull(bestReanchor(emptyList(), listOf("a.xhtml")))
    }

    // --- ProgressThrottle --------------------------------------------------------------

    @Test fun `the first position is always written`() {
        assertTrue(ProgressThrottle().shouldWrite("{\"a\":1}", now = 0L))
    }

    @Test fun `page turns inside the window do not reach the database`() {
        val throttle = ProgressThrottle(intervalMs = 3_000L)
        assertTrue(throttle.shouldWrite("p1", now = 10_000L))
        assertFalse(throttle.shouldWrite("p2", now = 10_500L))
        assertFalse(throttle.shouldWrite("p3", now = 12_000L))
        assertTrue(throttle.shouldWrite("p4", now = 13_000L))
    }

    @Test fun `the same position twice is never written twice`() {
        val throttle = ProgressThrottle(intervalMs = 0L)
        assertTrue(throttle.shouldWrite("p1", now = 0L))
        assertFalse(throttle.shouldWrite("p1", now = 60_000L))
    }

    @Test fun `closing the book flushes whatever the window was holding`() {
        val throttle = ProgressThrottle(intervalMs = 3_000L)
        assertTrue(throttle.shouldWrite("p1", now = 0L))
        assertFalse(throttle.shouldWrite("p2", now = 500L))
        assertEquals("p2", throttle.pending())
        assertTrue(throttle.shouldWrite("p2", now = 600L, force = true))
        assertNull(throttle.pending())
    }

    @Test fun `a forced flush of an unchanged position writes nothing`() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldWrite("p1", now = 0L))
        assertFalse(throttle.shouldWrite("p1", now = 1L, force = true))
    }

    @Test fun `a null locator is never written`() {
        assertFalse(ProgressThrottle().shouldWrite(null, now = 0L))
    }

    // --- palette -----------------------------------------------------------------------

    @Test fun `every highlight tint is opaque and distinct`() {
        val argbs = HIGHLIGHT_TINTS.map { it.argb }
        assertEquals(argbs.size, argbs.toSet().size)
        assertTrue(argbs.all { (it ushr 24) == 0xFF })
        assertEquals(DEFAULT_TINT, HIGHLIGHT_TINTS.first().argb)
    }

    @Test fun `an anchor round-trips out of a stored annotation`() {
        val row = annotation("q", "pre", "suf", 0.42)
        val anchor: Anchor = anchorOf(row)
        assertEquals("OEBPS/ch02.xhtml", anchor.href)
        assertEquals("q", anchor.quote)
        assertEquals("pre", anchor.prefix)
        assertEquals("suf", anchor.suffix)
        assertEquals(0.42, anchor.progression, 1e-9)
    }
}
