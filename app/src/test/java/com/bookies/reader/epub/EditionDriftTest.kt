package com.bookies.reader.epub

import com.bookies.reader.data.model.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The question this answers: if you archive a book, delete it, later re-download a
 * DIFFERENT build of the same title and restore your annotations — what fraction
 * actually land back on the right words?
 *
 * The mutations below are the ones that really differ between EPUB builds: typographic
 * quotes, dashes, re-flowed line wrapping, an added heading that shifts every offset,
 * and the odd corrected typo.
 */
class EditionDriftTest {

    private val paragraphs = listOf(
        "It was a bright cold day in April, and the clocks were striking thirteen.",
        "Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, slipped quickly through the glass doors of Victory Mansions.",
        "The hallway smelt of boiled cabbage and old rag mats. At one end of it a coloured poster, too large for indoor display, had been tacked to the wall.",
        "It depicted simply an enormous face, more than a metre wide: the face of a man of about forty-five, with a heavy black moustache and ruggedly handsome features.",
        "Winston made for the stairs. It was no use trying the lift, for even at the best of times it was seldom working.",
        "The flat was seven flights up, and Winston, who was thirty-nine and had a varicose ulcer above his right ankle, went slowly.",
        "Outside, even through the shut window-pane, the world looked cold, and little eddies of wind were whirling dust and torn paper into spirals.",
        "The black-moustachio'd face gazed down from every commanding corner, and there was one right opposite the lift shaft.",
        "Inside the flat a fruity voice was reading out a list of figures which had something to do with the production of pig-iron.",
        "The voice came from an oblong metal plaque like a dulled mirror which formed part of the surface of the right-hand wall."
    )

    private fun build(paragraphs: List<String>, wrapAt: Int, heading: String?) = buildString {
        heading?.let { append("    <h1>").append(it).append("</h1>\n\n") }
        for ((i, p) in paragraphs.withIndex()) {
            append("    <p class=\"p$i\">\n       ")
            append(p.chunked(wrapAt).joinToString("\n       "))
            append("\n    </p>\n\n")
        }
    }

    /** The build the reader originally annotated. */
    private val original = build(paragraphs, wrapAt = 48, heading = null)

    /** A different publisher's build of the same text. */
    private val reissue = build(
        paragraphs.map { p ->
            p.replace("'", "’")              // curly apostrophes
                .replace(" - ", " — ")       // em dashes
                .replace("coloured", "colored")   // US edition
                .replace("metre", "meter")
                .replace("moustache", "mustache")
        },
        wrapAt = 61,                              // different line wrapping
        heading = "Chapter One"                   // shifts every single offset
    )

    @Test
    fun annotationsRestoreIntoADifferentBuildWithoutMisAnchoring() {
        val random = Random(7)
        val words = TextAnchoring.normalizeWhitespace(original).split(' ')

        var attempted = 0
        var recovered = 0
        var wrong = 0
        val examples = mutableListOf<String>()
        val gaveUp = mutableListOf<String>()

        repeat(300) {
            val start = random.nextInt(words.size - 8)
            val end = minOf(words.size, start + 4 + random.nextInt(10))
            val quote = words.subList(start, end).joinToString(" ")
            if (quote.length < 12) return@repeat

            val anchor = Anchor(
                href = "ch01.xhtml",
                quote = quote,
                prefix = words.subList(maxOf(0, start - 5), start).joinToString(" "),
                suffix = words.subList(end, minOf(words.size, end + 5)).joinToString(" "),
                progression = start.toDouble() / words.size
            )

            attempted++
            val match = TextAnchoring.resolve(reissue, anchor)
            if (match == null) {
                gaveUp += quote.take(70)
                return@repeat
            }

            val got = TextAnchoring.normalizeWhitespace(reissue.substring(match.range.first, match.range.last + 1))
            // "Landed correctly" means the recovered span is the same passage, allowing for
            // the very mutations we applied. Anything below that is a mis-anchor, which is
            // worse than failing outright.
            val sim = TextAnchoring.similarity(got, quote)
            if (sim >= 0.7) recovered++
            else {
                wrong++
                if (examples.size < 5) examples += "sim=%.2f want='%s' got='%s'".format(sim, quote.take(50), got.take(50))
            }
        }

        assertTrue("attempted at least one restoration", attempted > 0)

        // A mis-anchor silently points an annotation at the wrong words, which is worse
        // than admitting failure. That is the number that must be zero.
        assertEquals(
            "mis-anchors must be zero:\n" + examples.joinToString("\n"),
            0, wrong
        )
        // And in practice nothing should give up either: full recovery across the drift.
        assertEquals(
            "every annotation must be recovered; gave up on:\n" + gaveUp.take(5).joinToString("\n"),
            attempted, recovered
        )
    }
}
