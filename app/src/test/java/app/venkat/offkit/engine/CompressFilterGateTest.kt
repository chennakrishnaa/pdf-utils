package app.venkat.offkit.engine

import app.venkat.offkit.engine.ops.ImageFilters
import app.venkat.offkit.engine.ops.ImageFilters.Chain
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.Deflater

/**
 * SR-1 / SR-5: Compress decodes an image only when its filter chain is positively allowed
 * ([ImageFilters]). Plain JUnit with real MuPDF.
 *
 * Safe by construction: every hostile PDF below carries a 65500×65500 progressive JPEG under a
 * spelling MuPDF decodes as DCT (or a predictor that would allocate ~270 MB per row buffer); the
 * gate skips each of them without decoding, so every call returns in milliseconds. Each call is
 * asserted to take < 2 s. These exact shapes were first run in a separate, memory-capped JVM
 * (peak RSS 70-74 MB each) before being added here.
 */
class CompressFilterGateTest {
    private val engine = MuPdfEngine()

    /* ------------------------------------------------------------------ pure filter table */

    @Test
    fun normaliser_mapsEveryNameMuPdfAccepts() {
        // pdf-stream.c build_filter / build_compression_params, MuPDF 1.28.5.
        val expected = mapOf(
            "ASCIIHexDecode" to "ASCIIHexDecode", "AHx" to "ASCIIHexDecode",
            "ASCII85Decode" to "ASCII85Decode", "A85" to "ASCII85Decode",
            "LZWDecode" to "LZWDecode", "LZW" to "LZWDecode",
            "FlateDecode" to "FlateDecode", "Fl" to "FlateDecode",
            "RunLengthDecode" to "RunLengthDecode", "RL" to "RunLengthDecode",
            "CCITTFaxDecode" to "CCITTFaxDecode", "CCF" to "CCITTFaxDecode",
            "DCTDecode" to "DCTDecode", "DCT" to "DCTDecode",
            "BrotliDecode" to "BrotliDecode", "Br" to "BrotliDecode",
            "JBIG2Decode" to "JBIG2Decode", "JPXDecode" to "JPXDecode", "Crypt" to "Crypt",
        )
        assertEquals(expected, ImageFilters.CANONICAL)
        val allowed = mapOf("FlateDecode" to Chain.FLATE, "DCTDecode" to Chain.DCT)
        for ((name, canonical) in expected) {
            assertEquals(name, allowed[canonical] ?: Chain.SKIP, ImageFilters.decide(true, listOf(name)))
        }
    }

    @Test
    fun decide_isDefaultDeny() {
        assertEquals(Chain.RAW, ImageFilters.decide(false, null))
        assertEquals(Chain.RAW, ImageFilters.decide(true, emptyList()))
        assertEquals(Chain.SKIP, ImageFilters.decide(true, null)) // a number, a dict, ...
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf(null)))
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf("Bogus")))
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf("dct"))) // names are case-sensitive
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf("FlateDecode", "DCTDecode")))
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf("AHx", "DCT")))
        assertEquals(Chain.SKIP, ImageFilters.decide(true, listOf("DCTDecode", "DCTDecode")))
        assertEquals(Chain.DCT, ImageFilters.decide(true, listOf("DCT")))
        assertEquals(Chain.FLATE, ImageFilters.decide(true, listOf("Fl")))
        for (k in listOf("F", "DP", "W", "H", "BPC", "CS", "IM", "D", "I", "FFilter", "FDecodeParms")) {
            assertTrue(k, k in ImageFilters.SKIP_KEYS)
        }
    }

    @Test
    fun flatePredictor_mustDescribeTheImage() {
        assertTrue(ImageFilters.flateParamsOk(null, null, null, null, 100, 3))
        assertTrue(ImageFilters.flateParamsOk(1, 4_194_000, 32, 16, 100, 3)) // no predictor: unused
        assertTrue(ImageFilters.flateParamsOk(15, 100, 3, 8, 100, 3))
        assertTrue(ImageFilters.flateParamsOk(12, 100, 1, null, 100, 1))
        assertFalse(ImageFilters.flateParamsOk(15, 4_194_000, 32, 16, 100, 3)) // SR-5 shape
        assertFalse(ImageFilters.flateParamsOk(15, 101, 3, 8, 100, 3))
        assertFalse(ImageFilters.flateParamsOk(15, 100, 4, 8, 100, 3))
        assertFalse(ImageFilters.flateParamsOk(15, 100, 3, 16, 100, 3))
        assertFalse(ImageFilters.flateParamsOk(15, null, 3, 8, 100, 3)) // Columns defaults to 1
        assertFalse(ImageFilters.flateParamsOk(5, 100, 3, 8, 100, 3)) // not a valid predictor
    }

    /* ------------------------------------------------------------------ compress on hostile PDFs */

    @Test
    fun compress_skipsEveryDctSpellingOfTheBomb_untouched() {
        val bomb = EngineSecurityTest.progressiveJpegDeclaring(65500, 65500)
        val img = "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /BitsPerComponent 8 /ColorSpace /DeviceRGB %s >>"
        val cases = mapOf(
            "dct" to (img.format("/Filter /DCT") to bomb),
            "dct-array" to (img.format("/Filter [/DCT]") to bomb),
            "flate-dct" to (img.format("/Filter [/FlateDecode /DCTDecode]") to deflate(bomb)),
            "ahx-dct" to (img.format("/Filter [/AHx /DCT]") to (hex(bomb) + ">").toByteArray()),
            "unknown" to (img.format("/Filter /Bogus") to bomb),
            "f-key" to (img.format("/F /DCT") to bomb),
            "dp-key" to (img.format("/Filter /DCTDecode /DP << /ColorTransform 1 >>") to bomb),
            "non-name-element" to (img.format("/Filter [/DCTDecode 7]") to bomb),
        )
        for ((name, case) in cases) {
            val pdf = write("$name.pdf", imagePage(case.first, case.second))
            // With only /F (no /Filter), MuPDF's writer treats the stream as unfiltered and deflates
            // it on save; the image is still never decoded or replaced (its dictionary size stays).
            assertSkipped(name, pdf, if (name == "f-key") null else case.second)
        }
        val indirect = write(
            "len-indirect.pdf",
            imagePage(img.format("/Filter /DCT /Length 6 0 R"), bomb, listOf("${bomb.size}" to null)),
        )
        assertSkipped("indirect length", indirect, bomb)
        val form = write(
            "in-form.pdf",
            handPdf(
                listOf(
                    "<< /Type /Catalog /Pages 2 0 R >>" to null,
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>" to null,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Resources << /XObject << /Fm0 5 0 R >> >> /Contents 4 0 R >>" to null,
                    "<< >>" to "/Fm0 Do".toByteArray(),
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 400 300] /Resources << /XObject << /Im0 6 0 R >> >> >>" to
                        "q 400 0 0 300 0 0 cm /Im0 Do Q".toByteArray(),
                    img.format("/Filter /DCT") to bomb,
                ),
            ),
        )
        assertSkipped("form xobject", form, bomb)
    }

    @Test
    fun compress_skipsFlateImageWhosePredictorLies() {
        val data = deflate(ByteArray(100 * 100 * 3))
        val pdf = write(
            "predictor-lie.pdf",
            imagePage(
                "<< /Type /XObject /Subtype /Image /Width 100 /Height 100 /BitsPerComponent 8 /ColorSpace /DeviceRGB " +
                    "/Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 32 /BitsPerComponent 16 /Columns 4194000 >> >>",
                data,
            ),
        )
        assertSkipped("predictor lie", pdf, data)
    }

    @Test
    fun compress_stillRecompressesOrdinaryFlateWithPredictor_andAbbreviatedFlate() {
        val w = 300
        val h = 300
        val rnd = Random(7)
        val rows = ByteArrayOutputStream()
        for (y in 0 until h) {
            rows.write(0) // PNG predictor tag: None
            val row = ByteArray(w * 3).also { rnd.nextBytes(it) }
            rows.write(row)
        }
        val data = deflate(rows.toByteArray())
        for ((name, filter) in listOf("predictor" to "/Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 3 /Columns $w >>", "fl" to "/Filter /Fl")) {
            val bytes = if (name == "fl") deflate(ByteArray(w * h * 3).also { rnd.nextBytes(it) }) else data
            val pdf = write(
                "ok-$name.pdf",
                imagePage("<< /Type /XObject /Subtype /Image /Width $w /Height $h /BitsPerComponent 8 /ColorSpace /DeviceRGB $filter >>", bytes),
            )
            val o = out("ok-$name-out.pdf")
            val r = fast(name) { engine.compress(pdf, CompressionLevel.HIGH, o) }
            assertTrue("$name: $r", r is PdfResult.Ok)
            assertTrue("$name: smaller", (r as PdfResult.Ok).bytes < pdf.length())
            assertEquals(listOf("DCTDecode"), imageFilters(o))
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private fun assertSkipped(what: String, pdf: File, original: ByteArray?) {
        for (level in CompressionLevel.entries) {
            val o = out("${pdf.name}-$level-out.pdf")
            val r = fast("$what $level") { engine.compress(pdf, level, o) }
            assertTrue("$what $level: $r", r is PdfResult.Ok)
            assertEquals(1, (r as PdfResult.Ok).pages)
            if (original != null) assertArrayEquals("$what $level: image stream untouched", original, imageRawStream(o))
            assertFalse("$what $level: not replaced", "DCTDecode" in imageFilters(o) && original == null)
        }
    }

    private inline fun <T> fast(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms", ms < 2000)
        return r
    }

    /** The raw (undecoded) stream of the only image XObject in [file]. */
    private fun imageRawStream(file: File): ByteArray = withImage(file) { obj -> obj.readRawStream() }

    private fun imageFilters(file: File): List<String> = withImage(file) { obj ->
        val f = obj.get("Filter")
        if (f.isName) listOf(f.asName()) else (0 until f.size()).map { f.get(it).asName() }
    }

    private fun <T> withImage(file: File, block: (com.artifex.mupdf.fitz.PDFObject) -> T): T {
        val doc = Document.openDocument(file.absolutePath) as PDFDocument
        try {
            for (num in 1 until doc.countObjects()) {
                val obj = doc.newIndirect(num, 0)
                try {
                    if (obj.isStream && obj.get("Subtype").let { it.isName && it.asName() == "Image" }) return block(obj)
                } finally {
                    obj.destroy()
                }
            }
            error("no image in ${file.name}")
        } finally {
            doc.destroy()
        }
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private lateinit var dir: File

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("compress-gate").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }

        fun deflate(b: ByteArray): ByteArray {
            val d = Deflater(9)
            d.setInput(b)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** A one-page PDF drawing image object 5 (dictionary [imageDict], data [data]) full page. */
        fun imagePage(imageDict: String, data: ByteArray, extra: List<Pair<String, ByteArray?>> = emptyList()): ByteArray =
            handPdf(
                listOf(
                    "<< /Type /Catalog /Pages 2 0 R >>" to null,
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>" to null,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>" to null,
                    "<< >>" to "q 400 0 0 300 0 0 cm /Im0 Do Q".toByteArray(),
                    imageDict to data,
                ) + extra,
            )

        /**
         * A PDF written byte by byte: objects numbered from 1 (object 1 is the catalog), each a
         * dictionary/value text plus optional stream data (`/Length` added when missing), with a
         * correct xref table. Lets a test use dictionary keys the binding would normalise.
         */
        fun handPdf(objects: List<Pair<String, ByteArray?>>): ByteArray {
            val out = ByteArrayOutputStream()
            fun put(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            put("%PDF-1.7\n%âãÏÓ\n")
            val offsets = ArrayList<Int>()
            for ((i, o) in objects.withIndex()) {
                offsets.add(out.size())
                val (dict, data) = o
                if (data == null) {
                    put("${i + 1} 0 obj\n$dict\nendobj\n")
                } else {
                    val d = if ("/Length" in dict) dict else dict.dropLast(2) + " /Length ${data.size} >>"
                    put("${i + 1} 0 obj\n$d\nstream\n")
                    out.write(data)
                    put("\nendstream\nendobj\n")
                }
            }
            val xref = out.size()
            put("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            for (off in offsets) put("%010d 00000 n \n".format(off))
            put("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
    }
}
