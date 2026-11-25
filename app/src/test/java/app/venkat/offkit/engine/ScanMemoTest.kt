package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.engine.HostilePdf.Companion.latin1
import app.venkat.offkit.engine.HostilePdf.Companion.repeated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** RDR-4: [ScanMemo] remembers only passed verdicts, for exactly one file identity. */
class ScanMemoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()

    @Before
    fun reset() = ScanMemo.clear()

    private fun render(pdf: File): PdfRender =
        engine.renderPage(pdf, RenderRequest(0, 1f, 0, 0, 100, 100), File(tmp.root, "out.rgba"), JobControl.None)

    private val ok = PdfRender.Ok(100, 100)
    private val tooLarge = PdfRender.Err(PdfError.OutOfMemory)

    /** 17.5 MB of decoded object stream from ~20 KB: ObjStmScan refuses it (as in ObjStmScanTest). */
    private fun objStmBomb(): ByteArray {
        val pdf = HostilePdf()
        pdf.stream(4, "", "BT ET".latin1())
        pdf.objStm(5, listOf(
            1 to "<</Type/Catalog/Pages 2 0 R>>".latin1(),
            2 to "<</Type/Pages/Kids[3 0 R]/Count 1>>".latin1(),
            3 to "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>".latin1(),
            6 to repeated("null ", 3_500_000),
        ))
        return pdf.bytes()
    }

    private fun scansDuring(block: () -> Unit): Long {
        val before = PdfInputs.objStmScans
        block()
        return PdfInputs.objStmScans - before
    }

    @Test
    fun benignFileIsScannedOncePerDocument() {
        val pdf = Fixtures.quadrantPdf(tmp.root, "q.pdf", listOf(Fixtures.QuadrantPage(200f, 100f)))
        assertEquals(1, scansDuring {
            assertTrue(engine.pageInfo(pdf) is PdfPageInfo.Ok)
            repeat(5) { assertEquals(ok, render(pdf)) }
        })
    }

    @Test
    fun samePathRewrittenWithHostileContentIsScannedAgainAndRefused() {
        val pdf = Fixtures.quadrantPdf(tmp.root, "q.pdf", listOf(Fixtures.QuadrantPage(200f, 100f)))
        assertEquals(ok, render(pdf))
        val mtime = pdf.lastModified()
        pdf.writeBytes(objStmBomb())
        pdf.setLastModified(mtime) // even with the old time
        assertEquals(2, scansDuring {
            assertEquals(tooLarge, render(pdf))
            assertEquals(tooLarge, render(pdf)) // a failed scan is never remembered
        })
    }

    @Test
    fun sameLengthAndTimeButDifferentBytesMisses() {
        val a = Fixtures.quadrantPdf(tmp.root, "a.pdf", listOf(Fixtures.QuadrantPage(200f, 100f)))
        val bytes = a.readBytes()
        val k1 = ScanMemo.keyOf(a)!!
        bytes[bytes.size - 2] = (bytes[bytes.size - 2] + 1).toByte() // tail byte (inside "%%EOF\n" area)
        val mtime = a.lastModified()
        a.writeBytes(bytes)
        a.setLastModified(mtime)
        val k2 = ScanMemo.keyOf(a)!!
        assertEquals(k1.length, k2.length)
        assertEquals(k1.modified, k2.modified)
        assertNotEquals(k1, k2)
    }

    @Test
    fun anotherFileDoesNotInheritTheVerdict() {
        val good = Fixtures.quadrantPdf(tmp.root, "good.pdf", listOf(Fixtures.QuadrantPage(200f, 100f)))
        val bad = File(tmp.root, "bad.pdf").apply { writeBytes(objStmBomb()) }
        assertEquals(ok, render(good))
        assertEquals(tooLarge, render(bad))
        assertEquals(ok, render(good)) // one entry: the good file is scanned again, still Ok
        assertEquals(tooLarge, render(bad))
    }

    @Test
    fun imageGateVerdictIsPerPageAndOnlyWhenPassed() {
        // Page 0 is plain, page 1 draws the SA-2 progressive JPEG bomb.
        val bomb = EngineSecurityTest.progressiveJpegDeclaring(65500, 65500)
        val pdf = HostilePdf()
        pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
        pdf.add(2, "<</Type/Pages/Kids[3 0 R 5 0 R]/Count 2>>")
        pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>")
        pdf.stream(4, "", "0 0 1 rg 0 0 100 100 re f".latin1())
        pdf.add(5, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<</XObject<</Im0 10 0 R>>>>/Contents 6 0 R>>")
        pdf.stream(6, "", "q 200 0 0 200 0 0 cm /Im0 Do Q".latin1())
        pdf.stream(10, "/Subtype/Image/Width 8/Height 8/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode", bomb, flate = false)
        val f = File(tmp.root, "two.pdf").apply { writeBytes(pdf.bytes()) }
        val req1 = RenderRequest(1, 1f, 0, 0, 100, 100)
        assertEquals(ok, render(f))
        repeat(3) {
            val t0 = System.nanoTime()
            assertEquals(tooLarge, engine.renderPage(f, req1, File(tmp.root, "o1.rgba"), JobControl.None))
            assertTrue((System.nanoTime() - t0) < 2_000_000_000L)
        }
        assertEquals(ok, render(f))
    }
}
