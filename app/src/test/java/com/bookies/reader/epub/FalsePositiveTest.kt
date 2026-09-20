package com.bookies.reader.epub

import com.bookies.reader.data.model.Anchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The risk of probing harder is that the matcher starts finding passages that are not
 * there. Feed it text from a DIFFERENT book and assert it declines to match.
 */
class FalsePositiveTest {

    private val orwell = """
        <p>It was a bright cold day in April, and the clocks were striking thirteen.
           Winston Smith, his chin nuzzled into his breast in an effort to escape the
           vile wind, slipped quickly through the glass doors of Victory Mansions.</p>
        <p>The hallway smelt of boiled cabbage and old rag mats. At one end of it a
           coloured poster, too large for indoor display, had been tacked to the wall.</p>
    """.trimIndent()

    private val austen = listOf(
        "It is a truth universally acknowledged that a single man in possession of a good fortune must be in want of a wife",
        "However little known the feelings or views of such a man may be on his first entering a neighbourhood",
        "My dear Mr Bennet said his lady to him one day have you heard that Netherfield Park is let at last",
        "Mr Bennet replied that he had not and do you not want to know who has taken it cried his wife impatiently",
        "Why my dear you must know Mrs Long says that Netherfield is taken by a young man of large fortune from the north",
        "a single man of large fortune four or five thousand a year what a fine thing for our girls",
        "how can you be so tiresome you must know that I am thinking of his marrying one of them",
        "is that his design in settling here design nonsense how can you talk so"
    )

    @Test
    fun austenQuotesNeverMatchInsideOrwell() {
        val random = Random(99)
        var attempted = 0
        var falsePositives = 0
        val examples = mutableListOf<String>()

        for (sentence in austen) {
            val words = sentence.split(' ')
            repeat(40) {
                val start = random.nextInt(maxOf(1, words.size - 5))
                val end = minOf(words.size, start + 4 + random.nextInt(8))
                val quote = words.subList(start, end).joinToString(" ")
                if (quote.length < 12) return@repeat

                attempted++
                // Deliberately plausible context and a mid-document progression, so nothing
                // about the anchor hints that it does not belong here.
                val anchor = Anchor(
                    href = "ch01.xhtml",
                    quote = quote,
                    prefix = words.take(start).takeLast(5).joinToString(" "),
                    suffix = words.drop(end).take(5).joinToString(" "),
                    progression = 0.5
                )
                val match = TextAnchoring.resolve(orwell, anchor)
                if (match != null) {
                    falsePositives++
                    val got = orwell.substring(match.range.first, match.range.last + 1)
                    if (examples.size < 5) {
                        examples += "sim=%.2f  quote='%s'  matched='%s'".format(
                            match.similarity, quote.take(45), TextAnchoring.normalizeWhitespace(got).take(45)
                        )
                    }
                }
            }
        }

        assertTrue("searched a meaningful number of foreign quotes: $attempted", attempted > 300)
        assertEquals(
            "matcher must decline absent text; false positives:\n" + examples.joinToString("\n"),
            0, falsePositives
        )
    }
}
