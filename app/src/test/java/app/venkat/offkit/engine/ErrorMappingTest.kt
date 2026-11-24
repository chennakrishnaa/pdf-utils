package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** SR3-5: allocation failures during open are OutOfMemory; a /Parent cycle in Add images is Corrupt. */
class ErrorMappingTest {
    @Test
    fun nativeAllocationFailureDuringOpen_isOutOfMemory() {
        for (msg in listOf("malloc (24 bytes) failed", "calloc (10 x 8 bytes) failed", "realloc (99 bytes) failed")) {
            assertEquals(msg, PdfError.OutOfMemory, PdfInputs.classifyOpenFailure(RuntimeException(msg)).error)
            assertEquals(msg, PdfError.OutOfMemory, classifyFailure(RuntimeException(msg)))
        }
        assertEquals(PdfError.Corrupt, PdfInputs.classifyOpenFailure(RuntimeException("no objects found")).error)
    }

    @Test
    fun parentCycle_inAddImages_isCorrupt() {
        val dir = Files.createTempDirectory("parent-cycle").toFile()
        try {
            val pdf = HostilePdf()
            pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
            pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1/Parent 5 0 R>>")
            pdf.add(5, "<</Type/Pages/Parent 2 0 R>>")
            pdf.add(3, "<</Type/Page/Parent 2 0 R/Contents 4 0 R>>") // MediaBox must be inherited
            pdf.stream(4, "", "BT ET".toByteArray())
            val f = dir.resolve("cycle.pdf").also { it.writeBytes(pdf.bytes()) }
            val img = Fixtures.jpeg(dir, "a.jpg", 40, 30)
            val r = MuPdfEngine().appendImages(f, listOf(img), dir.resolve("o.pdf"))
            assertEquals(PdfResult.Err(PdfError.Corrupt), r)
        } finally {
            dir.deleteRecursively()
        }
    }
}
