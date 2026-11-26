package app.venkat.offkit.engine

import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.HostilePdf.Companion.repeated
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SR3-1: the raw-file object-stream pre-scan ([ObjStmScan]) run by [PdfInputs.open]. Most cases use
 * small limits; the one at the real 16 MiB limit uses `null` elements (8 B each when parsed, so the
 * unfixed parse costs ~30 MB, harmless).
 */
class ObjStmScanTest {
    private val engine = MuPdfEngine()

    @Test
    fun objStmOverTheRealLimit_isTooLarge_inOpenAndCompress() {
        // 3.5 M x "null " = 17.5 MB decoded > 16 MiB; ~20 KB on disk. Catalog etc. in the same stream.
        val pdf = write("bignull.pdf", inCatalogStream(repeated("null ", 3_500_000)))
        assertTooLarge { PdfInputs.open(pdf).destroy() }
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(pdf, CompressionLevel.HIGH, out("c.pdf")))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.merge(listOf(pdf), out("m.pdf")))
    }

    @Test
    fun scanVariants_overSmallLimits_areRefused() {
        val big = repeated("1 0 R ", 50_000) // 300 KB decoded
        val variants = mapOf(
            "typed" to inCatalogStream(big),
            "no /Type (MuPDF needs only /N)" to inCatalogStream(big, dictExtra = ""),
            "#xx-escaped /N and /Type" to inCatalogStream(big, dictExtra = "/Ty#70e /Obj#53tm", nKey = "#4E"),
            "reset keywords inside a string and a comment" to inCatalogStream(big, dictExtra = "/X(endobj stream) %endobj\n/Type/ObjStm"),
            "unfiltered" to inCatalogStream(big, flate = false),
        )
        for ((name, bytes) in variants) {
            val f = write("v.pdf", bytes)
            assertTooLarge(name) { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
            ObjStmScan.check(f, maxStream = 400_000, maxTotal = 1_000_000) // under the bound: accepted
        }
    }

    @Test
    fun totalOverAllObjectStreams_isBounded() {
        val pdf = HostilePdf()
        basicPage(pdf)
        for (i in 0 until 5) pdf.objStm(20 + i, listOf(30 + i to repeated("null ", 40_000))) // 200 KB each
        val f = write("many.pdf", pdf.bytes())
        ObjStmScan.check(f, maxStream = 300_000, maxTotal = 1_100_000)
        assertTooLarge { ObjStmScan.check(f, maxStream = 300_000, maxTotal = 900_000) }
    }

    @Test
    fun unboundableFilter_isRefused_butGarbageFlateIsNot() {
        val chain = HostilePdf()
        basicPage(chain)
        chain.add(20, "<</Type/ObjStm/N 1/First 4/Filter[/ASCIIHexDecode/FlateDecode]/Length 4>>stream\n3030\nendstream")
        assertTooLarge { ObjStmScan.check(write("chain.pdf", chain.bytes())) }
        val lzw = HostilePdf()
        basicPage(lzw)
        lzw.add(20, "<</N 1/First 4/Filter/LZWDecode/Length 4>>stream\nabcd\nendstream")
        assertTooLarge { ObjStmScan.check(write("lzw.pdf", lzw.bytes())) }
        // Not zlib (e.g. encrypted data): ignored by the scan, left to MuPDF.
        val junk = HostilePdf()
        basicPage(junk)
        junk.add(20, "<</Type/ObjStm/N 1/First 4/Filter/FlateDecode/Length 8>>stream\nnotzlib!\nendstream")
        ObjStmScan.check(write("junk.pdf", junk.bytes()))
    }

    /** Legitimate-file check: 50,000 small objects in one honest object stream are accepted. */
    @Test
    fun largeHonestObjectStream_isAccepted() {
        val pdf = HostilePdf()
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R/Names 3 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[4 0 R]/Count 1>>")
        pdf.add(4, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 5 0 R>>")
        pdf.stream(5, "", "BT /F1 12 Tf 10 10 Td (hi) Tj ET".latin1())
        val n = 50_000
        pdf.add(3, "<</Dests<</Kids[" + (0 until 50).joinToString(" ") { "${100 + it} 0 R" } + "]>>>>")
        val members = (0 until n).map { i -> 100 + i to "<</Limits[(a$i)(a$i)]/Names[(a$i)[4 0 R/XYZ 0 $i 0]]>>".latin1() }
        pdf.objStm(10, members.subList(0, 25_000))
        pdf.objStm(11, members.subList(25_000, n))
        val f = write("honest.pdf", pdf.bytes())
        val doc = PdfInputs.open(f)
        try {
            assertTrue(doc.countObjects() > n)
        } finally {
            doc.destroy()
        }
        val o = out("honest-c.pdf")
        assertTrue(engine.compress(f, CompressionLevel.HIGH, o) is PdfResult.Ok)
    }

    /** K7-2: MuPDF reads the filter as `Filter|F` (`pdf_dict_geta`), so `/F /FlateDecode` is a Flate object stream. */
    @Test
    fun k7_2_abbreviatedFilterKey_isMeasured_inToolAndReaderPaths() {
        val bomb = write("f-bomb.pdf", abbreviated(inCatalogStream(repeated("null ", 3_500_000))))
        assertTooLarge("tool path") { PdfInputs.open(bomb).destroy() }
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(bomb, CompressionLevel.HIGH, out("fc.pdf")))
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb))
        // A benign object stream spelled the same way still opens.
        val ok = write("f-ok.pdf", abbreviated(inCatalogStream(repeated("null ", 1000))))
        PdfInputs.open(ok).destroy()
        assertTrue(engine.pageInfo(ok) is PdfPageInfo.Ok)
    }

    /** K7-2: a non-name, non-array `/F` (a file specification) on a candidate is refused. */
    @Test
    fun k7_2_fileSpecificationAsFilter_isRefused() {
        for ((name, f) in listOf("dict" to "/F<</Type/Filespec/F(x.bin)>>", "string" to "/F(x.bin)", "ref" to "/F 9 0 R")) {
            val pdf = HostilePdf()
            basicPage(pdf)
            pdf.add(20, "<</Type/ObjStm/N 1/First 4$f/Length 8>>stream\n1 0 null\nendstream")
            assertTooLarge(name) { ObjStmScan.check(write("fs-$name.pdf", pdf.bytes())) }
        }
    }

    /**
     * M2-2: only keys at the top level of the stream dictionary count. A nested dictionary's
     * `/Filter` or `/F` used to replace the outer one, so `/Filter/FlateDecode/X<</Filter[]>>` read
     * as unfiltered and the stream was counted as raw bytes while MuPDF inflates it.
     */
    @Test
    fun m2_2_nestedFilterKeyDoesNotHideTheOuterFilter() {
        val bombData = inCatalogStream(repeated("null ", 3_500_000), dictExtra = PAD)
        val missed = ArrayList<String>()
        for ((name, nested) in listOf(
            "nested /Filter []" to "/X<</Filter[]>>",
            "nested /F []" to "/X<</F[]>>",
            "nested in array" to "/X[<</Filter[]>>]",
            "array of names" to "/X[/Filter]",
            "string and comment" to "/X(<<)/Y<</Z(>>)%>>\n/Filter[]>>",
            // MuPDF takes `>>` right after a key as that key's (null) value, so this `/Filter[]` is nested for it.
            "swallowed >>" to "/X<</Y>>/Filter[]>>",
        )) {
            val bomb = write("nested.pdf", nestedAfterFilter(bombData, nested))
            val refused = runCatching { ObjStmScan.check(bomb) }.exceptionOrNull().let { it is PdfInputException && it.error == PdfError.OutOfMemory }
            if (!refused) { missed.add(name); continue }
            assertTooLarge("$name: tool path") { PdfInputs.open(bomb).destroy() }
            assertEquals(name, PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb))
        }
        assertTrue("not refused: $missed", missed.isEmpty())
    }

    /** M2-2: a candidate whose dictionary nesting cannot be trusted (a string cut at 64 KiB, unbalanced `<<`) is refused. */
    @Test
    fun m2_2_untrustworthyNestingIsRefused() {
        val missed = ArrayList<String>()
        for ((name, extra) in listOf(
            "cut string" to "/X(${"a".repeat(70_000)}<<)",
            "unbalanced" to "/X<</Y 1",
            // MuPDF reads `/Y 1<<` as Y = null and the `<<` is consumed: its /Filter is top-level for MuPDF.
            "swallowed <<" to "/Y 1<</Filter/FlateDecode/Z>>",
            "filter in an array" to "/X[/F]",
            "second /Filter" to "/Filter[]/Filter[]",
        )) {
            val pdf = HostilePdf()
            basicPage(pdf)
            pdf.add(20, "<</Type/ObjStm/N 1/First 4$extra/Length 8>>stream\n1 0 null\nendstream")
            val e = runCatching { ObjStmScan.check(write("nest.pdf", pdf.bytes())) }.exceptionOrNull()
            if (!(e is PdfInputException && e.error == PdfError.OutOfMemory)) missed.add(name)
        }
        assertTrue("not refused: $missed", missed.isEmpty())
    }

    /** M2-2: ordinary nested dictionaries in an object stream's dictionary still open. */
    @Test
    fun m2_2_benignNestedDictionariesStillOpen() {
        val small = inCatalogStream(repeated("null ", 1000), dictExtra = PAD)
        // The common predictor spelling passes the scan (the data here is not PNG-predicted, so it is not opened).
        ObjStmScan.check(write("benign-predictor.pdf", nestedAfterFilter(small, "/DecodeParms<</Columns 4/Predictor 12>>")))
        for (nested in listOf("/DecodeParms<</Columns 4>>", "/DecodeParms<</Columns 4/Colors 1>>/X[1 2]")) {
            val ok = write("benign-nested.pdf", nestedAfterFilter(small, nested))
            ObjStmScan.check(ok)
            PdfInputs.open(ok).destroy()
            assertTrue(nested, engine.pageInfo(ok) is PdfPageInfo.Ok)
        }
    }

    /**
     * Q1: binary image data in front of an object stream must not leak scanner state into it. The
     * M2-2 nesting rules used to tokenise stream data too: here a `[` and a `<` (hex string swallowing
     * `endstream endobj 7 0 obj <<...>`) in the image data left the depth wrong at the object
     * stream's `stream` keyword, and a benign object stream was refused (as a real 33 MB PDF was).
     */
    @Test
    fun q1_binaryStreamDataBeforeABenignObjectStream_opens() {
        for ((i, noise) in NOISES.withIndex()) {
            val ok = write("q1-ok.pdf", afterNoisyImage(repeated("null ", 1000), noise))
            ObjStmScan.check(ok)
            PdfInputs.open(ok).destroy()
            assertTrue("noise $i", engine.pageInfo(ok) is PdfPageInfo.Ok)
            assertTrue("noise $i", engine.compress(ok, CompressionLevel.HIGH, out("q1-c.pdf")) is PdfResult.Ok)
        }
    }

    /** Q1: the same binary data in front of an object-stream bomb does not hide it; it is refused for its size. */
    @Test
    fun q1_binaryStreamDataBeforeAnObjectStreamBomb_isRefused() {
        for ((i, noise) in NOISES.withIndex()) {
            val f = write("q1-big.pdf", afterNoisyImage(repeated("1 0 R ", 50_000), noise)) // 300 KB decoded
            assertTooLarge("noise $i") { ObjStmScan.check(f, maxStream = 200_000, maxTotal = 1_000_000) }
            ObjStmScan.check(f, maxStream = 400_000, maxTotal = 1_000_000) // under the bound: accepted
            val bomb = write("q1-bomb.pdf", afterNoisyImage(repeated("null ", 3_500_000), noise))
            assertTooLarge("noise $i: tool path") { PdfInputs.open(bomb).destroy() }
            assertEquals("noise $i", PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb))
        }
    }

    /** A page whose catalog lives in an object stream (7) holding [extra], after an image (5) whose data is [noise]. */
    private fun afterNoisyImage(extra: ByteArray, noise: ByteArray): ByteArray {
        val pdf = HostilePdf()
        pdf.stream(4, "", "BT ET".latin1())
        pdf.stream(5, "/Type/XObject/Subtype/Image/Width 4/Height 4/ColorSpace/DeviceGray/BitsPerComponent 8/Filter/DCTDecode", noise, flate = false)
        pdf.objStm(
            7,
            listOf(
                1 to "<</Type/Catalog/Pages 2 0 R>>".latin1(),
                2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>".latin1(),
                3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>".latin1(),
                6 to extra,
            ),
        )
        return pdf.bytes()
    }

    /**
     * Moves [nested] to just after the object stream's `/Filter/FlateDecode` (which [HostilePdf]
     * writes after the dictionary's other keys), taking the room from [PAD]: offsets are unchanged.
     */
    private fun nestedAfterFilter(pdf: ByteArray, nested: String): ByteArray {
        val s = String(pdf, Charsets.ISO_8859_1)
        val start = s.indexOf(PAD)
        val end = s.indexOf("/Length", start)
        val dict = "/Type/ObjStm" + s.substring(start + PAD.length, end) + nested
        assertTrue(nested, dict.length <= end - start)
        return (s.substring(0, start) + dict.padEnd(end - start, ' ') + s.substring(end)).latin1()
    }

    /** Respells every `/Filter/FlateDecode` as `/F/FlateDecode` padded to the same length (offsets unchanged). */
    private fun abbreviated(pdf: ByteArray): ByteArray =
        String(pdf, Charsets.ISO_8859_1).replace("/Filter/FlateDecode", "/F/FlateDecode     ").latin1()

    private fun inCatalogStream(extra: ByteArray, dictExtra: String = "/Type/ObjStm", nKey: String = "N", flate: Boolean = true): ByteArray {
        val pdf = HostilePdf()
        pdf.stream(4, "", "BT ET".latin1())
        pdf.objStm(
            5,
            listOf(
                1 to "<</Type/Catalog/Pages 2 0 R>>".latin1(),
                2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>".latin1(),
                3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>".latin1(),
                6 to extra,
            ),
            dictExtra = dictExtra, flate = flate, nKey = nKey,
        )
        return pdf.bytes()
    }

    private fun basicPage(pdf: HostilePdf) {
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
        pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>")
        pdf.stream(4, "", "BT ET".latin1())
    }

    private fun assertTooLarge(what: String = "", block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("$what: expected OutOfMemory, got $e", e is PdfInputException && e.error == PdfError.OutOfMemory)
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }
    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private const val PAD = "/Type/ObjStm/Pad(                                        )"
        private lateinit var dir: File

        private const val NOISE_HEAD = "ÿØÿà\u0000\u0010JFIF <<( endstream) % (\n/N [[ << ( (x) ) streamy\u0001 /ObjStm endstreamx "

        /**
         * Q1: JPEG-like stream data with unbalanced `<<`, `[`, `(`, `%`, `/N`, `/ObjStm` and
         * `stream`/`endstream` lookalikes. The first ends in a `<` that opens a hex string for a
         * tokeniser (it used to swallow the next object's header and leave the depth wrong: a false
         * refusal); the second in an unclosed `(` (it used to swallow the next object: a miss).
         */
        internal val NOISES: List<ByteArray> = listOf(
            "$NOISE_HEAD\u0080þ stream\u0002 [ <\u0089PÿÙ",
            "$NOISE_HEAD( \u0080þ stream\u0002 [ <\u0089PÿÙ",
        ).map { it.toByteArray(Charsets.ISO_8859_1) }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("objstm-scan").toFile()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
