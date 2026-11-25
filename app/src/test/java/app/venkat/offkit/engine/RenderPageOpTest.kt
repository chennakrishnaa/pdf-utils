package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.Fixtures.QuadrantPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/** MuPdfEngine.pageInfo / renderPage (PageInfoOp, RenderPageOp) on the host MuPDF build. */
class RenderPageOpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()
    private val dir: File get() = tmp.root

    /** Raw RGBA output of one render. */
    private class Rgba(val bytes: ByteArray, val width: Int, val height: Int) {
        fun px(x: Int, y: Int): IntArray {
            val i = (y * width + x) * 4
            return IntArray(4) { bytes[i + it].toInt() and 0xFF }
        }
    }

    private fun staleOut(name: String = "out.rgba") = File(dir, name).apply { writeText("stale") }

    private fun render(pdf: File, req: RenderRequest, job: JobControl = JobControl.None): Rgba {
        val out = staleOut("render-${System.nanoTime()}.rgba")
        val r = engine.renderPage(pdf, req, out, job)
        assertEquals(PdfRender.Ok(req.width, req.height), r)
        assertEquals("file length", req.width.toLong() * req.height * 4, out.length())
        return Rgba(out.readBytes(), req.width, req.height)
    }

    private fun full(pdf: File, page: Int, scale: Float): Rgba {
        val dims = (engine.pageInfo(pdf) as PdfPageInfo.Ok).pages[page]
        val w = RenderLimits.scaledWidth(dims, scale).toInt()
        val h = RenderLimits.scaledHeight(dims, scale).toInt()
        return render(pdf, RenderRequest(page, scale, 0, 0, w, h))
    }

    private fun assertColor(img: Rgba, x: Int, y: Int, rgb: Int, label: String) {
        val p = img.px(x, y)
        val want = intArrayOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, 255)
        for (c in 0 until 4) {
            if (abs(p[c] - want[c]) > 2) {
                fail("$label at ($x,$y): got ${p.toList()}, want ${want.toList()}")
            }
        }
    }

    private fun assertAllOpaque(img: Rgba) {
        for (i in 3 until img.bytes.size step 4) {
            if (img.bytes[i] != (-1).toByte()) fail("pixel ${i / 4} alpha ${img.bytes[i].toInt() and 0xFF}")
        }
    }

    private fun assertRenderErr(pdf: File, req: RenderRequest, check: (PdfError) -> Boolean, job: JobControl = JobControl.None) {
        val out = staleOut()
        val r = engine.renderPage(pdf, req, out, job)
        assertTrue("got $r", r is PdfRender.Err && check(r.error))
        assertFalse("out must not exist after $r", out.exists())
    }

    private val isBadRequest: (PdfError) -> Boolean = { it is PdfError.Io && it.cause is IllegalArgumentException }

    // 200x100 pt, unrotated: quadrant centres in top-left page coordinates (m = 10 pt).
    private val upright = listOf(
        Triple(50, 25, Fixtures.RED), Triple(150, 25, Fixtures.GREEN),
        Triple(50, 75, Fixtures.BLUE), Triple(150, 75, Fixtures.YELLOW),
    )
    private val whiteSpots = listOf(5 to 5, 100 to 50, 195 to 95, 100 to 5, 5 to 50)

    private fun assertUpright(img: Rgba, scale: Int, label: String) {
        for ((x, y, c) in upright) assertColor(img, x * scale, y * scale, c, "$label quadrant")
        for ((x, y) in whiteSpots) assertColor(img, x * scale, y * scale, 0xFFFFFF, "$label white")
    }

    // ------------------------------------------------------------------ pixels and geometry

    @Test
    fun quadrantColoursAtScale1And2() {
        val pdf = Fixtures.quadrantPdf(dir, "q.pdf", listOf(QuadrantPage(200f, 100f)))
        assertEquals(PdfPageInfo.Ok(listOf(PageDims(200f, 100f))), engine.pageInfo(pdf))
        for (s in 1..2) {
            val img = full(pdf, 0, s.toFloat())
            assertEquals(200 * s, img.width)
            assertEquals(100 * s, img.height)
            assertUpright(img, s, "scale $s")
            assertAllOpaque(img)
        }
    }

    @Test
    fun clipRectangleEqualsCropOfFullRender() {
        val pdf = Fixtures.quadrantPdf(dir, "q.pdf", listOf(QuadrantPage(200f, 100f)))
        val whole = full(pdf, 0, 2f)
        val (l, t, w, h) = listOf(37, 23, 251, 141)
        val tile = render(pdf, RenderRequest(0, 2f, l, t, w, h))
        for (y in 0 until h) for (x in 0 until w) {
            val a = tile.px(x, y)
            val b = whole.px(l + x, t + y)
            for (c in 0 until 4) {
                if (abs(a[c] - b[c]) > 2) fail("tile ($x,$y) ${a.toList()} vs full ${b.toList()}")
            }
        }
        // A tile at the bottom-right corner, up to the ceil'd page edge.
        render(pdf, RenderRequest(0, 1.5f, 290, 140, 10, 10))
    }

    @Test
    fun rotatedPageShowsQuadrantsWhereAnUprightViewerWould() {
        val pdf = Fixtures.quadrantPdf(dir, "r.pdf", listOf(QuadrantPage(200f, 100f, rotate = 90)))
        assertEquals(PdfPageInfo.Ok(listOf(PageDims(100f, 200f))), engine.pageInfo(pdf))
        val img = full(pdf, 0, 2f)
        assertEquals(200, img.width)
        assertEquals(400, img.height)
        // /Rotate 90 turns the page clockwise: unrotated top-left (x, y) is displayed at (H - y, x), H = 100.
        for ((x, y, c) in upright) assertColor(img, (100 - y) * 2, x * 2, c, "rotated quadrant")
        assertColor(img, 75 * 2, 50 * 2, Fixtures.RED, "RED top-right")
        assertColor(img, 25 * 2, 150 * 2, Fixtures.YELLOW, "YELLOW bottom-left")
        assertColor(img, 2, 2, 0xFFFFFF, "white corner")
    }

    @Test
    fun nonZeroMediaBoxOriginStartsAtTopLeft() {
        val pdf = Fixtures.quadrantPdf(
            dir, "o.pdf", listOf(QuadrantPage(200f, 100f, originX = 300f, originY = -500f)),
        )
        assertEquals(PdfPageInfo.Ok(listOf(PageDims(200f, 100f))), engine.pageInfo(pdf))
        val img = full(pdf, 0, 2f)
        assertUpright(img, 2, "offset origin")
        // Also with rotation on top of the offset origin.
        val rot = Fixtures.quadrantPdf(
            dir, "or.pdf", listOf(QuadrantPage(200f, 100f, rotate = 270, originX = 50f, originY = 70f)),
        )
        val r = full(rot, 0, 1f)
        // /Rotate 270 (= 90 counter-clockwise): unrotated (x, y) is displayed at (y, W - x), W = 200.
        for ((x, y, c) in upright) assertColor(r, y, 200 - x, c, "rot270 quadrant")
    }

    @Test
    fun annotationsAreDrawn() {
        val pdf = Fixtures.squareAnnotationPdf(dir, "a.pdf")
        val img = full(pdf, 0, 1f)
        assertColor(img, 100, 100, Fixtures.BLUE, "annotation interior")
        assertColor(img, 20, 20, 0xFFFFFF, "outside annotation")
    }

    // ------------------------------------------------------------------ page info

    @Test
    fun pageInfoMixedSizesInOrder() {
        val pdf = Fixtures.quadrantPdf(
            dir, "m.pdf",
            listOf(
                QuadrantPage(200f, 100f), QuadrantPage(612f, 792f),
                QuadrantPage(300f, 400f, rotate = 270), QuadrantPage(72.5f, 1000f, originX = 10f, originY = 10f),
            ),
        )
        var progress = 0 to 0
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) { progress = done to total }
            override fun isCancelled() = false
        }
        assertEquals(
            PdfPageInfo.Ok(listOf(PageDims(200f, 100f), PageDims(612f, 792f), PageDims(400f, 300f), PageDims(72.5f, 1000f))),
            engine.pageInfo(pdf, job),
        )
        assertEquals(4 to 4, progress)
    }

    @Test
    fun pageInfoZeroPagesIsCorrupt() {
        val pdf = rawPdf("zero.pdf", 0)
        assertEquals(PdfPageInfo.Err(PdfError.Corrupt), engine.pageInfo(pdf))
        assertRenderErr(pdf, RenderRequest(0, 1f, 0, 0, 1, 1), { it == PdfError.Corrupt })
    }

    @Test
    fun pageInfoOverPageLimitIsOutOfMemory() {
        val pdf = rawPdf("many.pdf", RenderLimits.MAX_PAGES + 1)
        val t0 = System.nanoTime()
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(pdf))
        println("TIMING over-limit (${RenderLimits.MAX_PAGES + 1} pages) pageInfo: ${(System.nanoTime() - t0) / 1_000_000} ms")
        // Exactly at the limit is fine.
        val atLimit = rawPdf("limit.pdf", RenderLimits.MAX_PAGES)
        val t1 = System.nanoTime()
        val info = engine.pageInfo(atLimit)
        println("TIMING ${RenderLimits.MAX_PAGES}-page pageInfo: ${(System.nanoTime() - t1) / 1_000_000} ms")
        assertTrue(info is PdfPageInfo.Ok && info.pages.size == RenderLimits.MAX_PAGES && info.pages.all { it == PageDims(10f, 20f) })
    }

    @Test
    fun pageInfoCancelled() {
        val pdf = Fixtures.textPdf(dir, "t.pdf", listOf("a", "b"))
        val cancelled = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            override fun isCancelled() = true
        }
        assertEquals(PdfPageInfo.Err(PdfError.Cancelled), engine.pageInfo(pdf, cancelled))
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun encryptedCorruptAndMissingInputs() {
        val req = RenderRequest(0, 1f, 0, 0, 10, 10)
        val enc = Fixtures.encryptedPdf(dir)
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(enc))
        assertRenderErr(enc, req, { it == PdfError.Encrypted })
        val owner = Fixtures.ownerPasswordPdf(dir)
        assertRenderErr(owner, req, { it == PdfError.Encrypted })
        val garbage = Fixtures.corruptPdf(dir)
        assertEquals(PdfPageInfo.Err(PdfError.Corrupt), engine.pageInfo(garbage))
        assertRenderErr(garbage, req, { it == PdfError.Corrupt })
        assertRenderErr(Fixtures.emptyPdfFile(dir), req, { it == PdfError.Corrupt })
        val missing = File(dir, "missing.pdf")
        assertTrue(engine.pageInfo(missing) is PdfPageInfo.Err)
        assertRenderErr(missing, req, { it is PdfError.Io })
    }

    @Test
    fun pageOverTheAnnotationCapIsRefusedBeforeLoading() {
        val pdf = File(dir, "annots.pdf").apply {
            writeBytes(HostilePdf.sharedAnnots(ObjectGraph.MAX_ANNOTS_PER_PAGE + 500, 1))
        }
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(pdf))
        assertRenderErr(pdf, RenderRequest(0, 1f, 0, 0, 5, 5), { it == PdfError.OutOfMemory })
    }

    @Test
    fun invalidRequestsAreIoIllegalArgument() {
        val pdf = Fixtures.quadrantPdf(dir, "q.pdf", listOf(QuadrantPage(200f, 100f)))
        assertRenderErr(pdf, RenderRequest(1, 1f, 0, 0, 10, 10), isBadRequest) // past the end
        assertRenderErr(pdf, RenderRequest(-1, 1f, 0, 0, 10, 10), isBadRequest)
        assertRenderErr(pdf, RenderRequest(0, 17f, 0, 0, 10, 10), isBadRequest)
        assertRenderErr(pdf, RenderRequest(0, Float.NaN, 0, 0, 10, 10), isBadRequest)
        assertRenderErr(pdf, RenderRequest(0, 1f, 0, 0, 0, 10), isBadRequest)
        assertRenderErr(pdf, RenderRequest(0, 1f, -1, 0, 10, 10), isBadRequest)
        assertRenderErr(pdf, RenderRequest(0, 16f, 0, 0, 3000, 2667), isBadRequest) // > MAX_PIXELS
        assertRenderErr(pdf, RenderRequest(0, 1f, 0, 0, 201, 100), isBadRequest) // past the page edge
        assertRenderErr(pdf, RenderRequest(0, 1f, 191, 91, 10, 10), isBadRequest)
        // The bad request is rejected before the (missing) file is even opened.
        assertRenderErr(File(dir, "missing.pdf"), RenderRequest(0, 0f, 0, 0, 1, 1), isBadRequest)
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun cancelBeforeStart() {
        val pdf = Fixtures.quadrantPdf(dir, "q.pdf", listOf(QuadrantPage(200f, 100f)))
        val cancelled = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            override fun isCancelled() = true
        }
        assertRenderErr(pdf, RenderRequest(0, 1f, 0, 0, 200, 100), { it == PdfError.Cancelled }, cancelled)
    }

    @Test
    fun cancelDuringSlowRenderReturnsPromptly() {
        val pdf = Fixtures.manyFillsPdf(dir, "slow.pdf", fills = SLOW_FILLS)
        val req = RenderRequest(0, 5.6f, 0, 0, 2800, 2800)

        val t0 = System.nanoTime()
        val img = render(pdf, req)
        val fullMs = (System.nanoTime() - t0) / 1_000_000
        println("TIMING slow page ($SLOW_FILLS full-page fills, 2800x2800) uncancelled: $fullMs ms")
        assertColor(img, 1400, 1400, Fixtures.GREEN, "last fill wins")
        assertTrue("fixture must be slow to prove anything (took $fullMs ms)", fullMs >= 1_000)

        val cancelAt = System.nanoTime() + 300_000_000L
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            override fun isCancelled() = System.nanoTime() >= cancelAt
        }
        val t1 = System.nanoTime()
        assertRenderErr(pdf, req, { it == PdfError.Cancelled }, job)
        val cancelMs = (System.nanoTime() - t1) / 1_000_000
        println("TIMING slow page cancelled after 300 ms returned at: $cancelMs ms")
        assertTrue("cancel returned after $cancelMs ms (uncancelled $fullMs ms)", cancelMs < fullMs / 2)
    }

    // ------------------------------------------------------------------ timings (printed)

    @Test
    fun timings() {
        val texts = Fixtures.textPdf(dir, "hundred.pdf", (1..100).map { "Page $it of a text document" })
        repeat(2) { run ->
            val t0 = System.nanoTime()
            val info = engine.pageInfo(texts)
            println("TIMING open+pageInfo 100-page text PDF (run ${run + 1}): ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(100, (info as PdfPageInfo.Ok).pages.size)
        }
        println("TIMING 100-page text PDF is ${texts.length()} bytes")
        val photo = Fixtures.photoPdf(dir, "photo.pdf", pages = 1, imageWidth = 1600, imageHeight = 1200)
        println("TIMING 1-page photo PDF (1600x1200 JPEG) is ${photo.length()} bytes")
        repeat(2) { run ->
            val t0 = System.nanoTime()
            engine.pageInfo(photo)
            println("TIMING open+pageInfo photo PDF (run ${run + 1}): ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        val out = File(dir, "timing.rgba")
        fun timedRender(pdf: File, label: String): Long {
            val dims = (engine.pageInfo(pdf) as PdfPageInfo.Ok).pages[0]
            val req = RenderRequest(0, 2f, 0, 0, RenderLimits.scaledWidth(dims, 2f).toInt(), RenderLimits.scaledHeight(dims, 2f).toInt())
            var best = Long.MAX_VALUE
            repeat(2) { run ->
                val t0 = System.nanoTime()
                assertTrue(engine.renderPage(pdf, req, out, JobControl.None) is PdfRender.Ok)
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("TIMING renderPage only, scale 2, $label ${req.width}x${req.height} (run ${run + 1}): $ms ms")
                best = minOf(best, ms)
            }
            return best
        }
        timedRender(photo, "image page")
        assertAllOpaque(Rgba(out.readBytes(), 3200, 2400))
        timedRender(texts, "text page")
        val img = Rgba(out.readBytes(), 1190, 1684)
        // Text is drawn (some dark pixels near the line at y = 100 pt from the top).
        var dark = 0
        for (y in 160 until 240) for (x in 0 until img.width) if (img.px(x, y)[0] < 128) dark++
        assertTrue("no text pixels", dark > 100)
    }

    // ------------------------------------------------------------------ helpers

    /** A hand-written PDF with [pages] 10x20 pt empty pages in one flat /Pages node (with xref). */
    private fun rawPdf(name: String, pages: Int): File {
        val objs = ArrayList<String>(pages + 2)
        objs.add("<</Type/Catalog/Pages 2 0 R>>")
        val kids = StringBuilder()
        for (i in 0 until pages) kids.append(i + 3).append(" 0 R ")
        objs.add("<</Type/Pages/Kids[$kids]/Count $pages>>")
        repeat(pages) { objs.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 10 20]>>") }
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = IntArray(objs.size)
        for ((i, o) in objs.withIndex()) {
            offsets[i] = sb.length
            sb.append(i + 1).append(" 0 obj\n").append(o).append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append(String.format("%010d 00000 n \n", off))
        sb.append("trailer\n<</Size ${objs.size + 1}/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return File(dir, name).apply { writeBytes(sb.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    private companion object {
        /** About 7 ms per full 2800x2800 fill on the dev machine: ~3.5 s uncancelled. */
        const val SLOW_FILLS = 500
    }
}
