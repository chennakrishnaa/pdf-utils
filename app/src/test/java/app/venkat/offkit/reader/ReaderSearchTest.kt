package app.venkat.offkit.reader

import app.venkat.offkit.TestPageTexts
import app.venkat.offkit.core.ReaderText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.feature.reader.SearchHit
import app.venkat.offkit.feature.reader.SearchMath
import app.venkat.offkit.feature.reader.SearchResults
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.TextRect
import app.venkat.offkit.feature.reader.scanPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

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

    @Test
    fun findCallsItsCheckpointThroughALongPageAndStopsWhenItThrows() {
        val t = TestPageTexts.grid("ab ".repeat(10_000))
        val q = SearchMath.parse("ab")!!
        var calls = 0
        val all = SearchMath.find(t.text, SearchMath.lineStarts(t), q, 0, checkpoint = { calls++ })
        assertEquals(10_000, all.size)
        // Once at the start, then about every CHECK_EVERY characters (a hit can step past the mark).
        assertTrue("$calls checkpoints", calls >= 30_000 / (SearchMath.CHECK_EVERY + 2) && calls <= 1 + 30_000 / SearchMath.CHECK_EVERY)

        calls = 0
        try {
            SearchMath.find(t.text, SearchMath.lineStarts(t), q, 0, checkpoint = { if (++calls == 3) throw CancellationException("stop") })
            fail("find went on after its checkpoint threw")
        } catch (_: CancellationException) {
        }
        assertEquals(3, calls)
    }

    @Test
    fun matchingAndRectanglesRunOnTheComputeDispatcherAndResultsOnTheCaller() = runBlocking {
        val compute = RecordingDispatcher()
        try {
            val caller = Thread.currentThread().name
            val callerThreads = HashSet<String>()
            val results = SearchResults(SearchMath.parse("cat")!!)
            val reveals = ArrayList<Pair<SearchHit, List<TextRect>?>>()
            scanPages(
                pages = SearchMath.scanOrder(1, 3),
                results = results,
                textOf = { ReaderText.Ok(TestPageTexts.grid("a cat", "cat")) },
                awaitTurn = {},
                near = { p ->
                    callerThreads += Thread.currentThread().name
                    assertTrue("a page's hits are recorded only after its off-thread work", results.hits.none { it.page == p })
                    p == 1
                },
                publish = { callerThreads += Thread.currentThread().name },
                reveal = { hit, rects -> callerThreads += Thread.currentThread().name; reveals += hit to rects },
                compute = compute,
            )
            assertEquals("results, near, publish and reveal stay on the caller", setOf(caller), callerThreads)
            assertEquals(setOf(RecordingDispatcher.THREAD), compute.threads.toSet())
            assertEquals("one matching task per page, one rectangles task for the page near the viewport", 4, compute.threads.size)
            // Same results as when run inline.
            assertEquals(listOf(0, 0, 1, 1, 2, 2), results.hits.map { it.page })
            assertEquals(SearchHit(1, 2, 5), results.current)
            assertEquals(setOf(1), results.rects.keys)
            assertEquals(listOf(SearchHit(1, 2, 5) to TestPageTexts.grid("a cat", "cat").rects(2 until 5)), reveals)
        } finally {
            compute.close()
        }
    }

    @Test
    fun cancellingTheScanStopsItInsideALongPage() = runBlocking {
        val compute = RecordingDispatcher()
        try {
            // The longest page text there is; without the in-page checks matching it would take many
            // seconds (about 1e10 comparisons), with them a check comes every ~1e8.
            val page = TestPageTexts.grid("a".repeat(PageTextFormat.MAX_UNITS))
            val results = SearchResults(SearchMath.parse("a".repeat(PageTextFormat.MAX_UNITS / 2) + "b")!!)
            var reads = 0
            var publishes = 0
            val job = launch(Dispatchers.IO) {
                scanPages(
                    SearchMath.scanOrder(0, 3), results, { reads++; ReaderText.Ok(page) }, {}, { false }, { publishes++ }, { _, _ -> },
                    compute = compute,
                )
            }
            assertTrue("matching started", compute.started.await(10, TimeUnit.SECONDS))
            Thread.sleep(300) // well inside the page's matching loop
            val t0 = System.nanoTime()
            job.cancelAndJoin()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("the cancelled scan stopped within the page (took $ms ms)", ms < 3_000)
            assertEquals(1, reads)
            assertEquals(0, publishes)
            assertTrue(results.scanning)
            assertTrue(results.hits.isEmpty())
        } finally {
            compute.close()
        }
    }
}

/** A dispatcher on one thread named [THREAD] that records the thread of every task it runs. */
internal class RecordingDispatcher : CoroutineDispatcher() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, THREAD) }
    val threads: MutableList<String> = CopyOnWriteArrayList()

    /** Counted down when the first task starts. */
    val started = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute {
            threads += Thread.currentThread().name
            started.countDown()
            block.run()
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val THREAD = "search-compute"
    }
}
