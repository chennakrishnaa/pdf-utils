package app.venkat.filetools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Plain JUnit: MuPdfEngine error handling and its common error wrapper. */
class MuPdfEngineStubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Every engine method must go through guardedRun: given inputs that any correct
    // implementation has to reject (files that do not exist), the result is an Err of
    // some kind and a stale `out` is deleted. No specific error type is asserted.
    private val missingPdf get() = tmp.root.resolve("missing.pdf")
    private val missingImg get() = tmp.root.resolve("missing.jpg")

    private fun staleOut() = tmp.root.resolve("out.pdf").apply { writeText("stale") }

    private fun assertErrAndOutDeleted(result: PdfResult, out: java.io.File) {
        assertTrue("expected Err, got $result", result is PdfResult.Err)
        assertFalse(out.exists())
    }

    @Test
    fun imagesToPdfErrDeletesStaleOutput() {
        val out = staleOut()
        assertErrAndOutDeleted(MuPdfEngine().imagesToPdf(listOf(missingImg), PageSize.A4, out), out)
    }

    @Test
    fun compressErrDeletesStaleOutput() {
        val out = staleOut()
        assertErrAndOutDeleted(MuPdfEngine().compress(missingPdf, CompressionLevel.MEDIUM, out), out)
    }

    @Test
    fun mergeErrDeletesStaleOutput() {
        val out = staleOut()
        assertErrAndOutDeleted(MuPdfEngine().merge(listOf(missingPdf, missingPdf), out), out)
    }

    @Test
    fun appendImagesErrDeletesStaleOutput() {
        val out = staleOut()
        assertErrAndOutDeleted(MuPdfEngine().appendImages(missingPdf, listOf(missingImg), out), out)
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
