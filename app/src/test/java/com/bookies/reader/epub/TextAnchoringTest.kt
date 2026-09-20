package com.bookies.reader.epub

import com.bookies.reader.data.model.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises TextAnchoring against the situations it actually has to survive:
 * ragged whitespace from EPUB markup, a quote that repeats, a different edition with
 * changed punctuation, and text that simply is not there.
 */
class TextAnchoringTest {

    /** Text as it arrives from an EPUB: newlines and indentation from the markup. */
    private val chapter = """
        <p>It was a bright cold day in April, and the clocks were striking thirteen.</p>

        <p>Winston Smith, his chin nuzzled into his breast in an effort to escape the
           vile wind, slipped quickly through the glass doors of Victory Mansions,
           though not quickly enough to prevent a swirl of gritty dust from entering
           along with him.</p>

        <p>The hallway smelt of boiled cabbage and old rag mats. At one end of it a
           coloured poster, too large for indoor display, had been tacked to the wall.</p>

        <p>It was a bright cold day in April, and the clocks were striking thirteen.</p>
    """.trimIndent()

    private fun anchor(
        quote: String, prefix: String = "", suffix: String = "", progression: Double = 0.0
    ) = Anchor(href = "ch01.xhtml", quote = quote, prefix = prefix, suffix = suffix, progression = progression)

    /** What the caller gets back if it slices the chapter with the returned range. */
    private fun slice(range: IntRange) = chapter.substring(range.first, range.last + 1)

    private fun normalized(s: String) = TextAnchoring.normalizeWhitespace(s)

    // --- 1. unique quote, no context needed ---
    @Test
    fun uniqueQuoteResolvesWithoutContext() {
        val m = TextAnchoring.resolve(chapter, anchor("The hallway smelt of boiled cabbage"))
        assertNotNull("resolves", m)
        assertEquals(
            "slice matches quote",
            "The hallway smelt of boiled cabbage",
            normalized(slice(m!!.range))
        )
    }

    // --- 2. quote spanning a markup line break ---
    @Test
    fun quoteSpanningMarkupLineBreakResolves() {
        // Stored normalised; in the source it is split across lines with indentation.
        val quote = "vile wind, slipped quickly through the glass doors"
        val m = TextAnchoring.resolve(chapter, anchor(quote))
        assertNotNull("resolves across ragged whitespace", m)
        assertEquals("slice normalises back to the quote", quote, normalized(slice(m!!.range)))
    }

    // --- 3. repeated quote, disambiguated by context ---
    @Test
    fun repeatedQuoteIsDisambiguatedByContextAndProgression() {
        val quote = "It was a bright cold day in April"
        val first = TextAnchoring.resolve(chapter, anchor(quote, prefix = "<p>", suffix = ", and the clocks"))
        assertNotNull("repeated quote still resolves", first)

        // The second occurrence is the last paragraph. Anchor it by progression.
        val second = TextAnchoring.resolve(chapter, anchor(quote, progression = 0.95))
        assertNotNull("progression resolves", second)
        assertTrue(
            "progression steers to the later occurrence: resolved at ${second!!.range.first} of ${chapter.length}",
            second.range.first > chapter.length / 2
        )
    }

    // --- 4. different edition: punctuation changed ---
    @Test
    fun survivesDifferentEditionWordChange() {
        // Publisher re-encoded straight quotes as curly ones and fixed a word.
        val edited = chapter.replace("boiled cabbage", "boiled cabbages")
        val m = TextAnchoring.resolve(edited, anchor("The hallway smelt of boiled cabbage and old rag mats"))
        assertNotNull("survives a one-word change, similarity=${m?.similarity}", m)
    }

    // --- 5. text genuinely absent ---
    @Test
    fun absentTextReturnsNullRatherThanGuessing() {
        val m = TextAnchoring.resolve(chapter, anchor("a passage from an entirely different book altogether"))
        assertNull("returns null rather than guessing: got range ${m?.range} sim ${m?.similarity}", m)
    }

    // --- 6. similarity scoring ---
    @Test
    fun similarityScoring() {
        assertEquals(
            "identical strings score 1.0",
            1.0, TextAnchoring.similarity("hello world", "hello world"), 0.0
        )
        assertEquals(
            "empty scores 0.0",
            0.0, TextAnchoring.similarity("", "abc"), 0.0
        )
        assertTrue(
            "one edit in eleven chars scores high",
            TextAnchoring.similarity("hello world", "hello worlt") > 0.85
        )
        assertTrue(
            "unrelated strings score low",
            TextAnchoring.similarity("hello world", "goodbye moon") < 0.5
        )
    }

    // --- 7. Kindle-style import: quote only, no context, no offsets ---
    @Test
    fun kindleStyleBareQuoteResolves() {
        val m = TextAnchoring.resolve(chapter, anchor("a swirl of gritty dust from entering"))
        assertNotNull("bare quote resolves", m)
        assertEquals(
            "slice matches",
            "a swirl of gritty dust from entering",
            normalized(slice(m!!.range))
        )
    }

    // --- 8. edge cases ---
    @Test
    fun edgeCases() {
        assertNull("blank quote returns null", TextAnchoring.resolve(chapter, anchor("   ")))
        assertNull("empty document returns null", TextAnchoring.resolve("", anchor("anything at all")))
        val whole = TextAnchoring.resolve(chapter, anchor(normalized(chapter)))
        assertNotNull("whole document as quote resolves", whole)
    }
}
