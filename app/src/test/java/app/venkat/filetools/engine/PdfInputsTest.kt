package app.venkat.filetools.engine

import app.venkat.filetools.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfInputsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun errorOf(f: File): PdfError {
        try {
            PdfInputs.open(f).destroy()
        } catch (e: PdfInputException) {
            return e.error
        }
        fail("expected PdfInputException for ${f.name}")
        error("unreachable")
    }

    @Test
    fun textPdfOpens() {
        val f = Fixtures.textPdf(tmp.root, "in0.pdf", listOf("one", "two"))
        val doc = PdfInputs.open(f)
        try {
            assertEquals(2, doc.countPages())
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun encryptedIsEncrypted() {
        assertSame(PdfError.Encrypted, errorOf(Fixtures.encryptedPdf(tmp.root, "enc.pdf")))
    }

    @Test
    fun ownerPasswordOnlyIsEncrypted() {
        val f = Fixtures.ownerPasswordPdf(tmp.root)
        val raw = com.artifex.mupdf.fitz.Document.openDocument(f.absolutePath)
        try {
            assertTrue("fixture must open without a password", !raw.needsPassword())
        } finally {
            raw.destroy()
        }
        assertSame(PdfError.Encrypted, errorOf(f))
    }

    @Test
    fun corruptAndEmptyAreCorrupt() {
        assertSame(PdfError.Corrupt, errorOf(Fixtures.corruptPdf(tmp.root)))
        assertSame(PdfError.Corrupt, errorOf(Fixtures.emptyPdfFile(tmp.root)))
        // badly truncated -> opens "repaired" with 0 pages
        val good = Fixtures.textPdf(tmp.root, "good.pdf", listOf("x")).readBytes()
        val trunc = File(tmp.root, "trunc.pdf").also { it.writeBytes(good.copyOf(good.size / 2)) }
        assertSame(PdfError.Corrupt, errorOf(trunc))
    }

    @Test
    fun missingFileIsIo() {
        val e = errorOf(File(tmp.root, "missing.pdf"))
        assertTrue("got $e", e is PdfError.Io)
        assertTrue((e as PdfError.Io).cause.message!!.startsWith("cannot open"))
    }

    @Test
    fun validPdfWithoutPdfExtensionOpens() {
        val src = Fixtures.textPdf(tmp.root, "src.pdf", listOf("hello"))
        for (name in listOf("in0.bin", "in0", "in0.PDF", "in0.jpg")) {
            val f = File(tmp.root, name).also { src.copyTo(it) }
            val doc = PdfInputs.open(f)
            try {
                assertEquals(name, 1, doc.countPages())
            } finally {
                doc.destroy()
            }
        }
    }

    @Test
    fun nonPdfContentWithoutPdfExtensionIsCorrupt() {
        assertSame(PdfError.Corrupt, errorOf(Fixtures.corruptPdf(tmp.root, "garbage.bin")))
        // a real image is not a PDF either
        assertSame(PdfError.Corrupt, errorOf(Fixtures.jpeg(tmp.root, "photo.jpg", 50, 40)))
    }

    @Test
    fun toResultMapping() {
        val ok = PdfResult.Ok(1, 2L)
        assertSame(ok, PdfInputs.toResult { ok })
        assertEquals(PdfResult.Err(PdfError.Encrypted), PdfInputs.toResult { throw PdfInputException(PdfError.Encrypted) })
        assertEquals(PdfResult.Err(PdfError.Corrupt), PdfInputs.toResult { throw PdfInputException(PdfError.Corrupt) })
        assertEquals(
            PdfResult.Err(PdfError.UnsupportedImage),
            PdfInputs.toResult { throw UnsupportedImageFileException(File("x.jpg"), null) },
        )
        try {
            PdfInputs.toResult { throw IllegalStateException("boom") }
            fail("other exceptions must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }
}
