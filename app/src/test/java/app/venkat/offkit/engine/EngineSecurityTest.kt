package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Plain JUnit with real MuPDF: regression tests for the engine security audit (SA-1 … SA-6, SA-9).
 *
 * Safe by construction: every bomb below is rejected from its header BEFORE any decode, so each
 * call returns in milliseconds and allocates nothing large. If the gate ever regressed these
 * inputs would exhaust memory, so they are only ever generated here (never stored) and each call
 * is also asserted to take < 2 s. (One exception: [png19Mp_passesTheGate] decodes a 19 MP PNG,
 * about 60 MB, to show the gate lets it through.)
 */
class EngineSecurityTest {
    private val engine = MuPdfEngine()

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    private fun out(name: String) = File(dir, name).also { it.delete() }

    private inline fun <T> fast(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms", ms < 2000)
        return r
    }

    /** The four bomb shapes of the audit, as generated equivalents. */
    private fun bombs(): Map<String, File> = mapOf(
        // SA-2: progressive 65500×65500 in < 2 KB (case-jpeg-kill).
        "jpeg-kill" to write("bomb-kill.jpg", progressiveJpegDeclaring(65500, 65500)),
        // SA-1: progressive 65500×10955 (case-jpeg-0).
        "jpeg-0" to write("bomb-0.jpg", progressiveJpegDeclaring(65500, 10955)),
        // SA-3: PNG 17×8 323 081 RGB interlaced (case-png-2).
        "png-tall" to write("bomb-tall.png", Fixtures.pngDeclaring(17, 8_323_081, interlace = 1)),
        // SA-3: PNG 2 147 483 647×1 1-bit gray (case-png-1).
        "png-wide" to write("bomb-wide.png", Fixtures.pngDeclaring(2_147_483_647, 1, bitDepth = 1, colorType = 0)),
    )

    @Test
    fun bombJpegIsSmall() {
        assertTrue(progressiveJpegDeclaring(65500, 65500).size < 2048)
    }

    @Test
    fun bombs_rejectedBeforeDecode_everywhere() {
        val base = Fixtures.textPdf(dir, "base.pdf", listOf("Base"))
        for ((name, file) in bombs()) {
            val tooLarge = setOf<PdfError>(PdfError.ImageTooLarge)
            val est = fast("$name estimate") { engine.estimateImage(file) }
            assertTrue("$name estimate: $est", est is PdfEstimate.Err && est.error in tooLarge)
            for (level in listOf<CompressionLevel?>(null) + CompressionLevel.entries) {
                val o = out("bomb-$name-$level.pdf")
                val r = fast("$name imagesToPdf $level") { engine.imagesToPdf(listOf(file), PageSize.A4, o, compression = level) }
                assertEquals("$name imagesToPdf $level", PdfResult.Err(PdfError.ImageTooLarge), r)
                assertFalse(o.exists())
            }
            val o = out("bomb-$name-append.pdf")
            val r = fast("$name append") { engine.appendImages(base, listOf(file), o) }
            assertEquals("$name append", PdfResult.Err(PdfError.ImageTooLarge), r)
            assertFalse(o.exists())
        }
    }

    @Test
    fun invalidJpegHeaders_areUnsupported() {
        val good = progressiveJpegDeclaring(16, 16)
        val sof = Fixtures.indexOf(good, byteArrayOf(0xFF.toByte(), 0xC2.toByte()))
        val sofLen = ((good[sof + 2].toInt() and 0xFF) shl 8) or (good[sof + 3].toInt() and 0xFF)
        val segment = good.copyOfRange(sof, sof + 2 + sofLen)
        val cases = mapOf(
            "zero-width" to Fixtures.patchSof(good, 0xC2, 0, 16),
            "zero-height" to Fixtures.patchSof(good, 0xC2, 16, 0),
            "two-components" to good.copyOf().also { it[sof + 9] = 2 },
            "no-sof" to good.copyOfRange(0, sof) + good.copyOfRange(sof + segment.size, good.size),
            "two-sofs" to good.copyOfRange(0, sof) + segment + segment + good.copyOfRange(sof + segment.size, good.size),
        )
        for ((name, bytes) in cases) {
            val f = write("bad-$name.jpg", bytes)
            val est = fast(name) { engine.estimateImage(f) }
            assertEquals(name, PdfEstimate.Err(PdfError.UnsupportedImage), est)
            val o = out("bad-$name.pdf")
            assertEquals(name, PdfResult.Err(PdfError.UnsupportedImage), engine.imagesToPdf(listOf(f), PageSize.A4, o))
            assertFalse(o.exists())
        }
    }

    @Test
    fun pngWithoutIhdrFirst_orZeroSize_isUnsupported() {
        val zero = write("zero.png", Fixtures.pngDeclaring(0, 10))
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(zero))
        val png = Fixtures.pngDeclaring(4, 4)
        val noIhdr = png.copyOfRange(0, 8) + png.copyOfRange(8 + 25, png.size) // drop the IHDR chunk
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(write("noihdr.png", noIhdr)))
    }

    @Test
    fun corruptPngData_isUnsupportedImage_notIo() {
        // IDAT inflates to far more data than a 2×2 RGB image holds, or to too little for 64×64.
        for ((name, png) in mapOf("long" to Fixtures.pngDeclaring(2, 2), "short" to Fixtures.pngDeclaring(64, 64))) {
            val f = write("corrupt-$name.png", png)
            val est = engine.estimateImage(f)
            assertTrue("$name: $est", est is PdfEstimate.Ok || est == PdfEstimate.Err(PdfError.UnsupportedImage))
            val o = out("corrupt-$name.pdf")
            val r = engine.imagesToPdf(listOf(f), PageSize.A4, o, compression = CompressionLevel.HIGH)
            assertTrue("$name: $r", r is PdfResult.Ok || r == PdfResult.Err(PdfError.UnsupportedImage))
        }
    }

    @Test
    fun limits_jpegVerdicts() {
        fun frame(marker: Int, w: Int, h: Int, nc: Int = 3) = ImagePages.JpegFrame(marker, w, h, nc)
        val ok = ImageLimits.Verdict.OK
        val big = ImageLimits.Verdict.TOO_LARGE
        val bad = ImageLimits.Verdict.UNSUPPORTED
        // Single-scan baseline / extended sequential: up to 250 MP.
        assertEquals(ok, ImageLimits.jpeg(frame(0xC0, 15000, 16000), 1, 3))
        assertEquals(ok, ImageLimits.jpeg(frame(0xC1, 15000, 16000), 1, 3))
        assertEquals(big, ImageLimits.jpeg(frame(0xC0, 16000, 16000), 1, 3))
        // Baseline whose first scan lacks components = multi-scan: buffered, 50 MP.
        assertEquals(big, ImageLimits.jpeg(frame(0xC0, 10000, 6000), 1, 1))
        assertEquals(ok, ImageLimits.jpeg(frame(0xC0, 10000, 5000), 1, 1))
        // Progressive, lossless, hierarchical, arithmetic: buffered, 50 MP.
        for (m in listOf(0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)) {
            assertEquals(ok, ImageLimits.jpeg(frame(m, 10000, 5000), 1, 3))
            assertEquals(big, ImageLimits.jpeg(frame(m, 10000, 5001), 1, 3))
        }
        assertEquals(big, ImageLimits.jpeg(frame(0xC0, 65501, 10), 1, 3))
        assertEquals(bad, ImageLimits.jpeg(frame(0xC0, 0, 10), 1, 3))
        assertEquals(bad, ImageLimits.jpeg(frame(0xC0, 10, 10, nc = 2), 1, 2))
        assertEquals(bad, ImageLimits.jpeg(frame(0xC0, 10, 10), 2, 3))
        assertEquals(bad, ImageLimits.jpeg(null, 0, -1))
    }

    @Test
    fun limits_pngVerdicts() {
        val ok = ImageLimits.Verdict.OK
        val big = ImageLimits.Verdict.TOO_LARGE
        val bad = ImageLimits.Verdict.UNSUPPORTED
        fun png(w: Long, h: Long, depth: Int, type: Int) = ImageLimits.png(w, h, depth, type)
        // 8-bit RGB and palette (expanded to RGB): 20 MP.
        for (type in listOf(2, 3)) {
            assertEquals(ok, png(5000, 4000, 8, type))
            assertEquals(big, png(5000, 4001, 8, type))
        }
        // Gray / 8-bit gray+alpha: still 20 MP by pixels, however few bytes they need.
        assertEquals(ok, png(5000, 4000, 16, 0))
        assertEquals(big, png(5000, 4001, 1, 0))
        assertEquals(ok, png(5000, 4000, 8, 4))
        assertEquals(big, png(5000, 4001, 8, 4))
        // 16-bit gray+alpha: 4 B/px, 15 MP.
        assertEquals(ok, png(5000, 3000, 16, 4))
        assertEquals(big, png(5000, 3001, 16, 4))
        // Byte budget (60 MB of samples): 8-bit RGBA 15 MP, 16-bit RGB 10 MP, 16-bit RGBA 7.5 MP.
        assertEquals(ok, png(5000, 3000, 8, 6))
        assertEquals(big, png(5000, 3001, 8, 6))
        assertEquals(ok, png(5000, 2000, 16, 2))
        assertEquals(big, png(5000, 2001, 16, 2))
        assertEquals(ok, png(3000, 2500, 16, 6))
        assertEquals(big, png(3000, 2501, 16, 6))
        // A tRNS chunk adds an alpha channel: 8-bit RGB / palette 15 MP, 16-bit gray 15 MP.
        for (type in listOf(2, 3)) {
            assertEquals(ok, ImageLimits.png(5000, 3000, 8, type, transparency = true))
            assertEquals(big, ImageLimits.png(5000, 3001, 8, type, transparency = true))
        }
        assertEquals(ok, ImageLimits.png(5000, 3000, 16, 0, transparency = true))
        assertEquals(big, ImageLimits.png(5000, 3001, 16, 0, transparency = true))
        // Edges, zero size, out-of-range values, invalid colour type / bit depth.
        assertEquals(big, png(65501, 1, 8, 2))
        assertEquals(bad, png(0, 10, 8, 2))
        assertEquals(bad, png(0xFFFFFFFFL, 1, 8, 2))
        for ((depth, type) in listOf(4 to 2, 16 to 3, 1 to 6, 8 to 1, 8 to 5, 8 to 7, -1 to -1)) {
            assertEquals("$depth/$type", bad, png(10, 10, depth, type))
        }
    }

    @Test
    fun png21Mp_isTooLarge_fast() {
        val f = write("21mp.png", Fixtures.pngDeclaring(7000, 3000))
        assertEquals(PdfEstimate.Err(PdfError.ImageTooLarge), fast("21 MP estimate") { engine.estimateImage(f) })
        for (level in listOf<CompressionLevel?>(null, CompressionLevel.HIGH)) {
            val o = out("21mp-$level.pdf")
            val r = fast("21 MP imagesToPdf $level") { engine.imagesToPdf(listOf(f), PageSize.A4, o, compression = level) }
            assertEquals(PdfResult.Err(PdfError.ImageTooLarge), r)
            assertFalse(o.exists())
        }
        // 16-bit RGBA over the byte budget (8 MP × 8 B), though well under 20 MP.
        val deep = write("8mp-rgba16.png", Fixtures.pngDeclaring(4000, 2000, bitDepth = 16, colorType = 6))
        assertEquals(PdfEstimate.Err(PdfError.ImageTooLarge), fast("16-bit RGBA estimate") { engine.estimateImage(deep) })
        // 8-bit RGB with a tRNS chunk (decoded with alpha) over the byte budget at 18 MP.
        val ihdrEnd = 8 + 25
        val rgb = Fixtures.pngDeclaring(6000, 3000)
        val trns = rgb.copyOfRange(0, ihdrEnd) + pngChunk("tRNS", ByteArray(6)) + rgb.copyOfRange(ihdrEnd, rgb.size)
        val f2 = write("18mp-trns.png", trns)
        assertEquals(PdfEstimate.Err(PdfError.ImageTooLarge), fast("tRNS estimate") { engine.estimateImage(f2) })
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value
        return java.nio.ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc.toInt()).array()
    }

    @Test
    fun png19Mp_passesTheGate() {
        // Tiny IDAT: the gate lets it through; the decode may then fail, but never as too large.
        val f = write("19mp.png", Fixtures.pngDeclaring(5000, 3800))
        val est = engine.estimateImage(f)
        assertTrue("$est", est is PdfEstimate.Ok || est == PdfEstimate.Err(PdfError.UnsupportedImage))
    }

    /* ------------------------------------------------------------------ compress (SA-6) */

    @Test
    fun compress_dctWhoseSofLiesAboutItsSize_isSkipped() {
        val lie = Fixtures.rawImagePdf(dir, "dct-lie.pdf", 8, 8, "DCTDecode", progressiveJpegDeclaring(65500, 65500))
        val bigProgressive = Fixtures.rawImagePdf(dir, "dct-big.pdf", 65500, 1520, "DCTDecode", progressiveJpegDeclaring(65500, 1520))
        // A 99.6 MP baseline (streamed) DCT whose header matches: over Compress's 40 MP cap.
        val bigBaseline = Fixtures.rawImagePdf(
            dir, "dct-big-baseline.pdf", 65500, 1520, "DCTDecode",
            jpegDeclaring(65500, 1520, progressive = false),
        )
        for (pdf in listOf(lie, bigProgressive, bigBaseline)) {
            for (level in CompressionLevel.entries) {
                val o = out("c-${pdf.name}-$level")
                val r = fast("${pdf.name} $level") { engine.compress(pdf, level, o) }
                assertTrue("${pdf.name} $level: $r", r is PdfResult.Ok)
                assertEquals(1, (r as PdfResult.Ok).pages)
                assertEquals(1, Fixtures.pageCount(o))
                assertEquals(listOf("Raw image"), Fixtures.pageTexts(o).map { it.trim() })
            }
        }
    }

    /* ------------------------------------------------------------------ error mapping (SA-4) */

    @Test
    fun classifyFailure_mapsAuditMessages() {
        assertEquals(PdfError.OutOfMemory, classifyFailure(RuntimeException("java.lang.OutOfMemoryError: Java heap space")))
        assertEquals(PdfError.OutOfMemory, classifyFailure(RuntimeException("malloc (1073741824 bytes) failed")))
        assertEquals(PdfError.OutOfMemory, classifyFailure(OutOfMemoryError()))
        for (m in listOf("malformed page tree", "cycle in page tree", "cannot find page 3 in page tree", "source object number out of range")) {
            assertEquals(m, PdfError.Corrupt, classifyFailure(RuntimeException(m)))
        }
        val f = File(dir, "x.png")
        assertEquals(PdfError.UnsupportedImage, classifyFailure(UnsupportedImageFileException(f, RuntimeException("ran out of output before input"))))
        assertEquals(PdfError.ImageTooLarge, classifyFailure(ImageTooLargeException(f)))
        assertEquals(PdfError.Encrypted, classifyFailure(PdfInputException(PdfError.Encrypted)))
        val io = RuntimeException("cannot write file")
        assertEquals(PdfError.Io(io), classifyFailure(io))
    }

    @Test
    fun guardedRun_mapsEscapedMuPdfFailures() {
        val o = out("guarded.pdf").also { it.writeText("partial") }
        assertEquals(PdfResult.Err(PdfError.Corrupt), guardedRun(o) { throw RuntimeException("cycle in page tree") })
        assertFalse(o.exists())
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), guardedRun(o) { throw RuntimeException("malloc (5 bytes) failed") })
    }

    /* ------------------------------------------------------------------ output validation (SA-5) */

    /** A PDF whose page tree is a cycle (`/Kids [3 0 R 2 0 R]`); MuPDF repairs it when opening. */
    private fun cyclePdf(): File {
        val body = """
            %PDF-1.7
            1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj
            2 0 obj <</Type/Pages/Count 3/Kids[3 0 R 2 0 R]>> endobj
            3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>> endobj
            trailer <</Root 1 0 R/Size 4>>
            %%EOF
        """.trimIndent()
        return write("cycle.pdf", body.toByteArray(Charsets.ISO_8859_1))
    }

    /** `Fixtures.linkPdf` re-saved with a plain xref table whose entry 1 is `00000F0042` (audit recipe). */
    private fun xrefDamagedPdf(): File {
        val link = Fixtures.linkPdf(dir, "link-src.pdf", "https://example.org/")
        val plain = File(dir, "link-plain.pdf")
        val doc = com.artifex.mupdf.fitz.Document.openDocument(link.absolutePath) as com.artifex.mupdf.fitz.PDFDocument
        try { doc.save(plain.absolutePath, "") } finally { doc.destroy() }
        val text = String(plain.readBytes(), Charsets.ISO_8859_1)
        val xref = text.lastIndexOf("xref\n0 ")
        assertTrue("plain xref table expected", xref > 0)
        val firstEntry = text.indexOf('\n', text.indexOf('\n', xref) + 1) + 1 // line after "0 N"
        val entry1 = firstEntry + 20
        val damaged = text.substring(0, firstEntry) + "0000000008 65535 f \n" + "00000F0042 00000 n \n" +
            text.substring(entry1 + 20)
        return write("xref-damaged.pdf", damaged.toByteArray(Charsets.ISO_8859_1))
    }

    private fun assertOkIsReadable(what: String, r: PdfResult, o: File) {
        if (r is PdfResult.Ok) {
            assertEquals("$what: page count", r.pages, Fixtures.pageCount(o))
            assertEquals("$what: every page loads", null, PdfInputs.checkPages(o, r.pages, JobControl.None))
        } else {
            assertFalse("$what: no output on Err", o.exists())
        }
    }

    @Test
    fun damagedInputs_neverOkWithUnreadableOutput() {
        val img = Fixtures.jpeg(dir, "add.jpg", 40, 30)
        for (pdf in listOf(cyclePdf(), xrefDamagedPdf())) {
            for (level in CompressionLevel.entries) {
                val o = out("v-${pdf.name}-$level.pdf")
                assertOkIsReadable("compress ${pdf.name} $level", engine.compress(pdf, level, o), o)
            }
            val a = out("v-${pdf.name}-append.pdf")
            assertOkIsReadable("append ${pdf.name}", engine.appendImages(pdf, listOf(img), a), a)
            val m = out("v-${pdf.name}-merge.pdf")
            assertOkIsReadable("merge ${pdf.name}", engine.merge(listOf(pdf), m), m)
        }
    }

    @Test
    fun cyclePdf_compressIsNotOk() {
        val o = out("cycle-out.pdf")
        val r = engine.compress(cyclePdf(), CompressionLevel.HIGH, o)
        assertTrue("$r", r is PdfResult.Err)
        assertFalse(o.exists())
    }

    @Test
    fun validateOutput_deletesUnreadableFile() {
        val bad = write("bad-out.pdf", cyclePdf().readBytes())
        assertEquals(PdfResult.Err(PdfError.Corrupt), PdfInputs.validateOutput(bad, 3, JobControl.None))
        assertFalse(bad.exists())
        val good = Fixtures.textPdf(dir, "good-out.pdf", listOf("a", "b"))
        assertEquals(null, PdfInputs.validateOutput(good, 2, JobControl.None))
        assertEquals(PdfResult.Err(PdfError.Corrupt), PdfInputs.validateOutput(good, 3, JobControl.None))
        assertFalse(good.exists())
    }

    /* ------------------------------------------------------------------ kept-segment hygiene (SA-7) */

    private val secret = "ZQXSECRET".toByteArray(Charsets.ISO_8859_1)

    /** Segments of [jpeg] before SOS as (marker, start, end) and the SOS offset. */
    private fun withoutApp(jpeg: ByteArray, marker: Int): ByteArray {
        val (segs, _) = Fixtures.jpegHeaderSegments(jpeg)
        val s = segs.firstOrNull { it.first == marker } ?: return jpeg
        return jpeg.copyOfRange(0, s.second) + jpeg.copyOfRange(s.third, jpeg.size)
    }

    private fun sosToEnd(jpeg: ByteArray): ByteArray = jpeg.copyOfRange(Fixtures.jpegHeaderSegments(jpeg).second, jpeg.size)

    /** SOI + [segments] + the rest of [base] after its SOI (its own APP0/APP14 removed). */
    private fun assemble(base: ByteArray, vararg segments: ByteArray): ByteArray {
        val rest = withoutApp(withoutApp(base, 0xE0), 0xEE)
        return rest.copyOfRange(0, 2) + segments.fold(ByteArray(0)) { a, b -> a + b } + rest.copyOfRange(2, rest.size)
    }

    private fun jfif(thumbW: Int, thumbH: Int, extra: ByteArray) = Fixtures.jpegSegment(
        0xE0,
        "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 2, 1, 0, 72, 0, 72, thumbW.toByte(), thumbH.toByte()) +
            ByteArray(thumbW * thumbH * 3) { 0x5A } + extra,
    )

    private fun adobe(transform: Int, extra: ByteArray) = Fixtures.jpegSegment(
        0xEE, "Adobe".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 100, 0, 0, 0, 0, transform.toByte()) + extra,
    )

    @Test
    fun keptJfifAndAdobeSegments_areRewrittenBare_pixelsUnchanged() {
        val base = Fixtures.jpeg(dir, "sa7-base.jpg", 320, 240).readBytes()
        val planted = write("sa7.jpg", assemble(base, jfif(2, 1, secret), adobe(1, secret)))
        val clean = write("sa7-clean.jpg", assemble(base, jfif(0, 0, ByteArray(0)), adobe(1, ByteArray(0))))
        assertTrue(Fixtures.indexOf(planted.readBytes(), secret) >= 0)

        val orig = out("sa7-orig.pdf")
        assertTrue(engine.imagesToPdf(listOf(planted), PageSize.FIT_IMAGE, orig) is PdfResult.Ok)
        val appended = out("sa7-append.pdf")
        assertTrue(engine.appendImages(Fixtures.textPdf(dir, "sa7-doc.pdf", listOf("x")), listOf(planted), appended) is PdfResult.Ok)
        val cleanPdf = out("sa7-cleanref.pdf")
        assertTrue(engine.imagesToPdf(listOf(clean), PageSize.FIT_IMAGE, cleanPdf) is PdfResult.Ok)

        for (pdf in listOf(orig, appended)) {
            assertFalse("${pdf.name} carries planted bytes", Fixtures.pdfContains(pdf, secret))
            val embedded = Fixtures.rawImageStreams(pdf).single()
            assertTrue(sosToEnd(embedded).contentEquals(sosToEnd(planted.readBytes()))) // pixels bit-identical
            val (segs, _) = Fixtures.jpegHeaderSegments(embedded)
            val app0 = segs.single { it.first == 0xE0 }
            assertEquals(16 + 2, app0.third - app0.second) // FF E0 + 16-byte segment
            val app14 = segs.single { it.first == 0xEE }
            assertEquals(14 + 2, app14.third - app14.second)
        }
        // Same pixels as the image with bare segments written by hand.
        assertTrue(Fixtures.rawImageStreams(orig).single().contentEquals(Fixtures.rawImageStreams(cleanPdf).single()))
        assertEquals(Fixtures.render(cleanPdf, 0).rgb.toList(), Fixtures.render(orig, 0).rgb.toList())
    }

    @Test
    fun cmykAdobeTransformStillHonoured_afterTruncation() {
        val pix = com.artifex.mupdf.fitz.Pixmap(com.artifex.mupdf.fitz.ColorSpace.DeviceCMYK, 64, 48, false)
        val cmyk = try {
            pix.clear(0)
            val buf = pix.asJPEG(90, false)
            try { buf.asByteArray() } finally { buf.destroy() }
        } finally {
            pix.destroy()
        }
        val (segs, _) = Fixtures.jpegHeaderSegments(cmyk)
        val a = segs.firstOrNull { it.first == 0xEE }
        val withAdobe = if (a == null) assemble(cmyk, adobe(0, ByteArray(0))) else cmyk
        val seg = Fixtures.jpegHeaderSegments(withAdobe).first.single { it.first == 0xEE }
        val payload = withAdobe.copyOfRange(seg.second + 4, seg.third)
        val tailed = withAdobe.copyOfRange(0, seg.second) + Fixtures.jpegSegment(0xEE, payload + secret) +
            withAdobe.copyOfRange(seg.third, withAdobe.size)
        val before = out("cmyk-before.pdf")
        val after = out("cmyk-after.pdf")
        assertTrue(engine.imagesToPdf(listOf(write("cmyk.jpg", withAdobe)), PageSize.FIT_IMAGE, before) is PdfResult.Ok)
        assertTrue(engine.imagesToPdf(listOf(write("cmyk-tail.jpg", tailed)), PageSize.FIT_IMAGE, after) is PdfResult.Ok)
        assertFalse(Fixtures.pdfContains(after, secret))
        assertEquals(Fixtures.render(before, 0).rgb.toList(), Fixtures.render(after, 0).rgb.toList())
    }

    /* ------------------------------------------------------------------ job limits (SA-9) */

    @Test
    fun tooManyImages_isOutOfMemory_beforeTouchingThem() {
        val missing = List(ImageLimits.MAX_IMAGES_PER_JOB + 1) { File(dir, "missing-$it.jpg") }
        val o = out("many.pdf")
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.imagesToPdf(missing, PageSize.A4, o))
        val base = Fixtures.textPdf(dir, "base2.pdf", listOf("Base"))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.appendImages(base, missing, o))
        assertFalse(o.exists())
    }

    @Test
    fun inputPdfOver1GiB_isOutOfMemory_withoutReadingIt() {
        val huge = File(dir, "huge.pdf")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(ImageLimits.MAX_INPUT_PDF_BYTES + 1) } // sparse
        try {
            val o = out("huge-out.pdf")
            assertEquals(PdfResult.Err(PdfError.OutOfMemory), fast("huge") { engine.compress(huge, CompressionLevel.LOW, o) })
            assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.merge(listOf(huge), o))
            assertFalse(o.exists())
        } finally {
            huge.delete()
        }
    }

    companion object {
        private lateinit var dir: File

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("engine-security").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }

        /**
         * A small (< 2 KB) real progressive JPEG (ImageIO, 16×16) whose SOF2 is patched to declare
         * [width]×[height]: the shape of the audit's SA-2 bomb. Never decode it.
         */
        fun progressiveJpegDeclaring(width: Int, height: Int): ByteArray = jpegDeclaring(width, height, progressive = true)

        /** As [progressiveJpegDeclaring]; with [progressive] false a baseline (SOF0) JPEG. */
        fun jpegDeclaring(width: Int, height: Int, progressive: Boolean): ByteArray {
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val bytes = ByteArrayOutputStream()
            try {
                ImageIO.createImageOutputStream(bytes).use { ios ->
                    writer.output = ios
                    val p = writer.defaultWriteParam
                    p.progressiveMode = if (progressive) ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
                    writer.write(null, IIOImage(img, null, null), p)
                }
            } finally {
                writer.dispose()
            }
            return Fixtures.patchSof(bytes.toByteArray(), if (progressive) 0xC2 else 0xC0, width, height)
        }
    }
}
