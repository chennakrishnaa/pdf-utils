package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.ops.PageTextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** MuPdfEngine.pageText (PageTextOp) on the host MuPDF build, parsed back with PageTextFormat. */
class PageTextOpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()

    /** One page [w]x[h] with /Rotate [rotate], Helvetica as /F1, content [content], extra objects from 10. */
    private fun pdf(name: String, content: String, w: Int = 200, h: Int = 200, rotate: Int = 0, xobj: String = "", more: HostilePdf.() -> Unit = {}): File {
        val p = HostilePdf()
        p.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        p.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
        p.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 $w $h]/Rotate $rotate/Resources<</Font<</F1 5 0 R>>$xobj>>/Contents 4 0 R>>")
        p.stream(4, "", content.latin1())
        p.add(5, "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>")
        p.more()
        return File(tmp.root, name).apply { writeBytes(p.bytes()) }
    }

    private fun text(pdf: File, page: Int = 0, op: PageTextOp? = null, job: JobControl = JobControl.None): PageText {
        val out = File(tmp.root, "t-${System.nanoTime()}.bin")
        val r = op?.run(pdf, page, out, job) ?: engine.pageText(pdf, page, out, job)
        assertTrue("got $r", r is PdfPageText.Ok)
        val parsed = PageTextFormat.read(out)
        assertTrue("parse: $parsed", parsed is PageTextParse.Ok)
        val t = (parsed as PageTextParse.Ok).text
        assertEquals((r as PdfPageText.Ok).units, t.length)
        assertEquals(r.truncated, t.truncated)
        return t
    }

    private fun assertErr(pdf: File, page: Int, check: (PdfError) -> Boolean, job: JobControl = JobControl.None) {
        val out = File(tmp.root, "stale.bin").apply { writeText("stale") }
        val r = engine.pageText(pdf, page, out, job)
        assertTrue("got $r", r is PdfPageText.Err && check(r.error))
        assertFalse(out.exists())
    }

    private val twoLines = "BT /F1 20 Tf 10 150 Td (Hello world) Tj 0 -40 Td (Second line) Tj ET"

    @Test
    fun knownWordsAtKnownPositionsInReadingOrder() {
        val t = text(pdf("two.pdf", twoLines))
        assertTrue(t.text, t.text.startsWith("Hello world"))
        val s = t.text.indexOf("Second")
        assertTrue(s > 0)
        assertEquals(2, t.lineCount)
        // Baseline y = 150 in PDF space is y = 50 from the top; 20 pt Helvetica.
        val h = t.box(0)
        assertTrue("$h", h.x0 in 9f..11f && h.y1 in 45f..56f && h.y0 in 28f..45f && h.x1 > h.x0)
        for (i in 0 until t.length) {
            val b = t.box(i)
            assertTrue("$i $b", b.x0 >= 0f && b.x1 <= 200f && b.y0 >= 0f && b.y1 <= 200f)
        }
        // Reading order: x grows along a line, second line lower.
        assertTrue(t.box(1).x0 > t.box(0).x0)
        assertTrue(t.box(s).y0 > t.box(0).y1)
        // Copy across the two lines gives a line break; helpers find words.
        assertTrue(Regex("Hello world\\n+Second line").matches(t.string(0 until t.length)))
        assertEquals("world", t.string(t.wordRange(8)))
        val hit = t.charAt(h.x0 + 1f, (h.y0 + h.y1) / 2)
        assertEquals(0, hit)
        assertEquals(s, t.charAt(t.box(s).x0 + 0.5f, t.box(s).y1 + 3f))
        val rects = t.rects(0..s + 2)
        assertEquals(2, rects.size)
    }

    @Test
    fun imageOnlyPageHasNoText() {
        val pdf = pdf("img.pdf", "q 200 0 0 200 0 0 cm /Im0 Do Q", xobj = "/XObject<</Im0 10 0 R>>") {
            stream(10, "/Subtype/Image/Width 2/Height 2/ColorSpace/DeviceGray/BitsPerComponent 8", ByteArray(4) { 0x40 })
        }
        val t = text(pdf)
        assertEquals(0, t.length)
        assertEquals(0, t.lineCount)
        assertEquals(null, t.charAt(10f, 10f))
        assertEquals("", t.string(0..10))
    }

    @Test
    fun rotatedPageBoxesAreInTheDisplayedSpace() {
        // 200x100 unrotated; /Rotate 90 displays it 100x200: unrotated top-left (x, y) is at (100 - y, x).
        val t = text(pdf("rot.pdf", "BT /F1 20 Tf 10 50 Td (Abc) Tj ET", w = 200, h = 100, rotate = 90))
        assertEquals("Abc", t.text)
        val a = t.box(0)
        // Unrotated: x 10..~23, y (top-left) ~32..~54 -> displayed x ~46..~68, y 10..~23.
        assertTrue("$a", a.x0 in 40f..70f && a.x1 in 40f..72f && a.y0 in 9f..11f && a.y1 < 30f)
        assertTrue(t.box(1).y0 > a.y0) // the line runs downwards
        for (i in 0 until t.length) assertTrue(t.box(i).x1 <= 100f && t.box(i).y1 <= 200f)
    }

    @Test
    fun capTruncatesAndFlags() {
        val t = text(pdf("two.pdf", twoLines), op = PageTextOp(maxUnits = 5))
        assertTrue(t.truncated)
        assertEquals("Hello", t.text)
        assertEquals(1, t.lineCount)
        val full = text(pdf("two2.pdf", twoLines))
        assertFalse(full.truncated)
    }

    @Test
    fun cancelAndBadRequests() {
        val pdf = pdf("two.pdf", twoLines)
        val cancelled = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            override fun isCancelled() = true
        }
        assertErr(pdf, 0, { it == PdfError.Cancelled }, cancelled)
        var polls = 0
        val later = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            override fun isCancelled() = ++polls > 1
        }
        assertErr(pdf, 0, { it == PdfError.Cancelled }, later)
        val bad: (PdfError) -> Boolean = { it is PdfError.Io && it.cause is IllegalArgumentException }
        assertErr(pdf, 1, bad)
        assertErr(pdf, -1, bad)
    }

    @Test
    fun filesTheRenderPathRefusesAreRefusedHereToo() {
        // A 60000x60000 image is refused by RenderImageGate before the page is run.
        val bomb = pdf("bomb.pdf", "q 200 0 0 200 0 0 cm /Im0 Do Q $twoLines", xobj = "/XObject<</Im0 10 0 R>>") {
            stream(10, "/Subtype/Image/Width 60000/Height 60000/ColorSpace/DeviceRGB/BitsPerComponent 8", ByteArray(64))
        }
        assertErr(bomb, 0, { it == PdfError.OutOfMemory })
        val annots = File(tmp.root, "annots.pdf").apply {
            writeBytes(HostilePdf.sharedAnnots(ObjectGraph.MAX_ANNOTS_PER_PAGE + 500, 1))
        }
        assertErr(annots, 0, { it == PdfError.OutOfMemory })
        assertErr(Fixtures.encryptedPdf(tmp.root), 0, { it == PdfError.Encrypted })
        assertErr(Fixtures.corruptPdf(tmp.root), 0, { it == PdfError.Corrupt })
        assertErr(File(tmp.root, "missing.pdf"), 0, { it is PdfError.Io })
    }
}
