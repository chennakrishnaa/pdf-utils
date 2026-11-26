package app.venkat.offkit.reader

import app.venkat.offkit.TestPageTexts
import app.venkat.offkit.core.ReaderText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.feature.reader.SearchHit
import app.venkat.offkit.feature.reader.SearchMath
import app.venkat.offkit.feature.reader.SearchResults
import app.venkat.offkit.feature.reader.scanPages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchTest {
    private fun find(query: String, vararg lines: String, limit: Int = SearchMath.MAX_HITS): List<Pair<Int, Int>> {
        val t = TestPageTexts.grid(*lines)
        return SearchMath.find(t.text, SearchMath.lineStarts(t), SearchMath.parse(query)!!, 0, limit).map { it.start to it.endExclusive }
    }

    @Test
    fun caseIsFoldedWithoutLocaleIncludingNonAscii() {
        assertEquals(listOf(0 to 5, 6 to 11), find("hello", "HeLLo hello"))
        assertEquals(listOf(0 to 3), find("ÉTÉ", "été"))
        assertEquals("folded per code point, same length", "istanbul".length, SearchMath.fold("İSTANBUL").length)
        // Final sigma folds like sigma; ß is not expanded to ss.
        assertEquals(listOf(0 to 5), find("ΛΟΓΟΣ", "λογος"))
        assertEquals(listOf(0 to 5), find("λογοσ", "λογος"))
        assertTrue(find("strasse", "straße").isEmpty())
    }

    @Test
    fun queryWhitespaceMatchesAnyRunOfSpacesAndLineBreaks() {
        assertEquals(listOf(0 to 14), find("hello world", "hello  \t world"))
        assertEquals("no-break space", listOf(0 to 11), find("hello world", "hello world"))
        // A phrase that wraps: the lines are concatenated with no character between them.
        assertEquals(listOf(4 to 12), find("brown  fox", "the brown", "fox jumps"))
        assertEquals("trailing space then break", listOf(4 to 13), find("brown fox", "the brown ", "fox jumps"))
        // Without whitespace in the query, the two words must touch on one line.
        assertTrue(find("brownfox", "the brown", "fox jumps").isEmpty())
        assertTrue("a word never spans a line break (no hyphenation)", find("jumping", "jump", "ing").isEmpty())
        assertTrue("whitespace is required between query words", find("a b", "ab").isEmpty())
    }

    @Test
    fun hitsAreLeftmostAndNonOverlapping() {
        assertEquals(listOf(0 to 2, 2 to 4), find("aa", "aaaaa"))
        assertEquals(listOf(0 to 3, 4 to 7), find("aba", "abababa"))
    }

    @Test
    fun aQueryAtThePageEndIsFoundAndOneLongerIsNot() {
        assertEquals(listOf(3 to 6, 6 to 9), find("end", "an end", "end"))
        assertTrue(find("ends", "the end").isEmpty())
    }

    @Test
    fun surrogatePairsAreMatchedWholeAndNeverSplit() {
        val deseretUpper = String(Character.toChars(0x10400))
        val deseretLower = String(Character.toChars(0x10428))
        val emoji = String(Character.toChars(0x1F600))
        assertEquals(listOf(0 to 2), find(deseretUpper, deseretLower))
        assertEquals(listOf(1 to 3), find(emoji, "a$emoji b"))
        // A lone half never matches inside a pair.
        assertTrue(find(emoji.substring(1), "a$emoji").isEmpty())
        assertTrue(find(emoji.substring(0, 1), "a$emoji").isEmpty())
    }

    @Test
    fun emptyOrWhitespaceQueryIsNoQuery() {
        assertNull(SearchMath.parse(""))
        assertNull(SearchMath.parse("  \n\t "))
        assertEquals("a b", SearchMath.parse("  a b ")!!.text)
        assertEquals(listOf(1 to 2), find(" x ", "axa"))
    }

    @Test
    fun hitsStopAtTheLimit() {
        assertEquals(3, find("a", "aaaaaaaa", limit = 3).size)
        assertTrue(find("a", "aaa", limit = 0).isEmpty())
    }

    @Test
    fun scanOrderStartsAtThePageAndWraps() {
        assertEquals(listOf(2, 3, 4, 0, 1), SearchMath.scanOrder(2, 5))
        assertEquals(listOf(0, 1, 2), SearchMath.scanOrder(0, 3))
        assertEquals(listOf(0), SearchMath.scanOrder(7, 1))
        assertTrue(SearchMath.scanOrder(0, 0).isEmpty())
    }

    @Test
    fun nextAndPreviousWrapAround() {
        assertEquals(1, SearchMath.step(3, 0, true))
        assertEquals(0, SearchMath.step(3, 2, true))
        assertEquals(2, SearchMath.step(3, 0, false))
        assertEquals(0, SearchMath.step(3, -1, true))
        assertEquals(2, SearchMath.step(3, -1, false))
        assertEquals(-1, SearchMath.step(0, -1, true))
    }

    @Test
    fun aWrappedScanKeepsDocumentOrderTheFirstHitAndCountsUnreadablePages() = runBlocking {
        val texts = mapOf(0 to "cat", 1 to "dog", 3 to "cat cat", 4 to "a cat")
        val results = SearchResults(SearchMath.parse("CAT")!!)
        val reveals = ArrayList<SearchHit>()
        var turns = 0
        scanPages(
            pages = SearchMath.scanOrder(3, 5),
            results = results,
            textOf = { p ->
                if (p == 2) ReaderText.Err(PdfError.TimedOut) else ReaderText.Ok(TestPageTexts.grid(texts.getValue(p)))
            },
            awaitTurn = { turns++ },
            near = { false },
            publish = {},
            reveal = { hit, _ -> reveals += hit },
        )
        assertEquals(5, turns)
        assertEquals(listOf(SearchHit(3, 0, 3)), reveals)
        assertEquals(listOf(0, 3, 3, 4), results.hits.map { it.page })
        assertEquals(SearchHit(3, 0, 3), results.current)
        assertEquals("index after wrapped hits were inserted before it", 1, results.state().currentIndex)
        assertEquals(1, results.skipped)
        assertFalse(results.scanning)
        assertTrue("no rectangles for pages far from the viewport", results.rects.isEmpty())
    }

    @Test
    fun theScanStopsAtTheHitCap() = runBlocking {
        val results = SearchResults(SearchMath.parse("a")!!)
        val page = TestPageTexts.grid("a".repeat(4_000))
        var read = 0
        scanPages(
            SearchMath.scanOrder(0, 10), results, { read++; ReaderText.Ok(page) }, {}, { false }, {}, { _, _ -> },
        )
        assertEquals(SearchMath.MAX_HITS, results.hits.size)
        assertTrue(results.capped)
        assertEquals(3, read)
    }
}
