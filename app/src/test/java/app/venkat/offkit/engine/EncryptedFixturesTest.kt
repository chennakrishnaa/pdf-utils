package app.venkat.offkit.engine

import app.venkat.offkit.EncryptedFixtures
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Checks the encrypted PDFs checked in as base64 in [EncryptedFixtures] (used by the on-device
 * tests, which must not run MuPDF in the instrumentation process) against real MuPDF on the JVM,
 * and regenerates them.
 *
 * To regenerate: `OFFKIT_REGEN_ENCRYPTED_FIXTURES=1 ./gradlew :app:testDebugUnitTest
 * --tests app.venkat.offkit.engine.EncryptedFixturesTest --no-daemon`, then paste the constants
 * printed to `app/build/encrypted-fixtures.kt.txt` into [EncryptedFixtures]. AES output differs on
 * every run (random salts and IVs); any run's output is valid.
 */
class EncryptedFixturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()

    @Before
    fun setUp() = ScanMemo.clear()

    @After
    fun tearDown() = ScanMemo.clear()

    private fun fixture(name: String, b64: String): File = EncryptedFixtures.write(tmp.root, name, b64)

    private fun assertOpens(pdf: File, password: String?) {
        val ok = engine.pageInfo(pdf, password) as PdfPageInfo.Ok
        assertEquals(listOf(PageDims(EncryptedFixtures.PAGE_PT, EncryptedFixtures.PAGE_PT)), ok.pages)
        assertTrue(ok.encrypted)
        val out = File(tmp.root, "text.bin").also { it.delete() }
        assertTrue(engine.pageText(pdf, 0, out, password) is PdfPageText.Ok)
        val text = (PageTextFormat.read(out) as PageTextParse.Ok).text.text
        assertTrue(text, text.contains(EncryptedFixtures.WORD))
    }

    @Test
    fun aes256NonAsciiUserPassword() {
        val pdf = fixture("aes.pdf", EncryptedFixtures.AES256_B64)
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(pdf, null))
        assertEquals(PdfPageInfo.Err(PdfError.WrongPassword), engine.pageInfo(pdf, "passwörd-é"))
        assertOpens(pdf, EncryptedFixtures.AES256_USER)
    }

    @Test
    fun rc4AsciiUserPassword() {
        val pdf = fixture("rc4.pdf", EncryptedFixtures.RC4_B64)
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(pdf, null))
        assertEquals(PdfPageInfo.Err(PdfError.WrongPassword), engine.pageInfo(pdf, "nope"))
        assertOpens(pdf, EncryptedFixtures.RC4_USER)
    }

    @Test
    fun ownerOnlyOpensWithoutPassword() {
        assertOpens(fixture("owner.pdf", EncryptedFixtures.OWNER_ONLY_B64), null)
    }

    /** U1: AES-128 (V4 R4, /AESV2), written by pypdf rather than MuPDF. */
    @Test
    fun aes128UserPassword() {
        val pdf = fixture("aes128.pdf", EncryptedFixtures.AES128_B64)
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(pdf, null))
        assertEquals(PdfPageInfo.Err(PdfError.WrongPassword), engine.pageInfo(pdf, "aes128-secreT"))
        val out = File(tmp.root, "tile.rgba")
        val req = RenderRequest(0, 1f, 0, 0, 200, 200)
        assertEquals(PdfRender.Err(PdfError.WrongPassword), engine.renderPage(pdf, req, out, "nope"))
        assertOpens(pdf, EncryptedFixtures.AES128_USER)
        assertEquals(PdfRender.Ok(200, 200), engine.renderPage(pdf, req, out, EncryptedFixtures.AES128_USER))
        // The owner password opens it too.
        assertTrue(engine.pageInfo(pdf, "owner-aes128") is PdfPageInfo.Ok)
    }

    @Test
    fun aes128OwnerOnlyOpensWithoutPassword() {
        assertOpens(fixture("aes128-owner.pdf", EncryptedFixtures.AES128_OWNER_ONLY_B64), null)
    }

    @Test
    fun aes128ToolsStillRefuse() {
        val out = File(tmp.root, "out.pdf")
        for (b64 in listOf(EncryptedFixtures.AES128_B64, EncryptedFixtures.AES128_OWNER_ONLY_B64)) {
            val pdf = fixture("tool.pdf", b64)
            assertEquals(PdfResult.Err(PdfError.Encrypted), engine.compress(pdf, CompressionLevel.MEDIUM, out))
            assertEquals(PdfResult.Err(PdfError.Encrypted), engine.merge(listOf(pdf, pdf), out))
            assertTrue(!out.exists())
        }
    }

    // ------------------------------------------------------------------ regeneration

    /** The plaintext one-page PDF: [EncryptedFixtures.WORD] in Helvetica, and a black square. */
    private fun plainPdf(): File {
        val p = EncryptedFixtures.PAGE_PT.toInt()
        val content = "BT /F1 24 Tf 20 120 Td (${EncryptedFixtures.WORD}) Tj ET 0 0 0 rg 20 20 60 60 re f".toByteArray()
        val bodies = listOf(
            "<</Type/Catalog/Pages 2 0 R>>".toByteArray(),
            "<</Type/Pages/Kids[3 0 R]/Count 1>>".toByteArray(),
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 $p $p]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>".toByteArray(),
            "<</Length ${content.size}>>\nstream\n".toByteArray() + content + "\nendstream".toByteArray(),
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>".toByteArray(),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = ArrayList<Int>()
        bodies.forEachIndexed { i, b ->
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(b); out.write("\nendobj\n".toByteArray())
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<</Size ${bodies.size + 1}/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray())
        return File(tmp.root, "plain.pdf").also { it.writeBytes(out.toByteArray()) }
    }

    private fun encrypt(method: String, user: String, owner: String): String {
        val out = File(tmp.root, "enc-$method-${user.isEmpty()}.pdf")
        val doc = Document.openDocument(plainPdf().absolutePath) as PDFDocument
        try {
            doc.save(out.absolutePath, "compress,encrypt=$method,user-password=$user,owner-password=$owner,permissions=-4")
        } finally {
            doc.destroy()
        }
        return Base64.getEncoder().encodeToString(out.readBytes())
    }

    @Test
    fun regenerate() {
        assumeTrue(System.getenv("OFFKIT_REGEN_ENCRYPTED_FIXTURES") == "1")
        fun chunked(name: String, b64: String) =
            "    const val $name =\n" + b64.chunked(96).joinToString(" +\n") { "        \"$it\"" } + "\n"
        val text = chunked("AES256_B64", encrypt("aes-256", EncryptedFixtures.AES256_USER, "owner-aes")) +
            chunked("RC4_B64", encrypt("rc4-128", EncryptedFixtures.RC4_USER, "owner-rc4")) +
            chunked("OWNER_ONLY_B64", encrypt("aes-256", "", "owner-only"))
        File("build/encrypted-fixtures.kt.txt").writeText(text)
        println(text)
    }
}
