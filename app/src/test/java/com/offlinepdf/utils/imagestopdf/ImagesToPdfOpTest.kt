package com.offlinepdf.utils.imagestopdf

import com.offlinepdf.utils.Fixtures
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.MuPdfEngine
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.engine.ops.ImagesToPdfOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plain JUnit with real MuPDF: [ImagesToPdfOp] and [MuPdfEngine.imagesToPdf]. */
class ImagesToPdfOpTest {
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

    @Test
    fun threeMixedImagesBecomeThreePagesInOrder() {
        val jpeg = Fixtures.jpeg(tmp.root, "a.jpg", width = 400, height = 300)
        val png = Fixtures.png(tmp.root, "b.png", width = 200, height = 500)
        val alpha = Fixtures.pngWithAlpha(tmp.root, "c.png", width = 333, height = 111)
        val out = out()
        val job = RecordingJob()

        val r = ok(ImagesToPdfOp().run(listOf(jpeg, png, alpha), PageSize.FIT_IMAGE, out, job))

        assertEquals(3, r.pages)
        assertEquals(out.length(), r.bytes)
        assertEquals(3, Fixtures.pageCount(out))
        val sizes = Fixtures.pageSizes(out)
        assertSize(400f, 300f, sizes[0])
        assertSize(200f, 500f, sizes[1])
        assertSize(333f, 111f, sizes[2])
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), job.progress)
        // The alpha PNG keeps its transparency as an SMask.
        assertTrue(Fixtures.imageInfos(out).any { it.hasSMask })
    }

    @Test
    fun reversedInputGivesReversedPages() {
        val jpeg = Fixtures.jpeg(tmp.root, "a.jpg", width = 400, height = 300)
        val png = Fixtures.png(tmp.root, "b.png", width = 200, height = 500)
        val out = out()
        ok(ImagesToPdfOp().run(listOf(png, jpeg), PageSize.FIT_IMAGE, out, JobControl.None))
        val sizes = Fixtures.pageSizes(out)
        assertSize(200f, 500f, sizes[0])
        assertSize(400f, 300f, sizes[1])
    }

    @Test
    fun fitImageUsesDisplayedSizeOfRotatedJpeg() {
        // Stored 300x200 with EXIF 6 (quarter turn) -> displayed 200x300.
        val img = Fixtures.jpegWithExifOrientation(tmp.root, "rot.jpg", 300, 200, 6)
        val out = out()
        ok(ImagesToPdfOp().run(listOf(img), PageSize.FIT_IMAGE, out, JobControl.None))
        assertSize(200f, 300f, Fixtures.pageSizes(out)[0])
    }

    @Test
    fun a4AndLetterFollowImageOrientation() {
        val portrait = Fixtures.jpeg(tmp.root, "p.jpg", width = 300, height = 400)
        val landscape = Fixtures.png(tmp.root, "l.png", width = 400, height = 300)

        val a4 = out("a4.pdf")
        ok(ImagesToPdfOp().run(listOf(portrait, landscape), PageSize.A4, a4, JobControl.None))
        val a4Sizes = Fixtures.pageSizes(a4)
        assertSize(595f, 842f, a4Sizes[0])
        assertSize(842f, 595f, a4Sizes[1])

        val letter = out("letter.pdf")
        ok(ImagesToPdfOp().run(listOf(portrait, landscape), PageSize.LETTER, letter, JobControl.None))
        val letterSizes = Fixtures.pageSizes(letter)
        assertSize(612f, 792f, letterSizes[0])
        assertSize(792f, 612f, letterSizes[1])
    }

    @Test
    fun exifOrientation6ComesOutUpright() {
        val img = Fixtures.jpegWithExifOrientation(tmp.root, "rot.jpg", 300, 200, 6)
        val out = out()
        ok(ImagesToPdfOp().run(listOf(img), PageSize.FIT_IMAGE, out, JobControl.None))
        val c = Fixtures.renderCornerColors(out, 0)
        assertTrue("TL ${c.topLeft.toString(16)}", Fixtures.colorNear(c.topLeft, Fixtures.RED))
        assertTrue("TR ${c.topRight.toString(16)}", Fixtures.colorNear(c.topRight, Fixtures.GREEN))
        assertTrue("BL ${c.bottomLeft.toString(16)}", Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
        assertTrue("BR ${c.bottomRight.toString(16)}", Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
    }

    @Test
    fun jpegStreamIsEmbeddedUnchanged() {
        val jpeg = Fixtures.jpeg(tmp.root, "a.jpg", width = 640, height = 480)
        val out = out()
        ok(ImagesToPdfOp().run(listOf(jpeg), PageSize.A4, out, JobControl.None))
        val infos = Fixtures.imageInfos(out)
        assertEquals(1, infos.size)
        assertEquals("DCTDecode", infos[0].filter)
        assertEquals(jpeg.length().toInt(), infos[0].rawLength)
    }

    @Test
    fun notAnImageAnywhereGivesUnsupportedImageAndNoOutput() {
        val good = Fixtures.jpeg(tmp.root, "a.jpg")
        val bad = Fixtures.notAnImage(tmp.root)
        for ((i, list) in listOf(listOf(bad, good), listOf(good, bad), listOf(good, bad, good)).withIndex()) {
            val out = out("bad$i.pdf")
            val r = MuPdfEngine().imagesToPdf(list, PageSize.FIT_IMAGE, out)
            assertEquals(PdfResult.Err(PdfError.UnsupportedImage), r)
            assertFalse(out.exists())
        }
    }

    @Test
    fun cancelAfterFirstImageGivesCancelledAndNoOutput() {
        val images = (1..3).map { Fixtures.jpeg(tmp.root, "i$it.jpg", seed = it.toLong()) }
        val opOut = out("op.pdf")
        val opJob = RecordingJob(cancelAfterProgressCalls = 1)
        assertEquals(PdfResult.Err(PdfError.Cancelled), ImagesToPdfOp().run(images, PageSize.A4, opOut, opJob))
        assertFalse(opOut.exists())
        assertEquals(listOf(1 to 3), opJob.progress)

        val engineOut = out("engine.pdf")
        val engineJob = RecordingJob(cancelAfterProgressCalls = 2)
        assertEquals(PdfResult.Err(PdfError.Cancelled), MuPdfEngine().imagesToPdf(images, PageSize.A4, engineOut, engineJob))
        assertFalse(engineOut.exists())
        assertEquals(listOf(1 to 3, 2 to 3), engineJob.progress)
    }

    @Test
    fun emptyListIsAnIoError() {
        val out = out()
        val r = MuPdfEngine().imagesToPdf(emptyList(), PageSize.FIT_IMAGE, out)
        val err = (r as PdfResult.Err).error
        assertTrue(err is PdfError.Io && err.cause is IllegalArgumentException)
        assertFalse(out.exists())
    }

    @Test
    fun worksThroughMuPdfEngine() {
        val jpeg = Fixtures.jpeg(tmp.root, "a.jpg", width = 400, height = 300)
        val alpha = Fixtures.pngWithAlpha(tmp.root, "c.png", width = 120, height = 80)
        val out = out()
        val job = RecordingJob()
        val r = ok(MuPdfEngine().imagesToPdf(listOf(jpeg, alpha), PageSize.LETTER, out, job))
        assertEquals(2, r.pages)
        assertEquals(out.length(), r.bytes)
        assertEquals(2, Fixtures.pageCount(out))
        assertEquals(listOf(1 to 2, 2 to 2), job.progress)
        // Inputs are never written to.
        assertTrue(jpeg.length() > 0 && alpha.length() > 0)
    }
}
