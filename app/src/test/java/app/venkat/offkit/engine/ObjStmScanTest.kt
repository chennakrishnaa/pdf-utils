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
        private lateinit var dir: File

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
