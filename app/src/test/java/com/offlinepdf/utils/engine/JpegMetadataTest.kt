package com.offlinepdf.utils.engine

import com.offlinepdf.utils.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plain JUnit with real MuPDF: metadata (EXIF/GPS, XMP, IPTC, comments) is not copied into PDFs (N2b). */
class JpegMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gps = Fixtures.PLANTED_GPS.toByteArray(Charsets.ISO_8859_1)
    private val text = Fixtures.PLANTED_TEXT.toByteArray(Charsets.ISO_8859_1)
    private val levels: List<CompressionLevel?> = listOf(null) + CompressionLevel.entries

    private fun out(name: String) = File(tmp.root, name)

    private fun ok(r: PdfResult): PdfResult.Ok {
        assertTrue("expected Ok, got $r", r is PdfResult.Ok)
        return r as PdfResult.Ok
    }

    private fun estimate(f: File): PdfEstimate.Ok {
        val e = MuPdfEngine().estimateImage(f)
        assertTrue("expected Ok, got $e", e is PdfEstimate.Ok)
        return e as PdfEstimate.Ok
    }

    private fun toPdf(image: File, name: String, level: CompressionLevel? = null): File =
        out(name).also { ok(MuPdfEngine().imagesToPdf(listOf(image), PageSize.FIT_IMAGE, it, compression = level)) }

    private fun startsWith(b: ByteArray, from: Int, sig: String) =
        sig.indices.all { from + it < b.size && b[from + it] == sig[it].code.toByte() }

    /** Expected kept segments, written independently of the engine's rule. */
    private fun isKept(jpeg: ByteArray, seg: Triple<Int, Int, Int>): Boolean {
        val (m, start, _) = seg
        val p = start + 4
        return when (m) {
            0xE0 -> startsWith(jpeg, p, "JFIF\u0000")
            0xE2 -> startsWith(jpeg, p, "ICC_PROFILE\u0000")
            0xEE -> startsWith(jpeg, p, "Adobe")
            in 0xE1..0xEF, 0xFE -> false
            else -> true
        }
    }

    /** Source JPEG with the expected metadata segments removed, up to its end marker. */
    private fun expectedStripped(src: ByteArray): ByteArray {
        val (segs, sos) = Fixtures.jpegHeaderSegments(src)
        assertTrue(sos > 0)
        val end = ImagePages.jpegEnd(src.inputStream())!!.toInt()
        val out = java.io.ByteArrayOutputStream()
        out.write(src, 0, 2)
        for (s in segs) if (isKept(src, s)) out.write(src, s.second, s.third - s.second)
        out.write(src, sos, end - sos)
        return out.toByteArray()
    }

    private fun assertUpright(pdf: File, exif: Int, w: Float, h: Float) {
        val size = Fixtures.pageSizes(pdf).single()
        val (ew, eh) = if (exif >= 5) h to w else w to h
        assertEquals("exif $exif width", ew, size.first, 0.01f)
        assertEquals("exif $exif height", eh, size.second, 0.01f)
        val c = Fixtures.renderCornerColors(pdf, 0)
        assertTrue("exif $exif TL", Fixtures.colorNear(c.topLeft, Fixtures.RED))
        assertTrue("exif $exif TR", Fixtures.colorNear(c.topRight, Fixtures.GREEN))
        assertTrue("exif $exif BL", Fixtures.colorNear(c.bottomLeft, Fixtures.BLUE))
        assertTrue("exif $exif BR", Fixtures.colorNear(c.bottomRight, Fixtures.YELLOW))
    }

    private fun assertNoMetadataSegments(embedded: ByteArray) {
        val (segs, sos) = Fixtures.jpegHeaderSegments(embedded)
        assertTrue("no SOS in embedded stream", sos > 0)
        val markers = segs.map { it.first }
        for (m in listOf(0xE1, 0xED, 0xFE, 0xEF)) assertFalse("marker ${m.toString(16)} in $markers", m in markers)
        assertTrue("all kept segments are expected: $markers", segs.all { isKept(embedded, it) })
    }

    @Test
    fun generatedJpegsHaveNothingToStrip() {
        // Existing tests (ImagesToPdfOpTest, AppendImagesOpTest) assert byte-identical embedding of these.
        for (f in listOf(Fixtures.jpeg(tmp.root, "a.jpg"), Fixtures.grayJpeg(tmp.root, "g.jpg"))) {
            val layout = ImagePages.jpegScan(f)!!
            assertTrue("${f.name}: ${layout.metadata}", layout.metadata.isEmpty())
            assertEquals(f.length(), layout.end)
        }
    }

    @Test
    fun fixtureReallyCarriesMetadata() {
        val src = Fixtures.jpegWithMetadata(tmp.root, "m.jpg").readBytes()
        assertTrue(Fixtures.indexOf(src, gps) >= 0)
        assertTrue(Fixtures.indexOf(src, text) >= 0)
        val markers = Fixtures.jpegHeaderSegments(src).first.map { it.first }
        for (m in listOf(0xE1, 0xE2, 0xED, 0xE0, 0xEF, 0xFE)) assertTrue(m in markers)
        // MuPDF reads the orientation from the original
        val img = com.artifex.mupdf.fitz.Image(src)
        try { assertEquals(4, img.orientation) } finally { img.destroy() } // EXIF 6 -> MuPDF code 4
    }

    /** Criteria 1 and 2: no metadata segment and no planted bytes in the PDF; page upright with the right box. */
    @Test
    fun originalQualityStripsMetadataAndKeepsOrientation() {
        for (exif in 1..8) {
            val src = Fixtures.jpegWithMetadata(tmp.root, "m$exif.jpg", 300, 200, exif)
            val pdf = toPdf(src, "m$exif.pdf")
            val embedded = Fixtures.rawImageStreams(pdf).single()
            assertNoMetadataSegments(embedded)
            assertFalse("GPS in PDF (exif $exif)", Fixtures.pdfContains(pdf, gps))
            assertFalse("text in PDF (exif $exif)", Fixtures.pdfContains(pdf, text))
            assertUpright(pdf, exif, 300f, 200f)
        }
    }

    /** Criterion 3: lossless; the embedded stream is exactly the source minus the metadata segments. */
    @Test
    fun strippingIsLossless() {
        val src = Fixtures.jpegWithMetadata(tmp.root, "l.jpg", 640, 480, 3).readBytes()
        val srcFile = File(tmp.root, "l.jpg")
        val embedded = Fixtures.rawImageStreams(toPdf(srcFile, "l.pdf")).single()
        val srcSos = Fixtures.jpegHeaderSegments(src).second
        val embSos = Fixtures.jpegHeaderSegments(embedded).second
        assertArrayEquals(src.copyOfRange(srcSos, src.size), embedded.copyOfRange(embSos, embedded.size))
        assertArrayEquals(expectedStripped(src), embedded)
    }

    /** Criterion 3: ICC profile and Adobe segments are kept; decoding is unchanged. */
    @Test
    fun iccAndAdobeSegmentsAreKept() {
        val src = Fixtures.jpegWithAdobeAndIcc(tmp.root, "ai.jpg")
        val plain = Fixtures.jpeg(tmp.root, "plain.jpg", 320, 240, 1L)
        val pdf = toPdf(src, "ai.pdf")
        val embedded = Fixtures.rawImageStreams(pdf).single()
        val markers = Fixtures.jpegHeaderSegments(embedded).first.map { it.first }
        assertTrue("Adobe kept: $markers", 0xEE in markers)
        assertTrue("ICC kept: $markers", 0xE2 in markers)
        assertFalse("COM dropped: $markers", 0xFE in markers)
        assertArrayEquals(expectedStripped(src.readBytes()), embedded)
        assertFalse(Fixtures.pdfContains(pdf, text))
        val a = Fixtures.render(toPdf(plain, "plain.pdf"), 0)
        val b = Fixtures.render(pdf, 0)
        assertArrayEquals(a.rgb, b.rgb)
    }

    /** Criterion 4: the same holds through Add images. */
    @Test
    fun appendImagesStripsMetadata() {
        val doc = Fixtures.textPdf(tmp.root, "doc.pdf", listOf("First"))
        val src = Fixtures.jpegWithMetadata(tmp.root, "ap.jpg", 300, 200, 8)
        val out = out("ap.pdf")
        ok(MuPdfEngine().appendImages(doc, listOf(src), out))
        assertEquals(2, Fixtures.pageCount(out))
        val embedded = Fixtures.rawImageStreams(out).single()
        assertNoMetadataSegments(embedded)
        assertArrayEquals(expectedStripped(src.readBytes()), embedded)
        assertFalse(Fixtures.pdfContains(out, gps))
        assertFalse(Fixtures.pdfContains(out, text))
        // EXIF 8 displays 200x300: the image is drawn upright (quadrant colours inside its ink box)
        val r = Fixtures.render(out, 1, 400)
        var x0 = r.width; var y0 = r.height; var x1 = -1; var y1 = -1
        for (y in 0 until r.height) for (x in 0 until r.width) {
            if (!Fixtures.colorNear(r.at(x, y), 0xFFFFFF, 15)) {
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
            }
        }
        assertTrue("blank page", x1 > x0 && y1 > y0)
        assertTrue("portrait image", (y1 - y0) > (x1 - x0))
        fun at(fx: Float, fy: Float) = r.at(x0 + ((x1 - x0) * fx).toInt(), y0 + ((y1 - y0) * fy).toInt())
        assertTrue(Fixtures.colorNear(at(0.2f, 0.2f), Fixtures.RED))
        assertTrue(Fixtures.colorNear(at(0.8f, 0.2f), Fixtures.GREEN))
        assertTrue(Fixtures.colorNear(at(0.2f, 0.8f), Fixtures.BLUE))
        assertTrue(Fixtures.colorNear(at(0.8f, 0.8f), Fixtures.YELLOW))
    }

    /** Criterion 5: PNG text and EXIF chunks do not survive (MuPDF decodes and re-encodes PNGs). */
    @Test
    fun pngTextAndExifChunksAreNotEmbedded() {
        val src = Fixtures.pngWithMetadata(tmp.root, "t.png")
        assertTrue(Fixtures.indexOf(src.readBytes(), text) >= 0)
        for (level in levels) {
            val pdf = toPdf(src, "t-$level.pdf", level)
            assertFalse("$level", Fixtures.pdfContains(pdf, text))
        }
    }

    /** Compressed levels (including the keep-original fallback) never carry metadata either. */
    @Test
    fun compressedLevelsCarryNoMetadata() {
        for ((w, h) in listOf(3000 to 2000, 40 to 30)) {
            val src = Fixtures.jpegWithMetadata(tmp.root, "c$w.jpg", w, h, 6, paddingBytes = 2000)
            for (level in CompressionLevel.entries) {
                val pdf = toPdf(src, "c$w-$level.pdf", level)
                assertFalse("$w $level", Fixtures.pdfContains(pdf, gps))
                assertFalse("$w $level", Fixtures.pdfContains(pdf, text))
                assertEquals(Fixtures.pageSizes(toPdf(src, "c$w-orig.pdf")).single(), Fixtures.pageSizes(pdf).single())
            }
        }
    }

    /** Criterion 6: the Original estimate is the stripped size, i.e. what is really embedded. */
    @Test
    fun estimateOfOriginalIsStrippedSize() {
        val src = Fixtures.jpegWithMetadata(tmp.root, "e.jpg", 400, 300, 6, paddingBytes = 30_000)
        val e = estimate(src)
        val pdf = toPdf(src, "e.pdf")
        val raw = Fixtures.rawImageStreams(pdf).single().size.toLong()
        assertEquals(raw, e.original)
        assertTrue("estimate ${e.original} vs file ${src.length()}", e.original < src.length() - 60_000)
        val predicted = PDF_DOCUMENT_OVERHEAD_BYTES + e.original + PDF_PAGE_OVERHEAD_BYTES
        assertEquals(pdf.length().toDouble(), predicted.toDouble(), pdf.length() * 0.05)
        for (level in CompressionLevel.entries) {
            val out = toPdf(src, "e-$level.pdf", level)
            assertEquals("$level", e.byLevel.getValue(level), Fixtures.rawImageStreams(out).single().size.toLong())
        }
    }

    /** Criterion 7: a trailer after a metadata-heavy JPEG is dropped too, and nothing else changes. */
    @Test
    fun metadataPlusTrailer() {
        val src = Fixtures.jpegWithMetadata(tmp.root, "mt.jpg", 300, 200, 6)
        val trailed = Fixtures.withTrailer(src, 70 * 1024)
        val pdf = toPdf(trailed, "mt.pdf")
        assertArrayEquals(Fixtures.rawImageStreams(toPdf(src, "m.pdf")).single(), Fixtures.rawImageStreams(pdf).single())
        assertFalse(Fixtures.pdfContains(pdf, gps))
        assertUpright(pdf, 6, 300f, 200f)
        assertEquals(estimate(src), estimate(trailed))
    }

    private fun seg(marker: Int, payload: String) = Fixtures.jpegSegment(marker, payload.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun walkerClassifiesSegments() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val parts = listOf(
            seg(0xE0, "JFIF\u0000\u0001\u0002\u0000\u0000\u0001\u0000\u0001\u0000\u0000"), // keep
            seg(0xE0, "JFXX\u0000\u0013"), // drop
            seg(0xE1, "Exif\u0000\u0000MM"), // drop
            seg(0xE2, "ICC_PROFILE\u0000\u0001\u0001xyz"), // keep
            seg(0xE2, "MPF\u0000"), // drop
            seg(0xE2, "ICC"), // drop: too short to be a profile
            seg(0xDB, "q"), // keep
            seg(0xEE, "Adobe\u0000d\u0000\u0000\u0000\u0000\u0001"), // keep
            seg(0xEE, "Other"), // drop
            seg(0xED, "Photoshop 3.0\u0000"), // drop
            seg(0xFE, ""), // drop (empty payload)
            seg(0xEF, "x"), // drop
            seg(0xC0, "sof"), // keep
            seg(0xDA, "sos"), // keep, scan starts after
        )
        val keep = listOf(true, false, false, true, false, false, true, true, false, false, false, false, true, true)
        val tail = byteArrayOf(1, 2, 3) + seg(0xE1, "Exif after SOS") + byteArrayOf(4, 0xFF.toByte(), 0xD9.toByte())
        var all = soi
        val spans = ArrayList<ImagePages.Span>()
        for ((i, p) in parts.withIndex()) {
            if (!keep[i]) spans.add(ImagePages.Span(all.size.toLong(), (all.size + p.size).toLong()))
            all += p
        }
        val scanStart = all.size.toLong()
        all += tail
        val layout = ImagePages.jpegScan(all.inputStream())!!
        assertEquals(all.size.toLong(), layout.end)
        assertEquals(scanStart, layout.scanStart)
        assertEquals(spans, layout.metadata)
        // a segment cut short inside its first payload bytes is a truncated file
        assertEquals(null, ImagePages.jpegScan((soi + byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, 20) + "ICC".toByteArray()).inputStream()))
    }
}
