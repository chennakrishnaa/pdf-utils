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
 * SA-11 / SR2-3 / SR2-4: deep and dense object graphs. Compress and Add images save without
 * `garbage` (no recursion over references); Merge's page-rooted walk ([ObjectGraph.checkPages])
 * bounds nesting and both walks are bounded by a reference/time budget. Plain JUnit, real MuPDF.
 *
 * Safe by construction: the longest chain here (5,000 links) is below what even a 1 MB stack
 * survived in the old garbage save (~12,000, measured in a separate JVM), the graft caps itself at
 * ~125 links, every operation runs on [EngineStack]'s 128 MB stack, and the dense graph has 300k
 * references (~20 MB parsed), so a regression shows as a wrong result, not a crash.
 */
class DeepObjectGraphTest {
    private val engine = MuPdfEngine()

    /** Merge: a page-hung chain just under [ObjectGraph.MAX_GRAFT_NESTING] is merged whole. */
    @Test
    fun merge_pageChainJustUnderTheBound_isOk_andCopiedWhole() {
        val links = 90 // levels: Resources dict + 90 hops + 90 dicts = 181 + 1 <= 200
        val pdf = write("chain-90.pdf", chainPdf(links))
        val o = out("m-90.pdf")
        val r = engine.merge(listOf(pdf, pdf), o)
        assertEquals(PdfResult.Ok(pages = 2, bytes = o.length()), r)
        val doc = PdfInputs.open(o)
        try {
            assertTrue("whole chain copied (deduplicated across the two copies)", doc.countObjects() > links)
        } finally {
            doc.destroy()
        }
    }

    /** Merge: a page-hung chain over the bound (where the graft would silently truncate) is Corrupt, no output. */
    @Test
    fun merge_pageChainOverTheBound_isCorrupt_withoutOutput() {
        for (links in listOf(110, 5_000)) {
            val pdf = write("chain-$links.pdf", chainPdf(links))
            val o = out("m-$links.pdf")
            assertEquals("$links", PdfResult.Err(PdfError.Corrupt), engine.merge(listOf(pdf), o))
            assertFalse(o.exists())
        }
    }

    /** SR2-3: the walks are bounded by references visited; a dense but small graph finishes fast. */
    @Test
    fun walkBudget_finishesFast_andTripsWhenInjected() {
        val pdf = write("dense.pdf", densePdf(arrays = 300, refs = 1_000))
        val doc = PdfInputs.open(pdf)
        try {
            val t0 = System.nanoTime()
            val objects = ObjectGraph.Budget()
            ObjectGraph.checkObjects(doc, objects)
            val pages = ObjectGraph.Budget()
            ObjectGraph.checkPages(doc, pages)
            assertTrue("fast", (System.nanoTime() - t0) / 1_000_000 < 3_000)
            assertTrue("counted ${objects.spent}", objects.spent >= 300_000)
            assertTrue("counted ${pages.spent}", pages.spent >= 300_000)
            for (walk in listOf<(ObjectGraph.Budget) -> Unit>({ ObjectGraph.checkObjects(doc, it) }, { ObjectGraph.checkPages(doc, it) })) {
                val e = runCatching { walk(ObjectGraph.Budget(maxRefs = 100_000)) }.exceptionOrNull()
                assertEquals(PdfError.OutOfMemory, (e as PdfInputException).error)
                val t = runCatching { walk(ObjectGraph.Budget(maxMillis = 0)) }.exceptionOrNull()
                assertEquals(PdfError.OutOfMemory, (t as PdfInputException).error)
            }
        } finally {
            doc.destroy()
        }
        // The real ops still succeed on it under the default budget.
        assertTrue(engine.compress(pdf, CompressionLevel.HIGH, out("dense-c.pdf")) is PdfResult.Ok)
        assertTrue(engine.merge(listOf(pdf), out("dense-m.pdf")) is PdfResult.Ok)
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

        /** One page whose /Resources /X hold [arrays] arrays of [refs] references each to shared leaf dictionaries. */
        fun densePdf(arrays: Int, refs: Int): ByteArray {
            val objs = ArrayList<String>()
            objs.add("<</Type/Catalog/Pages 2 0 R>>")
            objs.add("<</Type/Pages/Count 1/Kids[3 0 R]>>")
            objs.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<</X 4 0 R>>>>")
            objs.add((0 until arrays).joinToString(" ", "[", "]") { "${5 + it} 0 R" })
            val leaf0 = 5 + arrays
            val arr = (0 until refs).joinToString(" ", "[", "]") { "${leaf0 + it} 0 R" }
            repeat(arrays) { objs.add(arr) }
            repeat(refs) { objs.add("<</L $it>>") }
            return assemble(objs)
        }

        /** One page whose /Resources hold a chain of [links] indirect dictionaries (`/Next`), the SA-11 shape. */
        fun chainPdf(links: Int): ByteArray {
            val objs = ArrayList<String>()
            objs.add("<</Type/Catalog/Pages 2 0 R>>")
            objs.add("<</Type/Pages/Count 1/Kids[3 0 R]>>")
            objs.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<</X 4 0 R>>>>")
            for (i in 0 until links) objs.add(if (i < links - 1) "<</Next ${5 + i} 0 R>>" else "<</End true>>")
            return assemble(objs)
        }

        private fun assemble(objs: List<String>): ByteArray {
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
