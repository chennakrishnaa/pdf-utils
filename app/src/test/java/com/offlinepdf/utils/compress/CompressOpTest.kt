package com.offlinepdf.utils.compress

import com.offlinepdf.utils.Fixtures
import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.MuPdfEngine
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.engine.ops.CompressOp
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plain JUnit with real MuPDF: [CompressOp] through [MuPdfEngine.compress]. */
class CompressOpTest {
    private val engine = MuPdfEngine()

    private class RecordingJob(private val cancelAfter: Int = Int.MAX_VALUE) : JobControl {
        val calls = mutableListOf<Pair<Int, Int>>()
        override fun progress(done: Int, total: Int) {
            calls += done to total
        }
        override fun isCancelled() = calls.size >= cancelAfter
    }

    /** Properties that identify an image independently of its (renumbered) object number. */
    private data class Key(val w: Int, val h: Int, val bpc: Int, val filter: String?, val cs: String?, val raw: Int)

    private fun Fixtures.ImageInfo.key() = Key(width, height, bitsPerComponent, filter, colorSpaceFamily, rawLength)

    private fun newOut(name: String) = File(dir, name).also { it.delete() }

    /* ------------------------------------------------------------------ photo fixture */

    @Test
    fun photo_sizesStrictlyDecreaseWithLevel() {
        val orig = photo.length()
        val low = photoOut.getValue(CompressionLevel.LOW).length()
        val medium = photoOut.getValue(CompressionLevel.MEDIUM).length()
        val high = photoOut.getValue(CompressionLevel.HIGH).length()
        val msg = "orig=$orig low=$low medium=$medium high=$high"
        assertTrue(msg, low * 1.1 <= orig)
        assertTrue(msg, medium * 1.1 <= low)
        assertTrue(msg, high * 1.1 <= medium)
    }

    @Test
    fun photo_imagesCappedAtLongEdgeWithAspectRatioAndJpeg() {
        for ((level, out) in photoOut) {
            val infos = Fixtures.imageInfos(out)
            assertEquals("$level: image count", 2, infos.size)
            for (info in infos) {
                assertEquals("$level long edge", level.maxLongEdgePx, maxOf(info.width, info.height))
                val expectedH = (info.width * 2400.0 / 3200.0).roundToInt()
                assertTrue("$level aspect ${info.width}x${info.height}", abs(info.height - expectedH) <= 1)
                assertEquals("$level filter", "DCTDecode", info.filter)
                assertEquals("$level colour space", "DeviceRGB", info.colorSpaceFamily)
                assertEquals(8, info.bitsPerComponent)
            }
        }
    }

    @Test
    fun photo_pagesTextsAndRenderingPreserved() {
        for ((level, out) in photoOut) assertSamePages(level.name, photo, out)
    }

    @Test
    fun photo_resultReportsPagesAndBytes() {
        for ((level, out) in photoOut) {
            assertEquals("$level", PdfResult.Ok(2, out.length()), photoResults.getValue(level))
        }
    }

    @Test
    fun noImageGrows() {
        val inputs = listOf(photo, mixed)
        for (input in inputs) for (level in CompressionLevel.entries) {
            val out = if (input === photo) photoOut.getValue(level) else mixedOut.getValue(level)
            assertNoImageGrows("${input.name} $level", input, out)
        }
    }

    @Test
    fun inputFileIsUnchanged() {
        assertArrayEquals(photoBytes, photo.readBytes())
        assertArrayEquals(mixedBytes, mixed.readBytes())
    }

    @Test
    fun smallImageIsNotUpscaled() {
        val small = Fixtures.photoPdf(dir, "small.pdf", pages = 1, imageWidth = 800, imageHeight = 600)
        val out = newOut("small-out.pdf")
        val r = engine.compress(small, CompressionLevel.LOW, out)
        assertTrue("$r", r is PdfResult.Ok)
        val infos = Fixtures.imageInfos(out)
        assertEquals(1, infos.size)
        assertEquals(800, infos[0].width)
        assertEquals(600, infos[0].height)
        assertEquals("DCTDecode", infos[0].filter)
        assertTrue(out.length() <= small.length())
        assertSamePages("small", small, out)
    }

    /* ------------------------------------------------------------------ mixed fixture */

    @Test
    fun mixed_maskedOneBitAndIndexedUntouched_grayStaysGray() {
        val before = Fixtures.imageInfos(mixed)
        val untouched = before.filter {
            it.hasSMask || it.isMaskTarget || it.bitsPerComponent == 1 || it.colorSpaceFamily == "Indexed"
        }
        assertEquals("soft-masked image, its mask, 1-bit, Indexed: $before", 4, untouched.size)
        val grayBefore = before.single { it.colorSpaceFamily == "DeviceGray" && it.filter == "DCTDecode" && !it.isMaskTarget }

        for ((level, out) in mixedOut) {
            val after = Fixtures.imageInfos(out)
            assertEquals("$level image count", before.size, after.size)
            val afterKeys = after.map { it.key() }
            for (u in untouched) assertTrue("$level: untouched $u not found in $after", u.key() in afterKeys)
            // the soft-masked image still has its mask
            assertTrue("$level: SMask kept", after.any { it.hasSMask })
            assertTrue("$level: mask target kept", after.any { it.isMaskTarget })

            val untouchedKeys = untouched.map { it.key() }
            val rest = after.filter { it.key() !in untouchedKeys }
            val gray = rest.single()
            assertEquals("$level gray colour space", "DeviceGray", gray.colorSpaceFamily)
            assertEquals("$level gray filter", "DCTDecode", gray.filter)
            assertEquals(8, gray.bitsPerComponent)
            assertTrue("$level gray grew: ${gray.rawLength} > ${grayBefore.rawLength}", gray.rawLength <= grayBefore.rawLength)
            assertTrue("$level gray long edge ${gray.width}", maxOf(gray.width, gray.height) <= maxOf(level.maxLongEdgePx, 2400))

            assertSamePages("mixed $level", mixed, out)
        }
    }

    /* ------------------------------------------------------------------ whole-file guarantee */

    @Test
    fun textOnly_neverLarger_textsIntact() {
        val text = Fixtures.textPdf(dir, "text.pdf", listOf("First text page", "Second text page"))
        val bytes = text.readBytes()
        for (level in CompressionLevel.entries) {
            val out = newOut("text-$level.pdf")
            val r = engine.compress(text, level, out)
            assertEquals("$level", PdfResult.Ok(2, out.length()), r)
            assertTrue("$level: ${out.length()} > ${text.length()}", out.length() <= text.length())
            assertEquals(Fixtures.pageTexts(text), Fixtures.pageTexts(out))
        }
        assertArrayEquals(bytes, text.readBytes())
    }

    @Test
    fun recompressingCompressedOutputNeverGrows() {
        val first = photoOut.getValue(CompressionLevel.HIGH)
        for (level in CompressionLevel.entries) {
            val again = newOut("again-$level.pdf")
            val r = engine.compress(first, level, again)
            assertEquals("$level", PdfResult.Ok(2, again.length()), r)
            assertTrue("$level: ${again.length()} > ${first.length()}", again.length() <= first.length())
            assertSamePages("again $level", first, again)
        }
    }

    /* ------------------------------------------------------------------ errors */

    @Test
    fun encrypted_isErrEncrypted_noOutput() {
        val out = newOut("enc-out.pdf")
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.compress(Fixtures.encryptedPdf(dir), CompressionLevel.MEDIUM, out))
        assertFalse(out.exists())
    }

    @Test
    fun corrupt_isErrCorrupt_noOutput() {
        val out = newOut("corrupt-out.pdf")
        assertEquals(PdfResult.Err(PdfError.Corrupt), engine.compress(Fixtures.corruptPdf(dir), CompressionLevel.MEDIUM, out))
        assertFalse(out.exists())
    }

    @Test
    fun missingFile_isErrIo_noOutput() {
        val out = newOut("missing-out.pdf")
        val r = engine.compress(File(dir, "does-not-exist.pdf"), CompressionLevel.MEDIUM, out)
        assertTrue("$r", r is PdfResult.Err && r.error is PdfError.Io)
        assertFalse(out.exists())
    }

    /* ------------------------------------------------------------------ progress / cancellation */

    @Test
    fun progressMonotonicEndsAtTotal() {
        val calls = photoProgress
        assertTrue(calls.isNotEmpty())
        for (k in 1 until calls.size) {
            assertTrue("not monotonic: $calls", calls[k].first >= calls[k - 1].first)
            assertEquals("total changed: $calls", calls[0].second, calls[k].second)
        }
        assertEquals(2 to 2, calls.last())
    }

    @Test
    fun progressWithoutImagesNeverDividesByZero() {
        val text = Fixtures.textPdf(dir, "noimg.pdf", listOf("No images"))
        val job = RecordingJob()
        assertTrue(engine.compress(text, CompressionLevel.HIGH, newOut("noimg-out.pdf"), job) is PdfResult.Ok)
        assertTrue("${job.calls}", job.calls.all { it.second > 0 })
        assertEquals(1 to 1, job.calls.last())
    }

    @Test
    fun cancelAfterFirstProgress_isCancelled_noOutput() {
        val out = newOut("cancel-out.pdf")
        val job = RecordingJob(cancelAfter = 1)
        assertEquals(PdfResult.Err(PdfError.Cancelled), engine.compress(photo, CompressionLevel.HIGH, out, job))
        assertEquals(1, job.calls.size)
        assertFalse(out.exists())
        assertArrayEquals(photoBytes, photo.readBytes())
    }

    /* ------------------------------------------------------------------ helpers */

    private fun assertNoImageGrows(label: String, input: File, out: File) {
        // Each output image must be no larger than its input counterpart. Sorted pairing is implied
        // by (and the necessary condition of) any such one-to-one correspondence.
        val inLens = Fixtures.imageInfos(input).map { it.rawLength }.sorted()
        val outLens = Fixtures.imageInfos(out).map { it.rawLength }.sorted()
        assertEquals("$label image count", inLens.size, outLens.size)
        inLens.zip(outLens).forEach { (i, o) -> assertTrue("$label: $o > $i ($inLens -> $outLens)", o <= i) }
    }

    private fun assertSamePages(label: String, a: File, b: File) {
        assertEquals("$label page count", Fixtures.pageCount(a), Fixtures.pageCount(b))
        val sa = Fixtures.pageSizes(a)
        val sb = Fixtures.pageSizes(b)
        sa.zip(sb).forEach { (x, y) ->
            assertEquals("$label width", x.first, y.first, 0.5f)
            assertEquals("$label height", x.second, y.second, 0.5f)
        }
        assertEquals("$label texts", Fixtures.pageTexts(a), Fixtures.pageTexts(b))
        for (i in 0 until Fixtures.pageCount(a)) {
            val qa = quadrantMeans(Fixtures.render(a, i, 200))
            val qb = quadrantMeans(Fixtures.render(b, i, 200))
            for (q in 0 until 4) for (k in 0 until 3) {
                assertTrue(
                    "$label page $i quadrant $q channel $k: ${qa[q][k]} vs ${qb[q][k]}",
                    abs(qa[q][k] - qb[q][k]) < 12.0,
                )
            }
        }
    }

    /** Mean R, G, B of the four quadrants (TL, TR, BL, BR); catches channel swaps, flips and inversion. */
    private fun quadrantMeans(r: Fixtures.RenderedPage): List<DoubleArray> {
        val w = r.width
        val h = r.height
        fun mean(x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray {
            val acc = DoubleArray(3)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = r.at(x, y)
                acc[0] += (c shr 16 and 0xff).toDouble()
                acc[1] += (c shr 8 and 0xff).toDouble()
                acc[2] += (c and 0xff).toDouble()
            }
            val n = ((x1 - x0) * (y1 - y0)).toDouble()
            return DoubleArray(3) { acc[it] / n }
        }
        return listOf(mean(0, 0, w / 2, h / 2), mean(w / 2, 0, w, h / 2), mean(0, h / 2, w / 2, h), mean(w / 2, h / 2, w, h))
    }

    companion object {
        private lateinit var dir: File
        private lateinit var photo: File
        private lateinit var mixed: File
        private lateinit var photoBytes: ByteArray
        private lateinit var mixedBytes: ByteArray
        private val photoOut = LinkedHashMap<CompressionLevel, File>()
        private val photoResults = LinkedHashMap<CompressionLevel, PdfResult>()
        private val mixedOut = LinkedHashMap<CompressionLevel, File>()
        private var photoProgress: List<Pair<Int, Int>> = emptyList()

        @BeforeClass
        @JvmStatic
        fun generate() {
            dir = Files.createTempDirectory("compress-op-test").toFile()
            photo = Fixtures.photoPdf(dir, "photo.pdf")
            mixed = Fixtures.mixedImagesPdf(dir, "mixed.pdf")
            photoBytes = photo.readBytes()
            mixedBytes = mixed.readBytes()
            val engine = MuPdfEngine()
            for (level in CompressionLevel.entries) {
                val job = RecordingJob()
                val out = File(dir, "photo-$level.pdf")
                val r = engine.compress(photo, level, out, job)
                assertTrue("photo $level: $r", r is PdfResult.Ok)
                photoOut[level] = out
                photoResults[level] = r
                if (level == CompressionLevel.MEDIUM) photoProgress = job.calls.toList()

                val mOut = File(dir, "mixed-$level.pdf")
                val mr = engine.compress(mixed, level, mOut)
                assertTrue("mixed $level: $mr", mr is PdfResult.Ok)
                mixedOut[level] = mOut
            }
            assertNotNull(photoProgress)
            println("compress sizes: photo ${photo.length()} -> " + photoOut.map { "${it.key}=${it.value.length()}" })
            println("compress sizes: mixed ${mixed.length()} -> " + mixedOut.map { "${it.key}=${it.value.length()}" })
            println("compress mixed images after HIGH: " + Fixtures.imageInfos(mixedOut.getValue(CompressionLevel.HIGH)))
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            dir.deleteRecursively()
        }
    }
}
