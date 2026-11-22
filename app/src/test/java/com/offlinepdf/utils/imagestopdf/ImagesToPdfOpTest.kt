package com.offlinepdf.utils.imagestopdf

import com.offlinepdf.utils.Fixtures
import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.MuPdfEngine
import com.offlinepdf.utils.engine.PDF_DOCUMENT_OVERHEAD_BYTES
import com.offlinepdf.utils.engine.PDF_PAGE_OVERHEAD_BYTES
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfEstimate
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

    /* ------------------------------------------------------------------ compression levels */

    private val levels: List<CompressionLevel?> = listOf(null, CompressionLevel.LOW, CompressionLevel.MEDIUM, CompressionLevel.HIGH)

    private fun estimate(f: File): PdfEstimate.Ok {
        val e = MuPdfEngine().estimateImage(f)
        assertTrue("expected Ok, got $e", e is PdfEstimate.Ok)
        return e as PdfEstimate.Ok
    }

    @Test
    fun levelsShrinkOutputAndCapLongEdgeButKeepPageSizes() {
        val images = (1..3).map { Fixtures.jpeg(tmp.root, "p$it.jpg", width = 3200, height = 2400, seed = 40L + it) }
        val sizes = LinkedHashMap<CompressionLevel?, Long>()
        val pageSizes = HashSet<List<Pair<Float, Float>>>()
        val expectedEdge = mapOf(null to 3200, CompressionLevel.LOW to 2000, CompressionLevel.MEDIUM to 1500, CompressionLevel.HIGH to 1000)
        for (level in levels) {
            val out = out("lvl-$level.pdf")
            val r = ok(MuPdfEngine().imagesToPdf(images, PageSize.FIT_IMAGE, out, compression = level))
            assertEquals(3, r.pages)
            sizes[level] = r.bytes
            val infos = Fixtures.imageInfos(out)
            assertEquals("$level", 3, infos.size)
            for (info in infos) {
                assertEquals("$level long edge", expectedEdge[level], maxOf(info.width, info.height))
                assertEquals("$level aspect", 4.0 / 3.0, info.width.toDouble() / info.height, 0.01)
                assertEquals("DCTDecode", info.filter)
            }
            pageSizes += Fixtures.pageSizes(out)
        }
        assertEquals("page sizes identical at every level", 1, pageSizes.size)
        assertSize(3200f, 2400f, pageSizes.single()[0])
        assertTrue("sizes $sizes", sizes[CompressionLevel.HIGH]!! < sizes[CompressionLevel.MEDIUM]!!)
        assertTrue("sizes $sizes", sizes[CompressionLevel.MEDIUM]!! < sizes[CompressionLevel.LOW]!!)
        assertTrue("sizes $sizes", sizes[CompressionLevel.LOW]!! < sizes[null]!!)
    }

    @Test
    fun smallJpegIsNotUpscaledAndNeverLarger() {
        val small = Fixtures.jpeg(tmp.root, "s.jpg", width = 400, height = 300)
        val plain = out("plain.pdf")
        val plainBytes = ok(MuPdfEngine().imagesToPdf(listOf(small), PageSize.A4, plain)).bytes
        for (level in CompressionLevel.entries) {
            val out = out("s-$level.pdf")
            val r = ok(MuPdfEngine().imagesToPdf(listOf(small), PageSize.A4, out, compression = level))
            val info = Fixtures.imageInfos(out).single()
            assertTrue("$level ${info.width}x${info.height}", info.width <= 400 && info.height <= 300)
            assertTrue("$level ${r.bytes} > $plainBytes", r.bytes <= plainBytes)
            assertEquals(Fixtures.pageSizes(plain), Fixtures.pageSizes(out))
        }
        // Already-small, high-quality data: the re-encode is kept only if smaller, else the bytes are verbatim.
        val est = estimate(small)
        for (level in CompressionLevel.entries) assertTrue(est.byLevel.getValue(level) <= est.original)
    }

    @Test
    fun exifOrientation6IsUprightAtEveryLevel() {
        // 3000x2000 stored, displayed 2000x3000: every level re-encodes (long edge 2000 / 1500 / 1000).
        val img = Fixtures.jpegWithExifOrientation(tmp.root, "rot.jpg", 3000, 2000, 6)
        val edge = mapOf(null to 3000, CompressionLevel.LOW to 2000, CompressionLevel.MEDIUM to 1500, CompressionLevel.HIGH to 1000)
        for (level in levels) {
            val out = out("rot-$level.pdf")
            ok(MuPdfEngine().imagesToPdf(listOf(img), PageSize.FIT_IMAGE, out, compression = level))
            assertSize(2000f, 3000f, Fixtures.pageSizes(out)[0])
            val info = Fixtures.imageInfos(out).single()
            assertEquals("$level embedded long edge", edge[level], maxOf(info.width, info.height))
            val c = Fixtures.renderCornerColors(out, 0)
            assertTrue("$level TL ${c.topLeft.toString(16)}", Fixtures.colorNear(c.topLeft, Fixtures.RED))
            assertTrue("$level TR ${c.topRight.toString(16)}", Fixtures.colorNear(c.topRight, Fixtures.GREEN))
            assertTrue("$level BL ${c.bottomLeft.toString(16)}", Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
            assertTrue("$level BR ${c.bottomRight.toString(16)}", Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
        }
    }

    @Test
    fun alphaPngIsFlattenedOntoWhite() {
        val png = Fixtures.pngWithAlpha(tmp.root, "a.png", width = 1200, height = 800)
        val out = out()
        ok(MuPdfEngine().imagesToPdf(listOf(png), PageSize.FIT_IMAGE, out, compression = CompressionLevel.MEDIUM))
        val info = Fixtures.imageInfos(out).single()
        assertEquals("DCTDecode", info.filter)
        assertFalse(info.hasSMask)
        assertEquals(3, info.components)
        assertSize(1200f, 800f, Fixtures.pageSizes(out)[0])
        // Alpha is 0 at the left edge: the left corners must be white, not black.
        val c = Fixtures.renderCornerColors(out, 0)
        assertTrue("TL ${c.topLeft.toString(16)}", Fixtures.colorNear(c.topLeft, 0xFFFFFF, 40))
        assertTrue("BL ${c.bottomLeft.toString(16)}", Fixtures.colorNear(c.bottomLeft, 0xFFFFFF, 40))
    }

    @Test
    fun grayJpegStaysGray() {
        val gray = Fixtures.grayJpeg(tmp.root, "g.jpg", width = 2400, height = 1600)
        val out = out()
        ok(MuPdfEngine().imagesToPdf(listOf(gray), PageSize.FIT_IMAGE, out, compression = CompressionLevel.MEDIUM))
        val info = Fixtures.imageInfos(out).single()
        assertEquals("DCTDecode", info.filter)
        assertEquals(1500, info.width)
        assertEquals(1, info.components)
    }

    @Test
    fun estimateMatchesRealOutput() {
        val images = listOf(
            Fixtures.jpeg(tmp.root, "e1.jpg", width = 3200, height = 2400, seed = 7L),
            Fixtures.png(tmp.root, "e2.png", width = 1000, height = 700, seed = 8L),
            Fixtures.pngWithAlpha(tmp.root, "e3.png", width = 900, height = 600, seed = 9L),
            Fixtures.grayJpeg(tmp.root, "e4.jpg", width = 2400, height = 1600, seed = 10L),
            Fixtures.jpeg(tmp.root, "e5.jpg", width = 640, height = 480, seed = 11L),
        )
        val estimates = images.map { estimate(it) }
        for (level in levels) {
            val out = out("est-$level.pdf")
            val actual = ok(MuPdfEngine().imagesToPdf(images, PageSize.FIT_IMAGE, out, compression = level)).bytes
            val perImage = estimates.map { if (level == null) it.original else it.byLevel.getValue(level) }
            // Image bytes are exact: the estimate runs the very same encoder.
            assertEquals("$level image bytes", perImage.sum(), Fixtures.imageInfos(out).sumOf { it.rawLength.toLong() })
            val predicted = PDF_DOCUMENT_OVERHEAD_BYTES + perImage.sumOf { it + PDF_PAGE_OVERHEAD_BYTES }
            assertEquals("$level predicted $predicted vs actual $actual", actual.toDouble(), predicted.toDouble(), actual * 0.05)
        }
    }

    @Test
    fun estimateOfBadImagesIsUnsupportedImage() {
        val engine = MuPdfEngine()
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(Fixtures.notAnImage(tmp.root)))
        val jpeg = Fixtures.jpeg(tmp.root, "t.jpg", width = 1600, height = 1200)
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(Fixtures.truncated(jpeg, 1.0 / 3)))
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(File(tmp.root, "missing.jpg")))
    }

    @Test
    fun truncatedJpegGivesUnsupportedImageAndNoOutput() {
        val jpeg = Fixtures.jpeg(tmp.root, "t.jpg", width = 1600, height = 1200)
        for (level in levels) {
            val out = out("t-$level.pdf")
            val r = MuPdfEngine().imagesToPdf(listOf(Fixtures.jpeg(tmp.root), Fixtures.truncated(jpeg, 1.0 / 3)), PageSize.A4, out, compression = level)
            assertEquals(PdfResult.Err(PdfError.UnsupportedImage), r)
            assertFalse(out.exists())
        }
    }

    @Test
    fun progressAndCancellationUnchangedWithCompression() {
        val images = (1..3).map { Fixtures.jpeg(tmp.root, "c$it.jpg", width = 800, height = 600, seed = it.toLong()) }
        val job = RecordingJob()
        ok(MuPdfEngine().imagesToPdf(images, PageSize.A4, out("c-ok.pdf"), job, CompressionLevel.HIGH))
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), job.progress)
        val cancelOut = out("c-cancel.pdf")
        val cancelJob = RecordingJob(cancelAfterProgressCalls = 1)
        assertEquals(PdfResult.Err(PdfError.Cancelled), MuPdfEngine().imagesToPdf(images, PageSize.A4, cancelOut, cancelJob, CompressionLevel.LOW))
        assertFalse(cancelOut.exists())
        assertEquals(listOf(1 to 3), cancelJob.progress)
    }
}
