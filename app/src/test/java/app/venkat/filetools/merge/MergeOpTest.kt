package app.venkat.filetools.merge

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import app.venkat.filetools.Fixtures
import app.venkat.filetools.engine.JobControl
import app.venkat.filetools.engine.MuPdfEngine
import app.venkat.filetools.engine.PdfError
import app.venkat.filetools.engine.PdfResult
import app.venkat.filetools.engine.ops.MergeOp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/** Plain JUnit with real MuPDF: [MergeOp] through [MuPdfEngine.merge]. */
class MergeOpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private class RecordingJob(private val cancelAfter: Int = Int.MAX_VALUE) : JobControl {
        val calls = mutableListOf<Pair<Int, Int>>()
        override fun progress(done: Int, total: Int) {
            calls += done to total
        }
        override fun isCancelled() = calls.size >= cancelAfter
    }

    private fun assertMonotonic(calls: List<Pair<Int, Int>>) {
        for (k in 1 until calls.size) {
            assertTrue("progress not monotonic: $calls", calls[k].first >= calls[k - 1].first)
            assertEquals("total changed: $calls", calls[0].second, calls[k].second)
        }
    }

    @Test
    fun twoTextPdfsInOrder() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("Alpha one", "Alpha two"))
        val b = Fixtures.textPdf(tmp.root, "b.pdf", listOf("Bravo one", "Bravo two", "Bravo three"))
        val out = out()
        val job = RecordingJob()

        val r = engine.merge(listOf(a, b), out, job)

        assertEquals(PdfResult.Ok(5, out.length()), r)
        assertEquals(5, Fixtures.pageCount(out))
        val expect = listOf("Alpha one", "Alpha two", "Bravo one", "Bravo two", "Bravo three")
        val texts = Fixtures.pageTexts(out)
        expect.forEachIndexed { i, t -> assertEquals("page $i", t, texts[i].trim()) }
        assertMonotonic(job.calls)
        assertEquals(5 to 5, job.calls.last())
        assertEquals((1..5).map { it to 5 }, job.calls)
    }

    @Test
    fun pageSizesPreservedPerPage() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("A4"))
        val b = Fixtures.textPdf(tmp.root, "b.pdf", listOf("Letter 1", "Letter 2"), 612f, 792f)
        val c = Fixtures.textPdf(tmp.root, "c.pdf", listOf("Wide"), 900f, 300f)
        val out = out()

        assertTrue(engine.merge(listOf(a, b, c), out) is PdfResult.Ok)

        val expected = Fixtures.pageSizes(a) + Fixtures.pageSizes(b) + Fixtures.pageSizes(c)
        val actual = Fixtures.pageSizes(out)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, x) ->
            assertEquals(e.first, x.first, 0.5f)
            assertEquals(e.second, x.second, 0.5f)
        }
        assertEquals(900f, actual[3].first, 0.5f)
        assertEquals(300f, actual[3].second, 0.5f)
    }

    @Test
    fun externalLinkRecreatedOnCorrectPage() {
        val uri = "https://example.com/merge?x=1"
        val text = Fixtures.textPdf(tmp.root, "t.pdf", listOf("One", "Two"))
        val link = Fixtures.linkPdf(tmp.root, "l.pdf", uri)
        val out = out()

        assertTrue(engine.merge(listOf(text, link), out) is PdfResult.Ok)

        assertEquals(3, Fixtures.pageCount(out))
        assertTrue(Fixtures.links(out, 0).isEmpty())
        assertTrue(Fixtures.links(out, 1).isEmpty())
        val links = Fixtures.links(out, 2)
        assertEquals(1, links.size)
        assertEquals(uri, links[0].first)
        val r = Fixtures.LINK_RECT
        for (k in 0 until 4) assertEquals("rect[$k]", r[k], links[0].second[k], 0.5f)
    }

    @Test
    fun internalLinksAreDroppedExternalKept() {
        val src = Fixtures.textPdf(tmp.root, "s.pdf", listOf("P1", "P2"))
        val withLinks = File(tmp.root, "links.pdf")
        val doc = Document.openDocument(src.absolutePath) as PDFDocument
        try {
            val page = doc.loadPage(0)
            try {
                page.createLink(Rect(10f, 10f, 50f, 50f), "#page=2").destroy()
                page.createLink(Rect(60f, 60f, 100f, 100f), "mailto:someone@example.com").destroy()
            } finally {
                page.destroy()
            }
            doc.save(withLinks.absolutePath, "garbage=deduplicate,compress")
        } finally {
            doc.destroy()
        }
        assertEquals(2, Fixtures.links(withLinks, 0).size)
        val out = out()

        assertTrue(engine.merge(listOf(withLinks), out) is PdfResult.Ok)

        val links = Fixtures.links(out, 0)
        assertEquals(listOf("mailto:someone@example.com"), links.map { it.first })
    }

    @Test
    fun isExternalUriClassification() {
        assertTrue(MergeOp.isExternalUri("https://a.b"))
        assertTrue(MergeOp.isExternalUri("http://a.b"))
        assertTrue(MergeOp.isExternalUri("mailto:x@y.z"))
        assertFalse(MergeOp.isExternalUri("#page=3"))
        assertFalse(MergeOp.isExternalUri("page.html"))
        assertFalse(MergeOp.isExternalUri(""))
        assertFalse(MergeOp.isExternalUri(null))
    }

    @Test
    fun sameFileTwice() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("First", "Second"))
        val out = out()

        val r = engine.merge(listOf(a, a), out)

        assertEquals(PdfResult.Ok(4, out.length()), r)
        assertEquals(listOf("First", "Second", "First", "Second"), Fixtures.pageTexts(out).map { it.trim() })
    }

    @Test
    fun singleInputIsResaved() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("Only"))
        val out = out()
        val r = engine.merge(listOf(a), out)
        assertEquals(PdfResult.Ok(1, out.length()), r)
        assertEquals(listOf("Only"), Fixtures.pageTexts(out).map { it.trim() })
    }

    @Test
    fun photoPdfImagesIntact() {
        val photo = Fixtures.photoPdf(tmp.root, "p.pdf", pages = 2, imageWidth = 800, imageHeight = 600)
        val text = Fixtures.textPdf(tmp.root, "t.pdf", listOf("Text"))
        val out = out()

        val r = engine.merge(listOf(text, photo), out)

        assertEquals(PdfResult.Ok(3, out.length()), r)
        val before = Fixtures.imageInfos(photo).map { it.width to it.height }.sortedBy { it.toString() }
        val after = Fixtures.imageInfos(out).map { it.width to it.height }.sortedBy { it.toString() }
        assertEquals(2, before.size)
        assertEquals(before, after)
        val texts = Fixtures.pageTexts(out)
        assertTrue(texts[1].contains("Photo page 1"))
        assertTrue(texts[2].contains("Photo page 2"))
    }

    @Test
    fun badInputsFailAndLeaveNoOutput() {
        val good = Fixtures.textPdf(tmp.root, "g.pdf", listOf("Good"))
        val enc = Fixtures.encryptedPdf(tmp.root)
        val corrupt = Fixtures.corruptPdf(tmp.root)
        val empty = Fixtures.emptyPdfFile(tmp.root)
        val missing = File(tmp.root, "missing.pdf")
        val cases = listOf(
            listOf(enc, good) to PdfError.Encrypted,
            listOf(good, enc) to PdfError.Encrypted,
            listOf(good, good, enc) to PdfError.Encrypted,
            listOf(good, corrupt) to PdfError.Corrupt,
            listOf(empty, good) to PdfError.Corrupt,
        )
        for ((inputs, expected) in cases) {
            val out = out()
            out.writeText("stale")
            assertEquals(inputs.map { it.name }.toString(), PdfResult.Err(expected), engine.merge(inputs, out))
            assertFalse(out.exists())
        }
        val out = out()
        out.writeText("stale")
        val r = engine.merge(listOf(good, missing), out) as PdfResult.Err
        assertTrue(r.error is PdfError.Io)
        assertFalse(out.exists())

        val none = engine.merge(emptyList(), out) as PdfResult.Err
        assertTrue((none.error as PdfError.Io).cause is IllegalArgumentException)
        assertFalse(out.exists())
    }

    @Test
    fun ownerPasswordOnlyInputIsEncryptedAndLeavesNoOutput() {
        val good = Fixtures.textPdf(tmp.root, "g.pdf", listOf("Good"))
        val owner = Fixtures.ownerPasswordPdf(tmp.root)
        for (inputs in listOf(listOf(owner, good), listOf(good, owner))) {
            val out = out()
            out.writeText("stale")
            assertEquals(PdfResult.Err(PdfError.Encrypted), engine.merge(inputs, out))
            assertFalse(out.exists())
        }
    }

    @Test
    fun noProgressBeforeAllInputsValidated() {
        val good = Fixtures.textPdf(tmp.root, "g.pdf", listOf("Good", "Good 2"))
        val enc = Fixtures.encryptedPdf(tmp.root)
        val job = RecordingJob()
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.merge(listOf(good, enc), out(), job))
        assertTrue(job.calls.isEmpty())
    }

    @Test
    fun inputsUnchanged() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("A1", "A2"))
        val b = Fixtures.linkPdf(tmp.root, "b.pdf", "https://example.com")
        val c = Fixtures.photoPdf(tmp.root, "c.pdf", pages = 1, imageWidth = 300, imageHeight = 200)
        val before = listOf(a, b, c).map { it.readBytes() }
        val mtimes = listOf(a, b, c).map { it.lastModified() }

        assertTrue(engine.merge(listOf(a, b, c, a), out()) is PdfResult.Ok)

        listOf(a, b, c).forEachIndexed { i, f ->
            assertArrayEquals(f.name, before[i], f.readBytes())
            assertEquals(mtimes[i], f.lastModified())
        }
    }

    @Test
    fun cancelAfterFirstProgress() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("A1", "A2"))
        val b = Fixtures.textPdf(tmp.root, "b.pdf", listOf("B1"))
        val out = out()
        val job = RecordingJob(cancelAfter = 1)

        assertEquals(PdfResult.Err(PdfError.Cancelled), engine.merge(listOf(a, b), out, job))

        assertFalse(out.exists())
        assertEquals(listOf(1 to 3), job.calls)
    }

    @Test
    fun okBytesMatchesFileAndPagesCountable() {
        val a = Fixtures.textPdf(tmp.root, "a.pdf", listOf("x"))
        val out = out()
        val r = engine.merge(listOf(a, a, a), out) as PdfResult.Ok
        assertEquals(out.length(), r.bytes)
        assertEquals(3, r.pages)
        assertTrue(abs(r.bytes) > 0)
    }
}
