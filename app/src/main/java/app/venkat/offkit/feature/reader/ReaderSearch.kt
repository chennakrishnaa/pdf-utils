package app.venkat.offkit.feature.reader

import app.venkat.offkit.core.ReaderText
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.TextRect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One search hit: characters [start] until [endExclusive] of page [page]'s [PageText]. */
data class SearchHit(val page: Int, val start: Int, val endExclusive: Int) : Comparable<SearchHit> {
    override fun compareTo(other: SearchHit): Int =
        if (page != other.page) page.compareTo(other.page) else start.compareTo(other.start)
}

/** A parsed query: its folded words (see [SearchMath.fold]), matched in order with whitespace between. */
class SearchQuery internal constructor(val text: String, internal val words: List<String>)

/** Highlight rectangles of the hits of one page near the viewport: [rects] is parallel to [hits]. */
data class PageSearchRects(val hits: List<SearchHit>, val rects: List<List<TextRect>>) {
    fun rectsOf(hit: SearchHit): List<TextRect>? = hits.indexOf(hit).takeIf { it >= 0 }?.let { rects[it] }
}

/**
 * What the search bar and the pages show.
 *
 * @property query the trimmed query being searched.
 * @property hits all hits found so far, in document order (at most [SearchMath.MAX_HITS]).
 * @property current the selected hit, or null (none found yet).
 * @property scanning pages are still being read.
 * @property skipped pages whose text could not be read (not searched).
 * @property capped the scan stopped at [SearchMath.MAX_HITS] hits.
 * @property rects highlight rectangles of the pages near the viewport only.
 */
data class ReaderSearchState(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    val current: SearchHit? = null,
    val scanning: Boolean = false,
    val skipped: Int = 0,
    val capped: Boolean = false,
    val rects: Map<Int, PageSearchRects> = emptyMap(),
) {
    /** Index of [current] in [hits], or -1. */
    val currentIndex: Int get() = current?.let { c -> hits.binarySearch(c).takeIf { it >= 0 } } ?: -1
}

/** Scroll page [page] into view so that [rect] (page points; null: the page top) is visible. */
data class SearchReveal(val page: Int, val rect: TextRect?)

/**
 * Pure search maths.
 *
 * Matching: literal (no patterns), case-insensitive with a locale-independent simple fold of each
 * code point ([fold]); whitespace in the query matches any run of whitespace in the page's text
 * and also a line break (text on two lines, with or without spaces between); a word itself never
 * spans a line break (hyphenation is not handled). Hits on one page are leftmost and
 * non-overlapping: after a hit, the search goes on at its end. Hits never cross pages.
 */
object SearchMath {
    /** Most hits kept for one query; the scan stops there and the count shows "10,000+". */
    const val MAX_HITS = 10_000

    /** Typing pause before a search starts (the IME search action starts it at once). */
    const val DEBOUNCE_MS = 300L

    /** Pages around the visible ones whose hits keep highlight rectangles. */
    const val RECT_PAGES = 2

    /** Whitespace for matching: Java whitespace plus Unicode space separators (no-break spaces). */
    fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    /**
     * [s] case-folded, one code point at a time and never changing the length (so indices still
     * map to the page's character boxes): `toLowerCase(toUpperCase(cp))`, which is
     * locale-independent and folds non-ASCII letters with a one-to-one case mapping (É/é,
     * Σ/σ/ς, Cyrillic, Greek, Deseret...). Mappings that change length are not applied: "ß" does
     * not match "ss", and there is no Unicode normalisation (a precomposed "é" does not match "e"
     * plus a combining accent). Unpaired surrogates are kept as they are.
     */
    fun fold(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val f = Character.toLowerCase(Character.toUpperCase(cp))
            if (Character.charCount(f) == n) out.appendCodePoint(f) else out.append(s, i, i + n)
            i += n
        }
        return out.toString()
    }

    /** The query of [raw], or null when it is empty after trimming. */
    fun parse(raw: String): SearchQuery? {
        val words = ArrayList<String>()
        val w = StringBuilder()
        for (c in raw) {
            if (isSpace(c)) {
                if (w.isNotEmpty()) { words += fold(w.toString()); w.setLength(0) }
            } else {
                w.append(c)
            }
        }
        if (w.isNotEmpty()) words += fold(w.toString())
        return if (words.isEmpty()) null else SearchQuery(raw.trim(), words)
    }

    /** First character index of each line of [t]. */
    fun lineStarts(t: PageText): IntArray = IntArray(t.lineCount) { t.lineRange(it).first }

    /**
     * Hits of [query] in [text] (a page's text, lines concatenated, line [i] starting at
     * [lineStarts]`[i]`), at most [limit].
     */
    fun find(text: String, lineStarts: IntArray, query: SearchQuery, page: Int, limit: Int = MAX_HITS): List<SearchHit> {
        if (limit <= 0) return emptyList()
        val f = fold(text)
        val n = f.length
        val breakBefore = BooleanArray(n + 1)
        for (s in lineStarts) if (s in 1 until n) breakBefore[s] = true
        val out = ArrayList<SearchHit>()
        val first = query.words[0][0]
        var i = 0
        while (i < n && out.size < limit) {
            val startsInsidePair = i > 0 && Character.isLowSurrogate(f[i]) && Character.isHighSurrogate(f[i - 1])
            val end = if (f[i] == first && !startsInsidePair) matchAt(f, breakBefore, query.words, i) else -1
            if (end > 0) {
                out += SearchHit(page, i, end)
                i = end
            } else {
                i++
            }
        }
        return out
    }

    /** End (exclusive) of a match of [words] starting at [start] of folded [f], or -1. */
    private fun matchAt(f: String, breakBefore: BooleanArray, words: List<String>, start: Int): Int {
        val n = f.length
        var p = start
        for ((k, word) in words.withIndex()) {
            if (k > 0) {
                val gap = p
                while (p < n && isSpace(f[p])) p++
                if (p == gap && !breakBefore[p]) return -1
            }
            if (p + word.length > n) return -1
            for (j in word.indices) {
                if (j > 0 && breakBefore[p + j]) return -1
                if (f[p + j] != word[j]) return -1
            }
            p += word.length
        }
        // A hit never ends between the halves of a surrogate pair.
        if (p < n && Character.isLowSurrogate(f[p]) && Character.isHighSurrogate(f[p - 1])) return -1
        return p
    }

    /** Pages in scan order: [start] to the last, then from the first to the one before [start]. */
    fun scanOrder(start: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val s = start.coerceIn(0, pageCount - 1)
        return (s until pageCount) + (0 until s)
    }

    /** Index after (or before, ![forward]) [current] among [size] hits, wrapping; -1 when there are none. */
    fun step(size: Int, current: Int, forward: Boolean): Int = when {
        size <= 0 -> -1
        current !in 0 until size -> if (forward) 0 else size - 1
        forward -> (current + 1) % size
        else -> (current - 1 + size) % size
    }

    /** Highlight rectangles of [hits] on [text]. */
    fun rectsOf(text: PageText, hits: List<SearchHit>): PageSearchRects =
        PageSearchRects(hits, hits.map { text.rects(it.start until it.endExclusive) })
}

/**
 * The mutable results of one query (main thread only). Hits are kept in document order by
 * inserting each page's hits at its place (the scan wraps, so earlier pages come last).
 */
internal class SearchResults(val query: SearchQuery) {
    val hits = ArrayList<SearchHit>()
    var current: SearchHit? = null
    var scanning = true
    var skipped = 0
    var capped = false
    val rects = HashMap<Int, PageSearchRects>()

    /** Adds [pageHits] (one page's, in order) at their place. */
    fun addPage(pageHits: List<SearchHit>) {
        if (pageHits.isEmpty()) return
        val at = hits.binarySearch(pageHits[0]).let { if (it < 0) -it - 1 else it }
        hits.addAll(at, pageHits)
    }

    fun hitsOn(page: Int): List<SearchHit> {
        val from = hits.binarySearch(SearchHit(page, -1, -1)).let { -it - 1 }
        var to = from
        while (to < hits.size && hits[to].page == page) to++
        return hits.subList(from, to).toList()
    }

    fun state(): ReaderSearchState =
        ReaderSearchState(query.text, hits.toList(), current, scanning, skipped, capped, rects.toMap())
}

/**
 * Scans [pages] in order for [results]' query: before each page it waits for [awaitTurn] (pending
 * renders go first), reads the page's text with [textOf], records its hits, keeps rectangles only
 * for pages [near] the viewport, and calls [publish] after each page. The first hit found becomes
 * current and is passed to [reveal]. Pages whose text cannot be read are counted as skipped.
 * Stops at [SearchMath.MAX_HITS] hits. Cancellable between and during pages.
 */
internal suspend fun scanPages(
    pages: List<Int>,
    results: SearchResults,
    textOf: suspend (Int) -> ReaderText,
    awaitTurn: suspend () -> Unit,
    near: (Int) -> Boolean,
    publish: () -> Unit,
    reveal: (SearchHit, List<TextRect>?) -> Unit,
) {
    for (page in pages) {
        awaitTurn()
        val r = textOf(page)
        currentCoroutineContext().ensureActive()
        when (r) {
            is ReaderText.Ok -> {
                val text = r.text
                val found = SearchMath.find(text.text, SearchMath.lineStarts(text), results.query, page, SearchMath.MAX_HITS - results.hits.size)
                results.addPage(found)
                if (found.isNotEmpty() && near(page)) results.rects[page] = SearchMath.rectsOf(text, found)
                if (results.current == null && found.isNotEmpty()) {
                    results.current = found[0]
                    reveal(found[0], text.rects(found[0].start until found[0].endExclusive))
                }
                if (results.hits.size >= SearchMath.MAX_HITS) {
                    results.capped = true
                    break
                }
            }
            is ReaderText.Err -> if (r.error != PdfError.Cancelled) results.skipped++
        }
        publish()
    }
    results.scanning = false
    publish()
}
