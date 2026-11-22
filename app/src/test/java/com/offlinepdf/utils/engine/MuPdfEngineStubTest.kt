package com.offlinepdf.utils.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Plain JUnit: stubbed MuPdfEngine and its common error wrapper. */
class MuPdfEngineStubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val notImplemented = PdfResult.Err(PdfError.NotImplemented)

    @Test
    fun allOperationsReturnNotImplemented() {
        val engine = MuPdfEngine()
        val img = tmp.newFile("a.jpg")
        val pdf = tmp.newFile("a.pdf")
        val out = tmp.root.resolve("out.pdf")

        // A stale `out` must be removed on Err, proving every method goes through the wrapper.
        out.writeText("stale")
        assertEquals(notImplemented, engine.imagesToPdf(listOf(img), PageSize.A4, out))
        assertFalse(out.exists())
        out.writeText("stale")
        assertEquals(notImplemented, engine.compress(pdf, CompressionLevel.MEDIUM, out))
        assertFalse(out.exists())
        out.writeText("stale")
        assertEquals(notImplemented, engine.merge(listOf(pdf, pdf), out))
        assertFalse(out.exists())
        out.writeText("stale")
        assertEquals(notImplemented, engine.appendImages(pdf, listOf(img), out))
        assertFalse(out.exists())
    }

    @Test
    fun throwingOpMapsToIoAndDeletesPartialOutput() {
        val out = tmp.root.resolve("partial.pdf")
        val boom = IOException("disk full")

        val result = guardedRun(out) {
            out.writeText("partial")
            throw boom
        }

        val err = result as PdfResult.Err
        assertTrue(err.error is PdfError.Io)
        assertSame(boom, (err.error as PdfError.Io).cause)
        assertFalse(out.exists())
    }

    @Test
    fun outOfMemoryMapsToOutOfMemoryAndDeletesPartialOutput() {
        val out = tmp.root.resolve("partial.pdf")

        val result = guardedRun(out) {
            out.writeText("partial")
            throw OutOfMemoryError("test")
        }

        assertEquals(PdfResult.Err(PdfError.OutOfMemory), result)
        assertFalse(out.exists())
    }

    @Test
    fun returnedErrDeletesPartialOutput() {
        val out = tmp.root.resolve("partial.pdf")

        val result = guardedRun(out) {
            out.writeText("partial")
            PdfResult.Err(PdfError.Corrupt)
        }

        assertEquals(PdfResult.Err(PdfError.Corrupt), result)
        assertFalse(out.exists())
    }

    @Test
    fun okKeepsOutput() {
        val out = tmp.root.resolve("ok.pdf")

        val result = guardedRun(out) {
            out.writeText("done")
            PdfResult.Ok(pages = 1, bytes = out.length())
        }

        assertEquals(PdfResult.Ok(1, 4), result)
        assertTrue(out.exists())
    }

    @Test
    fun compressionLevelParameters() {
        assertEquals(2000 to 80, CompressionLevel.LOW.let { it.maxLongEdgePx to it.jpegQuality })
        assertEquals(1500 to 60, CompressionLevel.MEDIUM.let { it.maxLongEdgePx to it.jpegQuality })
        assertEquals(1000 to 40, CompressionLevel.HIGH.let { it.maxLongEdgePx to it.jpegQuality })
    }
}
