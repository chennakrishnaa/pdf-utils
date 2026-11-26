package app.venkat.offkit.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Outcome of [PdfPageRenderer.pageText]. */
sealed interface PdfPageText {
    /**
     * `out` holds a [PageTextFormat] file with exactly [units] UTF-16 units of text. [truncated]:
     * the page had more text than [PageTextFormat.MAX_UNITS] and only the first units were kept.
     */
    data class Ok(val units: Int, val truncated: Boolean) : PdfPageText

    /** `out` does not exist. */
    data class Err(val error: PdfError) : PdfPageText
}

/** An axis-aligned rectangle in page points, top-left origin (the space of [PageDims]). */
data class TextRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * The text of one page with its geometry, as parsed by [PageTextFormat.read]. Immutable; no Android
 * imports, so the selection helpers are plain functions.
 *
 * Indices are UTF-16 indices into [text]: a supplementary character is two units that share one
 * box. [text] holds no synthetic separators; [string] inserts them. Coordinates are page points of
 * the displayed page (rotation applied, top-left origin), the same space as [PageDims]; boxes may lie
 * partly or wholly off the page (that is legal PDF), but are finite and within
 * ±[PageTextFormat.MAX_COORD].
 *
 * @property lineStarts index of the first unit of each line; lines are contiguous, non-empty, in
 *   reading order, and cover the whole text (`lineStarts[0] == 0` when there is text).
 * @property lineBlocks block number of each line, non-decreasing from 0 in steps of 0 or 1.
 */
class PageText internal constructor(
    val text: String,
    private val boxes: FloatArray,
    private val lineStarts: IntArray,
    private val lineBlocks: IntArray,
    val truncated: Boolean,
) {
    val length: Int get() = text.length
    val lineCount: Int get() = lineStarts.size

    /** Box of unit [index] (0 ≤ index < [length]). */
    fun box(index: Int): TextRect {
        val i = index * 4
        return TextRect(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3])
    }

    /** Line number holding unit [index]. */
    fun lineOf(index: Int): Int {
        val k = lineStarts.binarySearch(index)
        return if (k >= 0) k else -k - 2
    }

    /** Units of line [line], as a half-open range start until end. */
    fun lineRange(line: Int): IntRange =
        lineStarts[line] until (if (line + 1 < lineStarts.size) lineStarts[line + 1] else text.length)

    /** Block number of line [line]. */
    fun blockOf(line: Int): Int = lineBlocks[line]

    /**
     * Index of the character at or nearest to ([xPt], [yPt]), or null if the page has no text or the
     * nearest box is farther than [maxDistancePt]. A point inside a box returns that box. Otherwise
     * the nearest line (distance to its bounding box) is taken, then the nearest unit in it.
     */
    fun charAt(xPt: Float, yPt: Float, maxDistancePt: Float = Float.POSITIVE_INFINITY): Int? {
        if (text.isEmpty() || !xPt.isFinite() || !yPt.isFinite()) return null
        var bestLine = -1
        var bestD = Float.POSITIVE_INFINITY
        for (l in 0 until lineCount) {
            val r = lineRange(l)
            var x0 = Float.POSITIVE_INFINITY; var y0 = Float.POSITIVE_INFINITY
            var x1 = Float.NEGATIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
            for (i in r) {
                val b = i * 4
                x0 = min(x0, boxes[b]); y0 = min(y0, boxes[b + 1]); x1 = max(x1, boxes[b + 2]); y1 = max(y1, boxes[b + 3])
            }
            val d = dist(xPt, yPt, x0, y0, x1, y1)
            if (d < bestD) { bestD = d; bestLine = l }
            if (d == 0f) break
        }
        if (bestLine < 0 || bestD > maxDistancePt) return null
        var best = -1
        var bd = Float.POSITIVE_INFINITY
        for (i in lineRange(bestLine)) {
            val b = i * 4
            val d = dist(xPt, yPt, boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3])
            if (d < bd) { bd = d; best = i }
        }
        if (best >= 0 && Character.isLowSurrogate(text[best]) && best > 0) best -= 1
        return best.takeIf { it >= 0 }
    }

    /**
     * The word around unit [index] within its line: the maximal run of non-whitespace units. A
     * whitespace unit gives just itself. [index] is clamped to the text.
     */
    fun wordRange(index: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val i = index.coerceIn(0, text.length - 1)
        if (text[i].isWhitespace()) return i..i
        val line = lineRange(lineOf(i))
        var s = i
        while (s > line.first && !text[s - 1].isWhitespace()) s--
        var e = i
        while (e < line.last && !text[e + 1].isWhitespace()) e++
        return s..e
    }

    /** Highlight rectangles of [range] (clamped to the text): one per line it touches, the union of its boxes there. */
    fun rects(range: IntRange): List<TextRect> {
        val r = clamp(range) ?: return emptyList()
        val out = ArrayList<TextRect>()
        var l = lineOf(r.first)
        while (l < lineCount) {
            val lr = lineRange(l)
            if (lr.first > r.last) break
            val s = max(lr.first, r.first)
            val e = min(lr.last, r.last)
            var x0 = Float.POSITIVE_INFINITY; var y0 = Float.POSITIVE_INFINITY
            var x1 = Float.NEGATIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
            for (i in s..e) {
                val b = i * 4
                x0 = min(x0, boxes[b]); y0 = min(y0, boxes[b + 1]); x1 = max(x1, boxes[b + 2]); y1 = max(y1, boxes[b + 3])
            }
            if (s <= e) out += TextRect(x0, y0, x1, y1)
            l++
        }
        return out
    }

    /**
     * The text of [range] (clamped) for copying: "\n" between lines of one block, "\n\n" between
     * blocks, but a single space when the next line continues the same row (see [sameRow]);
     * trailing whitespace of each line is dropped.
     */
    fun string(range: IntRange): String {
        val r = clamp(range) ?: return ""
        val sb = StringBuilder()
        var l = lineOf(r.first)
        var first = true
        while (l < lineCount) {
            val lr = lineRange(l)
            if (lr.first > r.last) break
            if (!first) {
                sb.append(
                    when {
                        sameRow(l - 1, l) -> " "
                        lineBlocks[l] != lineBlocks[l - 1] -> "\n\n"
                        else -> "\n"
                    },
                )
            }
            first = false
            sb.append(text, max(lr.first, r.first), min(lr.last, r.last) + 1)
            while (sb.isNotEmpty() && sb.last().let { it == ' ' || it == '\t' }) sb.setLength(sb.length - 1)
            l++
        }
        return sb.toString()
    }

    /**
     * True when line [b] continues line [a] on the same row: the last box of [a] and the first box
     * of [b] overlap vertically by at least half the smaller height, and [b] starts at or right of
     * the end of [a] (less 1 pt of slack).
     */
    internal fun sameRow(a: Int, b: Int): Boolean {
        val p = lineRange(a).last * 4
        val q = lineRange(b).first * 4
        val overlap = min(boxes[p + 3], boxes[q + 3]) - max(boxes[p + 1], boxes[q + 1])
        val minH = min(boxes[p + 3] - boxes[p + 1], boxes[q + 3] - boxes[q + 1])
        return minH > 0f && overlap >= minH / 2 && boxes[q] >= boxes[p + 2] - 1f
    }

    private fun clamp(range: IntRange): IntRange? {
        if (text.isEmpty() || range.isEmpty()) return null
        val s = range.first.coerceIn(0, text.length - 1)
        val e = range.last.coerceIn(0, text.length - 1)
        return if (s <= e) s..e else null
    }

    private fun dist(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = if (x < x0) x0 - x else if (x > x1) x - x1 else 0f
        val dy = if (y < y0) y0 - y else if (y > y1) y - y1 else 0f
        return abs(dx) + abs(dy)
    }

    companion object {
        val EMPTY = PageText("", FloatArray(0), IntArray(0), IntArray(0), false)
    }
}

/** Result of [PageTextFormat.read]. */
sealed interface PageTextParse {
    data class Ok(val text: PageText) : PageTextParse

    /** The file is not a valid [PageTextFormat] file; [reason] says why. */
    data class Err(val reason: String) : PageTextParse
}

/**
 * The page-text file written by the `:engine` process and read by the UI process. Big-endian
 * (`DataOutputStream`), fixed-size records, so its exact length follows from the header:
 *
 * ```
 * header  : magic "OKTX" (4 bytes), version u32 (=1), flags u32 (bit 0: truncated),
 *           units u32, lines u32, blocks u32                                    (24 bytes)
 * text    : units x u16 UTF-16 code unit
 * boxes   : units x 4 x f32 (x0, y0, x1, y1), page points, top-left origin, x0<=x1, y0<=y1
 * lines   : lines x (u32 first unit, u32 block)
 * ```
 * Lines are non-empty, contiguous and cover the text; blocks are numbered 0 until `blocks`,
 * non-decreasing by at most 1 per line. Zero units means zero lines and zero blocks.
 */
object PageTextFormat {
    const val MAGIC = 0x4F4B5458 // "OKTX"
    const val VERSION = 1
    const val HEADER_BYTES = 24
    const val FLAG_TRUNCATED = 1

    /** Most UTF-16 units of text per page. */
    const val MAX_UNITS = 200_000

    /** Largest |coordinate| accepted, in points: as [app.venkat.offkit.core.PdfReader.MAX_PAGE_PT] plus margin. */
    const val MAX_COORD = 4_000_000f

    /** Exact byte length of a file with these counts (Long, never overflows for Int inputs). */
    fun fileBytes(units: Int, lines: Int): Long = HEADER_BYTES + units.toLong() * (2 + 16) + lines.toLong() * 8

    /** Largest valid file: [MAX_UNITS] units, as many lines. */
    val MAX_FILE_BYTES: Long = fileBytes(MAX_UNITS, MAX_UNITS)

    /** Writes [t] to [out] (engine side; [t] must satisfy the invariants [read] checks). */
    fun write(out: File, t: PageText) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024)).use { o ->
            val n = t.length
            val blocks = if (t.lineCount == 0) 0 else t.blockOf(t.lineCount - 1) + 1
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeInt(if (t.truncated) FLAG_TRUNCATED else 0)
            o.writeInt(n); o.writeInt(t.lineCount); o.writeInt(blocks)
            for (i in 0 until n) o.writeChar(t.text[i].code)
            for (i in 0 until n) {
                val b = t.box(i)
                o.writeFloat(b.x0); o.writeFloat(b.y0); o.writeFloat(b.x1); o.writeFloat(b.y1)
            }
            for (l in 0 until t.lineCount) { o.writeInt(t.lineRange(l).first); o.writeInt(t.blockOf(l)) }
        }
    }

    /**
     * Reads and validates [file] (untrusted). Every count is checked against the caps and the exact
     * file length before anything is allocated; any violation, or an I/O failure, is an [PageTextParse.Err].
     * Never throws (except [OutOfMemoryError] in a JVM that cannot hold a capped file).
     */
    fun read(file: File): PageTextParse = try {
        val length = file.length()
        if (length < HEADER_BYTES) {
            PageTextParse.Err("file too short ($length bytes)")
        } else if (length > MAX_FILE_BYTES) {
            PageTextParse.Err("file too long ($length bytes)")
        } else {
            DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { parse(it, length) }
        }
    } catch (e: EOFException) {
        PageTextParse.Err("file ends early")
    } catch (e: IOException) {
        PageTextParse.Err("cannot read: ${e.message}")
    }

    private fun parse(i: DataInputStream, length: Long): PageTextParse {
        fun err(s: String) = PageTextParse.Err(s)
        if (i.readInt() != MAGIC) return err("bad magic")
        val version = i.readInt()
        if (version != VERSION) return err("unsupported version $version")
        val flags = i.readInt()
        if (flags and FLAG_TRUNCATED.inv() != 0) return err("unknown flags $flags")
        val n = i.readInt()
        val lines = i.readInt()
        val blocks = i.readInt()
        if (n < 0 || n > MAX_UNITS) return err("unit count $n")
        if (lines < 0 || lines > n || blocks < 0 || blocks > lines) return err("counts $n/$lines/$blocks")
        if ((n == 0) != (lines == 0)) return err("lines without text")
        if (fileBytes(n, lines) != length) return err("length $length does not match counts $n/$lines")
        val chars = CharArray(n) { i.readChar() }
        val boxes = FloatArray(n * 4)
        for (k in 0 until n) {
            val x0 = i.readFloat(); val y0 = i.readFloat(); val x1 = i.readFloat(); val y1 = i.readFloat()
            if (!sane(x0) || !sane(y0) || !sane(x1) || !sane(y1) || x0 > x1 || y0 > y1) return err("bad box at $k")
            boxes[4 * k] = x0; boxes[4 * k + 1] = y0; boxes[4 * k + 2] = x1; boxes[4 * k + 3] = y1
        }
        val starts = IntArray(lines)
        val lineBlocks = IntArray(lines)
        for (l in 0 until lines) {
            val s = i.readInt()
            val b = i.readInt()
            val prevStart = if (l == 0) -1 else starts[l - 1]
            val prevBlock = if (l == 0) -1 else lineBlocks[l - 1]
            if (l == 0 && s != 0) return err("first line does not start at 0")
            if (l == 0 && b != 0) return err("first line is not in block 0")
            if (l > 0 && s <= prevStart) return err("empty or unordered line $l")
            if (s >= n) return err("line $l starts past the text")
            if (b != prevBlock && b != prevBlock + 1) return err("bad block $b at line $l")
            starts[l] = s; lineBlocks[l] = b
        }
        if (lines > 0 && lineBlocks[lines - 1] != blocks - 1) return err("block count mismatch")
        if (i.read() != -1) return err("trailing bytes")
        return PageTextParse.Ok(PageText(String(chars), boxes, starts, lineBlocks, flags and FLAG_TRUNCATED != 0))
    }

    private fun sane(f: Float) = f.isFinite() && abs(f) <= MAX_COORD

    /**
     * Builds a [PageText] from parts (engine side and tests). Lines are given as their first unit
     * indices and block numbers; validated like [read], throwing [IllegalArgumentException].
     */
    fun build(text: String, boxes: FloatArray, lineStarts: IntArray, lineBlocks: IntArray, truncated: Boolean): PageText {
        require(boxes.size == text.length * 4 && lineStarts.size == lineBlocks.size) { "sizes" }
        val tmp = File.createTempFile("pagetext", ".bin")
        try {
            write(tmp, PageText(text, boxes, lineStarts, lineBlocks, truncated))
            return when (val p = read(tmp)) {
                is PageTextParse.Ok -> p.text
                is PageTextParse.Err -> throw IllegalArgumentException(p.reason)
            }
        } finally {
            tmp.delete()
        }
    }
}
