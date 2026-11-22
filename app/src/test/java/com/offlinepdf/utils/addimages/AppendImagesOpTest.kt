package com.offlinepdf.utils.addimages

import com.offlinepdf.utils.Fixtures
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.MuPdfEngine
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.engine.ops.AppendImagesOp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plain JUnit with real MuPDF: [AppendImagesOp] and [MuPdfEngine.appendImages]. */
class AppendImagesOpTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private fun assertSize(w: Float, h: Float, actual: Pair<Float, Float>) {
        assertEquals("width", w, actual.first, 0.01f)
        assertEquals("height", h, actual.second, 0.01f)
    }

    private fun ok(r: PdfResult): PdfResult.Ok {
        assertTrue("expected Ok, got $r", r is PdfResult.Ok)
        return r as PdfResult.Ok
    }

    private class RecordingJob(private val cancelAfterProgressCalls: Int = Int.MAX_VALUE) : JobControl {
        val progress = mutableListOf<Pair<Int, Int>>()
        override fun progress(done: Int, total: Int) { progress += done to total }
        override fun isCancelled() = progress.size >= cancelAfterProgressCalls
    }

    private fun isWhite(c: Int) = (0..2).all { k -> (c shr (8 * k) and 0xff) > 240 }

    /** Bounding box of non-white pixels as fractions of the rendered page: x0, y0, x1, y1. */
    private fun inkBox(file: File, page: Int): FloatArray {
        val r = Fixtures.render(file, page, 400)
        var x0 = r.width; var y0 = r.height; var x1 = -1; var y1 = -1
        for (y in 0 until r.height) for (x in 0 until r.width) {
            if (!isWhite(r.at(x, y))) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        assertTrue("page $page is blank", x1 >= 0)
        return floatArrayOf(
            x0.toFloat() / r.width, y0.toFloat() / r.height,
            (x1 + 1).toFloat() / r.width, (y1 + 1).toFloat() / r.height,
        )
    }

    private fun assertBox(expected: FloatArray, actual: FloatArray, msg: String) {
        for (i in 0..3) assertEquals("$msg [$i] ${actual.toList()}", expected[i], actual[i], 0.02f)
    }

    @Test
    fun twoPagePdfPlusThreeImagesGivesFivePagesInOrder() {
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("First page", "Second page"))
        val wide = Fixtures.jpeg(tmp.root, "wide.jpg", width = 400, height = 100)
        val tall = Fixtures.png(tmp.root, "tall.png", width = 100, height = 400)
        val square = Fixtures.pngWithAlpha(tmp.root, "square.png", width = 200, height = 200)
        val textsBefore = Fixtures.pageTexts(pdf)
        val out = out()
        val job = RecordingJob()

        val r = ok(AppendImagesOp().run(pdf, listOf(wide, tall, square), out, job))

        assertEquals(5, r.pages)
        assertEquals(out.length(), r.bytes)
        assertEquals(5, Fixtures.pageCount(out))
        assertEquals(textsBefore, Fixtures.pageTexts(out).take(2))
        assertTrue(textsBefore[0].contains("First page"))
        val sizes = Fixtures.pageSizes(out)
        for (s in sizes) assertSize(Fixtures.A4_WIDTH, Fixtures.A4_HEIGHT, s)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), job.progress)

        // Order: page 3 shows the wide image (full width band), page 4 the tall one (full height
        // column), page 5 the square one.
        val w = Fixtures.A4_WIDTH; val h = Fixtures.A4_HEIGHT
        val wideH = w / 4 / h
        assertBox(floatArrayOf(0f, (1 - wideH) / 2, 1f, (1 + wideH) / 2), inkBox(out, 2), "wide")
        val tallW = h / 4 / w
        assertBox(floatArrayOf((1 - tallW) / 2, 0f, (1 + tallW) / 2, 1f), inkBox(out, 3), "tall")
        val sq = inkBox(out, 4)
        assertEquals("square width", 1f, sq[2], 0.02f)
        assertEquals("square centred", 0.5f, (sq[1] + sq[3]) / 2, 0.02f)
        assertTrue(Fixtures.imageInfos(out).any { it.hasSMask })
    }

    @Test
    fun newPagesTakeTheSizeOfTheOriginalLastPageAndImageIsCentred() {
        val pdf = Fixtures.textPdf(tmp.root, "small.pdf", listOf("Only"), pageWidth = 400f, pageHeight = 300f)
        val img = Fixtures.jpeg(tmp.root, "a.jpg", width = 300, height = 400)
        val out = out()
        ok(AppendImagesOp().run(pdf, listOf(img, img), out, JobControl.None))
        val sizes = Fixtures.pageSizes(out)
        assertEquals(3, sizes.size)
        for (s in sizes) assertSize(400f, 300f, s)
        // 300x400 image on a 400x300 page: 225 pt wide, full height, centred.
        val box = inkBox(out, 1)
        assertBox(floatArrayOf(87.5f / 400, 0f, 312.5f / 400, 1f), box, "fitted")
    }

    @Test
    fun landscapeImageOnPortraitPageIsFittedAndCentred() {
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("A"))
        val img = Fixtures.png(tmp.root, "l.png", width = 400, height = 300)
        val out = out()
        ok(AppendImagesOp().run(pdf, listOf(img), out, JobControl.None))
        assertSize(Fixtures.A4_WIDTH, Fixtures.A4_HEIGHT, Fixtures.pageSizes(out)[1])
        val r = Fixtures.render(out, 1, 400)
        // Image height = 595 * 3/4 = 446.25 pt of 842 -> occupies the middle 53%.
        assertTrue("top band white", isWhite(r.at(r.width / 2, r.height / 10)))
        assertTrue("bottom band white", isWhite(r.at(r.width / 2, r.height * 9 / 10)))
        assertFalse("centre has image", isWhite(r.at(r.width / 2, r.height / 2)))
        assertFalse("left edge at centre has image", isWhite(r.at(2, r.height / 2)))
    }

    @Test
    fun linksOnOriginalPagesSurvive() {
        val uri = "https://example.com/doc"
        val pdf = Fixtures.linkPdf(tmp.root, "link.pdf", uri)
        val img = Fixtures.jpeg(tmp.root, "a.jpg")
        val out = out()
        ok(AppendImagesOp().run(pdf, listOf(img), out, JobControl.None))
        val links = Fixtures.links(out, 0)
        assertEquals(1, links.size)
        assertEquals(uri, links[0].first)
        assertArrayEquals(Fixtures.LINK_RECT, links[0].second, 0.5f)
        assertEquals(listOf("Link page"), Fixtures.pageTexts(out).take(1).map { it.trim() })
    }

    @Test
    fun jpegIsEmbeddedUnchanged() {
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("A"))
        val jpeg = Fixtures.jpeg(tmp.root, "a.jpg", width = 640, height = 480)
        val out = out()
        ok(AppendImagesOp().run(pdf, listOf(jpeg), out, JobControl.None))
        val infos = Fixtures.imageInfos(out)
        assertEquals(1, infos.size)
        assertEquals("DCTDecode", infos[0].filter)
        assertEquals(jpeg.length().toInt(), infos[0].rawLength)
    }

    @Test
    fun exifOrientation6ComesOutUpright() {
        // Stored 300x200, EXIF 6 -> displayed 200x300; on a 200x300 page it fills the page exactly.
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("A"), pageWidth = 200f, pageHeight = 300f)
        val img = Fixtures.jpegWithExifOrientation(tmp.root, "rot.jpg", 300, 200, 6)
        val out = out()
        ok(AppendImagesOp().run(pdf, listOf(img), out, JobControl.None))
        val c = Fixtures.renderCornerColors(out, 1)
        assertTrue("TL ${c.topLeft.toString(16)}", Fixtures.colorNear(c.topLeft, Fixtures.RED))
        assertTrue("TR ${c.topRight.toString(16)}", Fixtures.colorNear(c.topRight, Fixtures.GREEN))
        assertTrue("BL ${c.bottomLeft.toString(16)}", Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
        assertTrue("BR ${c.bottomRight.toString(16)}", Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
    }

    @Test
    fun badInputsGiveErrorsAndNoOutput() {
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("A"))
        val good = Fixtures.jpeg(tmp.root, "a.jpg")
        val bad = Fixtures.notAnImage(tmp.root)
        val cases = listOf(
            Triple(pdf, listOf(good, bad), PdfError.UnsupportedImage),
            Triple(pdf, listOf(bad), PdfError.UnsupportedImage),
            Triple(Fixtures.encryptedPdf(tmp.root), listOf(good), PdfError.Encrypted),
            Triple(Fixtures.corruptPdf(tmp.root), listOf(good), PdfError.Corrupt),
        )
        for ((i, c) in cases.withIndex()) {
            val out = out("bad$i.pdf")
            assertEquals("case $i", PdfResult.Err(c.third), MuPdfEngine().appendImages(c.first, c.second, out))
            assertFalse("case $i", out.exists())
        }
        val missingOut = out("missing-out.pdf")
        val missing = MuPdfEngine().appendImages(File(tmp.root, "missing.pdf"), listOf(good), missingOut)
        assertTrue("missing: $missing", missing is PdfResult.Err && missing.error is PdfError.Io)
        assertFalse(missingOut.exists())

        val emptyOut = out("empty-out.pdf")
        val empty = MuPdfEngine().appendImages(pdf, emptyList(), emptyOut)
        assertTrue("empty: $empty", empty is PdfResult.Err && (empty.error as? PdfError.Io)?.cause is IllegalArgumentException)
        assertFalse(emptyOut.exists())
    }

    @Test
    fun inputPdfIsNotModified() {
        val pdf = Fixtures.linkPdf(tmp.root, "link.pdf", "https://example.com/")
        val before = pdf.readBytes()
        val out = out()
        ok(MuPdfEngine().appendImages(pdf, listOf(Fixtures.jpeg(tmp.root, "a.jpg")), out))
        assertArrayEquals(before, pdf.readBytes())
        assertEquals(1, Fixtures.pageCount(pdf))
    }

    @Test
    fun cancelAfterFirstImageGivesCancelledAndNoOutput() {
        val pdf = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("A", "B"))
        val images = (1..3).map { Fixtures.jpeg(tmp.root, "i$it.jpg", seed = it.toLong()) }
        val opOut = out("op.pdf")
        val opJob = RecordingJob(cancelAfterProgressCalls = 1)
        assertEquals(PdfResult.Err(PdfError.Cancelled), AppendImagesOp().run(pdf, images, opOut, opJob))
        assertFalse(opOut.exists())
        assertEquals(listOf(1 to 3), opJob.progress)

        val engineOut = out("engine.pdf")
        val engineJob = RecordingJob(cancelAfterProgressCalls = 1)
        assertEquals(PdfResult.Err(PdfError.Cancelled), MuPdfEngine().appendImages(pdf, images, engineOut, engineJob))
        assertFalse(engineOut.exists())
    }
}
