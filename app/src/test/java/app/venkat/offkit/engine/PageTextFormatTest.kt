package app.venkat.offkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

/** PageTextFormat parser (hostile files) and the PageText selection helpers. */
class PageTextFormatTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** "ab cd" / "ef" in block 0, "gh" in block 1; 10 pt wide boxes, lines 20 pt apart. */
    private fun sample(): PageText {
        val text = "ab cdefgh"
        val boxes = FloatArray(text.length * 4)
        val pos = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 0 to 1, 1 to 1, 0 to 2, 1 to 2)
        for ((i, p) in pos.withIndex()) {
            val (col, row) = p
            boxes[4 * i] = 10f + col * 10f; boxes[4 * i + 1] = 10f + row * 20f
            boxes[4 * i + 2] = 20f + col * 10f; boxes[4 * i + 3] = 22f + row * 20f
        }
        return PageTextFormat.build(text, boxes, intArrayOf(0, 5, 7), intArrayOf(0, 0, 1), false)
    }

    private fun file(bytes: (DataOutputStream) -> Unit) = tmp.newFile().apply { DataOutputStream(outputStream()).use(bytes) }

    private fun header(o: DataOutputStream, units: Int, lines: Int, blocks: Int, magic: Int = PageTextFormat.MAGIC, version: Int = 1, flags: Int = 0) {
        o.writeInt(magic); o.writeInt(version); o.writeInt(flags); o.writeInt(units); o.writeInt(lines); o.writeInt(blocks)
    }

    /** A valid one-unit file whose box is [box]. */
    private fun oneUnit(box: FloatArray, magic: Int = PageTextFormat.MAGIC, version: Int = 1, trailing: Boolean = false) = file { o ->
        header(o, 1, 1, 1, magic, version)
        o.writeChar('x'.code)
        box.forEach { o.writeFloat(it) }
        o.writeInt(0); o.writeInt(0)
        if (trailing) o.writeByte(0)
    }

    private fun assertErr(f: File) = assertTrue(PageTextFormat.read(f) is PageTextParse.Err)

    @Test
    fun roundTripAndHelpers() {
        val t = sample()
        val f = tmp.newFile().also { PageTextFormat.write(it, t) }
        val back = (PageTextFormat.read(f) as PageTextParse.Ok).text
        assertEquals(t.text, back.text)
        assertEquals(3, back.lineCount)
        assertEquals("ab cd\nef\n\ngh", back.string(0 until back.length))
        assertEquals("cd\ne", back.string(3..5))
        assertEquals(3..4, back.wordRange(4))
        assertEquals(0..1, back.wordRange(0))
        assertEquals(2..2, back.wordRange(2))
        assertEquals(5..6, back.wordRange(6))
        assertEquals(0, back.charAt(12f, 15f))
        assertEquals(4, back.charAt(100f, 15f)) // right of line 0: its last char
        assertEquals(8, back.charAt(29f, 60f)) // below the last line
        assertEquals(null, back.charAt(100f, 15f, maxDistancePt = 5f))
        assertEquals(listOf(TextRect(40f, 10f, 60f, 22f), TextRect(10f, 30f, 30f, 42f)), back.rects(3..6))
        assertEquals(emptyList<TextRect>(), back.rects(IntRange.EMPTY))
    }

    @Test
    fun malformedFilesAreErrorsNotExceptions() {
        val ok = floatArrayOf(1f, 2f, 3f, 4f)
        assertTrue(PageTextFormat.read(oneUnit(ok)) is PageTextParse.Ok)
        assertErr(oneUnit(ok, magic = 0x12345678))
        assertErr(oneUnit(ok, version = 2))
        assertErr(oneUnit(ok, trailing = true))
        assertErr(oneUnit(floatArrayOf(Float.NaN, 2f, 3f, 4f)))
        assertErr(oneUnit(floatArrayOf(1f, 2f, Float.POSITIVE_INFINITY, 4f)))
        assertErr(oneUnit(floatArrayOf(1f, 2f, 1e30f, 4f)))
        assertErr(oneUnit(floatArrayOf(3f, 2f, 1f, 4f))) // x0 > x1
        // Truncated: valid file cut short.
        val full = oneUnit(ok)
        val cut = tmp.newFile().apply { writeBytes(full.readBytes().copyOf(full.length().toInt() - 3)) }
        assertErr(cut)
        assertErr(tmp.newFile()) // empty
        // Counts larger than the file, huge declared lengths, negative counts.
        assertErr(file { header(it, 1000, 1, 1) })
        assertErr(file { header(it, Int.MAX_VALUE, Int.MAX_VALUE, 1) })
        assertErr(file { header(it, -1, 0, 0) })
        assertErr(file { header(it, PageTextFormat.MAX_UNITS + 1, 1, 1) })
        // Line structure: first line not at 0, line starting past the text, block jump.
        assertErr(file { o -> header(o, 1, 1, 1); o.writeChar(120); ok.forEach { o.writeFloat(it) }; o.writeInt(1); o.writeInt(0) })
        assertErr(file { o -> header(o, 1, 1, 1); o.writeChar(120); ok.forEach { o.writeFloat(it) }; o.writeInt(0); o.writeInt(1) })
        assertErr(file { o -> header(o, 1, 1, 2); o.writeChar(120); ok.forEach { o.writeFloat(it) }; o.writeInt(0); o.writeInt(0) })
        assertErr(File(tmp.root, "missing.bin"))
        // An empty page is valid.
        assertEquals(0, (PageTextFormat.read(file { header(it, 0, 0, 0) }) as PageTextParse.Ok).text.length)
    }

    @Test
    fun h5_6_selectionNeverSplitsASupplementaryCharacter() {
        // "a😀b😀": the emoji is two UTF-16 units (high, low surrogate); each unit has its own box.
        val text = "a😀b😀"
        val boxes = FloatArray(text.length * 4)
        for (i in text.indices) { boxes[4 * i] = 10f * i; boxes[4 * i + 1] = 0f; boxes[4 * i + 2] = 10f * i + 10f; boxes[4 * i + 3] = 12f }
        val t = PageTextFormat.build(text, boxes, intArrayOf(0), intArrayOf(0), false)
        assertEquals("a😀", t.string(0..1)) // ends on the high surrogate
        assertEquals("😀b", t.string(2..3)) // starts on the low surrogate
        assertEquals("😀", t.string(4..4)) // last character of the text
        assertEquals("😀", t.string(5..5))
        assertEquals("b", t.string(3..3))
        assertEquals(listOf(TextRect(0f, 0f, 30f, 12f)), t.rects(0..1))
        assertEquals(listOf(TextRect(10f, 0f, 40f, 12f)), t.rects(2..3))
        assertEquals(listOf(TextRect(40f, 0f, 60f, 12f)), t.rects(4..4))
    }

    @Test
    fun h5_7_firstLineMustBeInBlockZero() {
        val ok = floatArrayOf(1f, 2f, 3f, 4f)
        // One unit, one line in block -1, zero blocks: consistent except that block 0 is required.
        assertErr(file { o -> header(o, 1, 1, 0); o.writeChar(120); ok.forEach { o.writeFloat(it) }; o.writeInt(0); o.writeInt(-1) })
        // Two lines in blocks -1, 0 and one block.
        assertErr(file { o ->
            header(o, 2, 2, 1); o.writeChar(120); o.writeChar(121); repeat(2) { ok.forEach { o.writeFloat(it) } }
            o.writeInt(0); o.writeInt(-1); o.writeInt(1); o.writeInt(0)
        })
        assertTrue(PageTextFormat.read(file { o -> header(o, 1, 1, 1); o.writeChar(120); ok.forEach { o.writeFloat(it) }; o.writeInt(0); o.writeInt(0) }) is PageTextParse.Ok)
    }

    @Test
    fun linesOnTheSameRowAreJoinedWithASpace() {
        // "ab" at x 10..30 and "cd" at x 40..60 on the same row, as two lines; then "ef" below.
        val text = "abcdef"
        val xs = listOf(10f, 20f, 40f, 50f, 10f, 20f)
        val ys = listOf(10f, 10f, 11f, 11f, 40f, 40f)
        val boxes = FloatArray(24)
        for (i in 0 until 6) { boxes[4 * i] = xs[i]; boxes[4 * i + 1] = ys[i]; boxes[4 * i + 2] = xs[i] + 10f; boxes[4 * i + 3] = ys[i] + 12f }
        val t = PageTextFormat.build(text, boxes, intArrayOf(0, 2, 4), intArrayOf(0, 0, 0), false)
        assertEquals("ab cd\nef", t.string(0..5))
        // A next line that starts left of the previous end is a new line even at the same height.
        val back = boxes.copyOf().also { it[8] = 15f; it[10] = 25f }
        assertEquals("ab\ncd\nef", PageTextFormat.build(text, back, intArrayOf(0, 2, 4), intArrayOf(0, 0, 0), false).string(0..5))
    }
}
