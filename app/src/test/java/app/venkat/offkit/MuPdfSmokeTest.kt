package app.venkat.offkit

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM test: proves the host MuPDF native library (libmupdf_java64.so, found via
 * java.library.path = $MUPDF_HOST_LIB_DIR or ~/mupdf-host) loads inside Gradle's test JVM.
 */
class MuPdfSmokeTest {
    @Test
    fun createDocumentWithOneBlankPage() {
        val doc = PDFDocument()
        try {
            val mediaBox = Rect(0f, 0f, 595f, 842f)
            val resources = doc.newDictionary()
            val page = doc.addPage(mediaBox, 0, resources, "")
            doc.insertPage(-1, page)
            assertEquals(1, doc.countPages())
        } finally {
            doc.destroy()
        }
    }
}
