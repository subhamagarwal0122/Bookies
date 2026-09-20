package com.bookies.reader.epub

import com.bookies.reader.data.model.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property test: pull real substrings out of a document, anchor them, resolve them back,
 * and assert the invariants a caller depends on. This is what shakes out off-by-ones in
 * the normalised-offset mapping, which unit tests with hand-picked strings will miss.
 */
class AnchoringPropertyTest {

    private val doc = buildString {
        val paragraphs = listOf(
            "It was a bright cold day in April, and the clocks were striking thirteen.",
            "Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, slipped quickly through the glass doors.",
            "The hallway smelt of boiled cabbage and old rag mats. At one end of it a coloured poster had been tacked to the wall.",
            "It depicted simply an enormous face, more than a metre wide: the face of a man of about forty-five, with a heavy black moustache.",
            "Winston made for the stairs. It was no use trying the lift, for even at the best of times it was seldom working.",
            "The flat was seven flights up, and Winston, who was thirty-nine, went slowly, resting several times on the way.",
            "Outside, even through the shut window-pane, the world looked cold, and little eddies of wind were whirling dust and torn paper.",
            "The black-moustachio'd face gazed down from every commanding corner, and there was one right opposite."
        )
        for ((i, p) in paragraphs.withIndex()) {
            append("    <p class=\"c$i\">\n       ")
            // Wrap with ragged indentation, as real extracted EPUB text arrives.
            append(p.chunked(48).joinToString("\n       "))
            append("\n    </p>\n\n")
        }
    }

    @Test
    fun everyDrawnSubstringRoundTrips() {
        val random = Random(20260920)
        var checked = 0
        var resolved = 0
        val failures = mutableListOf<String>()

        val plain = TextAnchoring.normalizeWhitespace(doc)

        repeat(400) {
            // Draw a random word-aligned span of the normalised text as the "selection".
            val words = plain.split(' ')
            val start = random.nextInt(words.size - 6)
            val end = minOf(words.size, start + 3 + random.nextInt(12))
            val quote = words.subList(start, end).joinToString(" ")
            if (quote.isBlank() || quote.length < 8) return@repeat

            val prefixWords = words.subList(maxOf(0, start - 5), start)
            val suffixWords = words.subList(end, minOf(words.size, end + 5))

            val anchor = Anchor(
                href = "ch.xhtml",
                quote = quote,
                prefix = prefixWords.joinToString(" "),
                suffix = suffixWords.joinToString(" "),
                progression = start.toDouble() / words.size
            )

            checked++
            val match = TextAnchoring.resolve(doc, anchor) ?: return@repeat
            resolved++

            val r = match.range
            if (r.first < 0 || r.last >= doc.length || r.first > r.last) {
                failures += "range out of bounds: $r for doc of ${doc.length} — quote='${quote.take(40)}'"
                return@repeat
            }

            val got = TextAnchoring.normalizeWhitespace(doc.substring(r.first, r.last + 1))
            if (got != quote) {
                val sim = TextAnchoring.similarity(got, quote)
                failures += "slice mismatch (sim=%.3f)\n      want: '%s'\n      got:  '%s'"
                    .format(sim, quote.take(60), got.take(60))
            }
        }

        assertTrue("drew at least one selection", checked > 0)
        // Every anchor drawn from the document itself must resolve, and every resolved
        // range must be in bounds and normalise back to exactly the quote.
        assertEquals(
            "invariant violations:\n" + failures.take(6).joinToString("\n") +
                if (failures.size > 6) "\n... and ${failures.size - 6} more" else "",
            0, failures.size
        )
        assertEquals("every drawn selection resolved", checked, resolved)
    }
}
