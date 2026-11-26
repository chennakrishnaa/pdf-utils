package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.engine.ops.MergeOp
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SR3-3: `/Annots` is charged per page and capped at [ObjectGraph.MAX_ANNOTS_PER_PAGE] before any
 * `loadPage`, and Merge copies at most [MergeOp.MAX_LINKS_PER_PAGE] links per page, checking cancel.
 * Scaled down: the largest input is 10,500 shared annotations (unfixed: a few seconds of Merge).
 */
class AnnotsBoundTest {
    private val engine = MuPdfEngine()

    @Test
    fun pageOverTheAnnotationCap_isTooLarge_inAllThreeOps_fast() {
        val pdf = write("annots-over.pdf", HostilePdf.sharedAnnots(ObjectGraph.MAX_ANNOTS_PER_PAGE + 500, 1))
        val img = Fixtures.jpeg(dir, "a.jpg", 40, 30)
        val t = System.nanoTime()
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.merge(listOf(pdf, pdf), out("m.pdf")))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(pdf, CompressionLevel.HIGH, out("c.pdf")))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.appendImages(pdf, listOf(img), out("a.pdf")))
        assertTrue("refused before loading annotations", (System.nanoTime() - t) / 1_000_000 < 2_000)
        assertFalse(File(dir, "m.pdf").exists())
    }

    /**
     * W2: the reader applies [ObjectGraph.MAX_READER_ANNOTS_PER_PAGE] instead (one page loaded at a
     * time, no link copy), so a page with 10,001 annotations opens and renders in the reader, while
     * the three tools still refuse it; one over the reader's bound is still refused by the reader.
     */
    @Test
    fun w2_readerOpensAPageOverTheToolCap_toolsStillRefuseIt() {
        val pdf = write("annots-10001.pdf", HostilePdf.sharedAnnots(ObjectGraph.MAX_ANNOTS_PER_PAGE + 1, 1))
        val img = Fixtures.jpeg(dir, "b.jpg", 40, 30)
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.merge(listOf(pdf, pdf), out("m2.pdf")))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(pdf, CompressionLevel.HIGH, out("c2.pdf")))
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.appendImages(pdf, listOf(img), out("a2.pdf")))
        assertTrue(engine.pageInfo(pdf) is PdfPageInfo.Ok)
        val rgba = File(dir, "p.rgba")
        assertEquals(PdfRender.Ok(20, 20), engine.renderPage(pdf, RenderRequest(0, 0.1f, 0, 0, 20, 20), rgba, JobControl.None))
        val over = write("annots-reader-over.pdf", HostilePdf.sharedAnnots(ObjectGraph.MAX_READER_ANNOTS_PER_PAGE + 1, 1))
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(over))
        val doc = PdfInputs.open(over)
        try {
            val e = runCatching { ObjectGraph.checkAnnots(doc, maxPerPage = ObjectGraph.MAX_READER_ANNOTS_PER_PAGE) }.exceptionOrNull()
            assertEquals(PdfError.OutOfMemory, (e as PdfInputException).error)
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun sharedAnnotsArray_isChargedOncePerPage() {
        // 300 pages x 8,000 = 2.4 M > the 2 M budget, though the array itself is under every cap.
        val pdf = write("annots-shared.pdf", HostilePdf.sharedAnnots(8_000, 300))
        val doc = PdfInputs.open(pdf)
        try {
            val e = runCatching { ObjectGraph.checkAnnots(doc) }.exceptionOrNull()
            assertEquals(PdfError.OutOfMemory, (e as PdfInputException).error)
            val e2 = runCatching { ObjectGraph.checkPages(doc) }.exceptionOrNull()
            assertEquals(PdfError.OutOfMemory, (e2 as PdfInputException).error)
        } finally {
            doc.destroy()
        }
        val small = write("annots-shared-small.pdf", HostilePdf.sharedAnnots(10, 20))
        val d2 = PdfInputs.open(small)
        try {
            val b = ObjectGraph.Budget()
            ObjectGraph.checkAnnots(d2, b)
            assertEquals("20 pages x (1 + 10)", 220, b.spent)
        } finally {
            d2.destroy()
        }
    }

    @Test
    fun budgetBulkSpend_isOverflowSafe() {
        val b = ObjectGraph.Budget(maxRefs = 100)
        b.spend(60)
        assertEquals(60, b.spent)
        val e = runCatching { b.spend(Int.MAX_VALUE) }.exceptionOrNull()
        assertEquals(PdfError.OutOfMemory, (e as PdfInputException).error)
        assertEquals(60, b.spent)
    }

    @Test
    fun merge_copiesAtMostTheLinkCap_perPage() {
        val pdf = write("links-1500.pdf", HostilePdf.distinctLinks(1_500))
        val o = out("m-links.pdf")
        val r = engine.merge(listOf(pdf), o)
        assertEquals(PdfResult.Ok(pages = 1, bytes = o.length()), r)
        val doc = PdfInputs.open(o)
        try {
            val page = doc.loadPage(0)
            try {
                assertEquals(MergeOp.MAX_LINKS_PER_PAGE, page.links.size)
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun merge_cancelInsideTheLinkLoop_isCancelled() {
        val pdf = write("links-200.pdf", HostilePdf.distinctLinks(200))
        var polls = 0
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) {}
            // Pass 1 (1 poll) and the page loop (1 poll) pass; the 10th poll is inside the link loop.
            override fun isCancelled(): Boolean = ++polls >= 10
        }
        val o = out("m-cancel.pdf")
        assertEquals(PdfResult.Err(PdfError.Cancelled), engine.merge(listOf(pdf), o, job))
        assertTrue("polled inside the loop", polls in 10..11)
        assertFalse(o.exists())
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }
    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private lateinit var dir: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("annots-bound").toFile()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
