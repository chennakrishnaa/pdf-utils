package app.venkat.offkit.engine

import app.venkat.offkit.EncryptedFixtures
import app.venkat.offkit.engine.ops.DecryptCopyOp
import app.venkat.offkit.engine.remote.EngineRequest
import app.venkat.offkit.engine.remote.EngineServer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P1: the unencrypted copy the reader prints a protected PDF from (real MuPDF). */
class DecryptCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(tmp.root, "reader-test").apply { mkdirs() }
        ScanMemo.clear()
    }

    @After
    fun tearDown() = ScanMemo.clear()

    private data class Case(val name: String, val b64: String, val password: String?)

    private val cases = listOf(
        Case("rc4", EncryptedFixtures.RC4_B64, EncryptedFixtures.RC4_USER),
        Case("aes128", EncryptedFixtures.AES128_B64, EncryptedFixtures.AES128_USER),
        Case("aes256", EncryptedFixtures.AES256_B64, EncryptedFixtures.AES256_USER),
        Case("owner-only", EncryptedFixtures.OWNER_ONLY_B64, null),
        Case("aes128-owner-only", EncryptedFixtures.AES128_OWNER_ONLY_B64, null),
    )

    /** Page count and per-page text of [file] opened with stock MuPDF ([password] if it needs one). */
    private fun pagesAndText(file: File, password: String?): Pair<Int, List<String>> {
        val doc = Document.openDocument(file.absolutePath)
        try {
            if (doc.needsPassword()) assertTrue(doc.authenticatePassword(password))
            val n = doc.countPages()
            return n to (0 until n).map { i ->
                val page = doc.loadPage(i)
                try {
                    val st = page.toStructuredText()
                    try { st.asText() } finally { st.destroy() }
                } finally {
                    page.destroy()
                }
            }
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun theCopyOpensWithoutAPasswordWithTheSamePagesAndTextAndNoEncryption() {
        for (c in cases) {
            val src = EncryptedFixtures.write(dir, "${c.name}.pdf", c.b64)
            val out = File(dir, "print-${c.name}.pdf")
            val r = engine.decryptCopy(src, out, c.password)
            assertTrue("${c.name}: $r", r is PdfResult.Ok)
            r as PdfResult.Ok
            assertEquals(out.length(), r.bytes)
            val doc = Document.openDocument(out.absolutePath) as PDFDocument
            try {
                assertFalse(c.name, doc.needsPassword())
                assertTrue(c.name, doc.isUnencryptedPDF)
                assertFalse(c.name, PdfInputs.isEncrypted(doc))
                assertTrue(c.name, doc.trailer.get("Encrypt").isNull)
            } finally {
                doc.destroy()
            }
            val original = pagesAndText(src, c.password)
            val copy = pagesAndText(out, null)
            assertEquals(c.name, original, copy)
            assertEquals(c.name, original.first, r.pages)
            assertTrue(c.name, copy.second.joinToString().contains(EncryptedFixtures.WORD))
            // The tools still refuse the encrypted original.
            assertEquals(c.name, PdfResult.Err(PdfError.Encrypted), engine.merge(listOf(src, src), File(dir, "m.pdf")))
        }
    }

    @Test
    fun wrongOrMissingPasswordLeavesNoFile() {
        val src = EncryptedFixtures.write(dir, "aes.pdf", EncryptedFixtures.AES256_B64)
        val out = File(dir, "print-x.pdf")
        out.writeBytes(byteArrayOf(1, 2, 3)) // a stale file is removed
        assertEquals(PdfResult.Err(PdfError.WrongPassword), engine.decryptCopy(src, out, "nope"))
        assertFalse(out.exists())
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.decryptCopy(src, out, null))
        assertFalse(out.exists())
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.decryptCopy(src, out, ""))
        assertFalse(out.exists())
        val tooLong = "x".repeat(200)
        assertTrue(engine.decryptCopy(src, out, tooLong).let { it is PdfResult.Err && it.error is PdfError.Io })
        assertFalse(out.exists())
    }

    @Test
    fun corruptOrCancelledLeavesNoFile() {
        val junk = File(dir, "junk.pdf").apply { writeText("not a pdf at all") }
        val out = File(dir, "print-j.pdf")
        assertTrue(engine.decryptCopy(junk, out, null) is PdfResult.Err)
        assertFalse(out.exists())
        val src = EncryptedFixtures.write(dir, "rc4.pdf", EncryptedFixtures.RC4_B64)
        val cancelled = object : JobControl {
            override fun progress(done: Int, total: Int) = Unit
            override fun isCancelled() = true
        }
        assertEquals(PdfResult.Err(PdfError.Cancelled), engine.decryptCopy(src, out, EncryptedFixtures.RC4_USER, cancelled))
        assertFalse(out.exists())
    }

    @Test
    fun anUnencryptedPdfIsCopiedToo() {
        val plain = File(dir, "plain.pdf")
        val doc = PDFDocument()
        try {
            doc.insertPage(-1, doc.addPage(Rect(0f, 0f, 100f, 100f), 0, doc.newDictionary(), ""))
            doc.save(plain.absolutePath, "")
        } finally {
            doc.destroy()
        }
        val out = File(dir, "print-plain.pdf")
        assertEquals(1, (engine.decryptCopy(plain, out, null) as PdfResult.Ok).pages)
    }

    @Test
    fun saveOptionsOnlyDropEncryption() {
        assertEquals("encrypt=none", DecryptCopyOp.SAVE_OPTIONS)
    }

    @Test
    fun theServerOnlyAcceptsACopyInsideTheDocumentsOwnReaderFolder() {
        val root = tmp.root
        val src = File(dir, "document.pdf").path
        EngineServer.checkPaths(root, listOf(src, File(dir, "print-1.pdf").path))
        EngineServer.checkReaderPair(root, src, File(dir, "print-1.pdf").path)
        val refused = listOf(
            File(root, "reader-other/print-1.pdf").path, // another document's folder
            File(root, "print-1.pdf").path, // the jobs folder itself
            File(dir, "../reader-other/print-1.pdf").path,
            File(root.parentFile, "print-1.pdf").path, // outside the jobs folder
        )
        for (out in refused) {
            try {
                EngineServer.checkReaderPair(root, src, out)
                fail("accepted $out")
            } catch (e: SecurityException) {
                // expected
            }
        }
        val job = File(root, "job-1").apply { mkdirs() }
        try {
            EngineServer.checkReaderPair(root, File(job, "a.pdf").path, File(job, "b.pdf").path)
            fail("accepted a non-reader folder")
        } catch (e: SecurityException) {
            // expected
        }
        try {
            EngineServer.checkPaths(root, listOf(File(root.parentFile, "x.pdf").path))
            fail("accepted a path outside the jobs folder")
        } catch (e: SecurityException) {
            // expected
        }
    }

    /** Z3-2: a copy onto its own source, a folder or a link is refused, and the source survives. */
    @Test
    fun aCopyOntoItsSourceOrANonRegularFileIsRefusedAndTheSourceSurvives() {
        val root = tmp.root
        val src = EncryptedFixtures.write(dir, "doc.pdf", EncryptedFixtures.OWNER_ONLY_B64)
        val bytes = src.readBytes()
        val folder = File(dir, "print-dir.pdf").apply { mkdirs() }
        val link = File(dir, "print-link.pdf")
        java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
        val dangling = File(dir, "print-dangling.pdf")
        java.nio.file.Files.createSymbolicLink(dangling.toPath(), File(dir, "nowhere.pdf").toPath())
        val refused = listOf(
            src.path,
            File(dir, "./doc.pdf").path,
            File(dir, "../reader-test/doc.pdf").path,
            folder.path,
            link.path,
            dangling.path,
        )
        for (out in refused) {
            try {
                EngineServer.checkReaderPair(root, src.path, out)
                fail("accepted $out")
            } catch (e: SecurityException) {
                // expected
            }
            val r = engine.decryptCopy(src, File(out), null)
            assertTrue("$out: $r", r is PdfResult.Err && r.error is PdfError.Io)
            assertTrue(src.readBytes().contentEquals(bytes))
        }
        assertTrue(folder.isDirectory)
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        // A stale regular file is still accepted (and replaced by the op).
        val stale = File(dir, "print-stale.pdf").apply { writeBytes(byteArrayOf(1)) }
        EngineServer.checkReaderPair(root, src.path, stale.path)
        assertTrue(engine.decryptCopy(src, stale, null) is PdfResult.Ok)
    }

    @Test
    fun theRequestNeverPrintsThePassword() {
        val r = EngineRequest.DecryptCopy("/a.pdf", "/b.pdf", "hunter2")
        assertFalse(r.toString().contains("hunter2"))
        assertEquals(listOf("/a.pdf", "/b.pdf"), r.paths)
    }
}
