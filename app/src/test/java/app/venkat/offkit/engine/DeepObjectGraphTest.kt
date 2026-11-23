package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * SA-11: a chain of indirect objects deep enough to overflow MuPDF's recursive save-time marking
 * is refused as Corrupt by [ObjectGraph] before any recursive native call. Plain JUnit, real MuPDF.
 *
 * Safe by construction: the deepest chain here is just over [ObjectGraph.MAX_DEPTH] (3,000 links),
 * far below the ~12,000 links a 1 MB stack survives (measured in a separate JVM), and every
 * operation runs on [EngineStack]'s 128 MB stack anyway, so even a regressed guard cannot crash
 * the test JVM.
 */
class DeepObjectGraphTest {
    private val engine = MuPdfEngine()

    @Test
    fun depth_isMeasuredIteratively() {
        val pdf = write("chain-100.pdf", chainPdf(100))
        val doc = PdfInputs.open(pdf)
        try {
            // trailer, Root, Pages, Kids, Page, Resources, then 100 linked dictionaries.
            assertEquals(106, ObjectGraph.depth(doc, doc.countObjects(), 1_000_000))
            assertEquals(51, ObjectGraph.depth(doc, doc.countObjects(), 50)) // stops early
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun chainJustBelowTheBound_succeedsEverywhere() {
        val pdf = write("chain-ok.pdf", chainPdf(ObjectGraph.MAX_DEPTH - 10))
        val img = Fixtures.jpeg(dir, "a.jpg", 40, 30)
        val results = mapOf(
            "compress" to engine.compress(pdf, CompressionLevel.HIGH, out("c.pdf")),
            "merge" to engine.merge(listOf(pdf, pdf), out("m.pdf")),
            "append" to engine.appendImages(pdf, listOf(img), out("a.pdf")),
        )
        for ((op, r) in results) assertTrue("$op: $r", r is PdfResult.Ok)
    }

    /**
     * SR2-4: Compress and Add images save without `garbage`, so no recursive pass follows the
     * chain: a 5,000-link chain (refused as Corrupt under the old trailer-walk guard) now gives a
     * valid Ok that keeps the chain. Normal engine path (128 MB stack) only.
     */
    @Test
    fun longTrailerChain_compressAndAppend_areOk_andKeepTheChain() {
        val links = 5_000
        val pdf = write("chain-5000.pdf", chainPdf(links))
        val img = Fixtures.jpeg(dir, "b.jpg", 40, 30)
        val c = out("c2.pdf")
        val a = out("a2.pdf")
        val rc = engine.compress(pdf, CompressionLevel.HIGH, c)
        val ra = engine.appendImages(pdf, listOf(img), a)
        assertEquals(PdfResult.Ok(pages = 1, bytes = c.length()), rc)
        assertEquals(PdfResult.Ok(pages = 2, bytes = a.length()), ra)
        for (o in listOf(c, a)) {
            val doc = PdfInputs.open(o)
            try {
                assertTrue("${o.name} keeps the chain", doc.countObjects() > links)
            } finally {
                doc.destroy()
            }
        }
    }

    @Test
    fun engineStack_runsOnItsOwnThread_andRethrows() {
        val caller = Thread.currentThread()
        assertNotSame(caller, EngineStack.run { Thread.currentThread() })
        val e = runCatching { EngineStack.run { throw IllegalStateException("boom") } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private lateinit var dir: File

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("deep-graph").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }

        /** One page whose /Resources hold a chain of [links] indirect dictionaries (`/Next`), the SA-11 shape. */
        fun chainPdf(links: Int): ByteArray {
            val objs = ArrayList<String>()
            objs.add("<</Type/Catalog/Pages 2 0 R>>")
            objs.add("<</Type/Pages/Count 1/Kids[3 0 R]>>")
            objs.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<</X 4 0 R>>>>")
            for (i in 0 until links) objs.add(if (i < links - 1) "<</Next ${5 + i} 0 R>>" else "<</End true>>")
            val b = ByteArrayOutputStream()
            fun put(s: String) = b.write(s.toByteArray(Charsets.ISO_8859_1))
            put("%PDF-1.7\n")
            val offs = ArrayList<Int>()
            for ((i, o) in objs.withIndex()) {
                offs.add(b.size())
                put("${i + 1} 0 obj\n$o\nendobj\n")
            }
            val x = b.size()
            put("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
            for (o in offs) put("%010d 00000 n \n".format(o))
            put("trailer\n<</Size ${objs.size + 1}/Root 1 0 R>>\nstartxref\n$x\n%%EOF\n")
            return b.toByteArray()
        }
    }
}
