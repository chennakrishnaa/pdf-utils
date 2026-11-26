package app.venkat.offkit.engine

import app.venkat.offkit.engine.remote.EngineRequest
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * K1: the reader opens password-protected PDFs (real MuPDF), and every pre-check still holds on
 * encrypted input: object-stream limits on decrypted data, the image gate on decrypted streams.
 */
class EncryptedReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = MuPdfEngine()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.root
        ScanMemo.clear()
    }

    @After
    fun tearDown() = ScanMemo.clear()

    // ------------------------------------------------------------------ builders

    /** A classic-xref PDF from object bodies (object i+1 = bodies[i]); catalog 1, pages 2. */
    private fun rawPdf(name: String, bodies: List<ByteArray>, trailerExtra: String = ""): File {
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
        sb.append("trailer\n<</Size ${bodies.size + 1}/Root 1 0 R$trailerExtra>>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray())
        return File(dir, name).also { it.writeBytes(out.toByteArray()) }
    }

    private fun stream(dict: String, data: ByteArray): ByteArray =
        "<<$dict/Length ${data.size}>>\nstream\n".toByteArray() + data + "\nendstream".toByteArray()

    private val content = "BT /F1 24 Tf 20 100 Td (Hello secret) Tj ET 1 0 0 rg 0 0 50 50 re f".toByteArray()

    /** A one-page text PDF, plus [extraObjects] / [xobjects] for an image (object 6 on). */
    private fun textPdf(name: String, xobjects: String = "", draw: String = "", extraObjects: List<ByteArray> = emptyList()): File =
        rawPdf(
            name,
            listOf(
                "<</Type/Catalog/Pages 2 0 R>>".toByteArray(),
                "<</Type/Pages/Kids[3 0 R]/Count 1>>".toByteArray(),
                "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>$xobjects>>>>".toByteArray(),
                stream("", content + draw.toByteArray()),
                "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>".toByteArray(),
            ) + extraObjects,
        )

    /** [src] saved by MuPDF with encryption [method] (rc4-128 or aes-256). */
    private fun encrypt(src: File, method: String, user: String, owner: String, permissions: Int = -4): File {
        val out = File(dir, "${src.nameWithoutExtension}-$method-${user.ifEmpty { "nouser" }}.pdf")
        val doc = Document.openDocument(src.absolutePath) as PDFDocument
        try {
            doc.save(out.absolutePath, "encrypt=$method,user-password=$user,owner-password=$owner,permissions=$permissions")
        } finally {
            doc.destroy()
        }
        return out
    }

    private fun render(pdf: File, password: String?): PdfRender {
        val out = File(dir, "tile.rgba").also { it.delete() }
        return engine.renderPage(pdf, RenderRequest(0, 1f, 0, 0, 200, 200), out, password)
    }

    private fun text(pdf: File, password: String?): PdfPageText =
        engine.pageText(pdf, 0, File(dir, "text.bin").also { it.delete() }, password)

    // ------------------------------------------------------------------ passwords

    @Test
    fun needsWrongRightPasswordRc4AndAes256() {
        for (method in listOf("rc4-128", "aes-256")) {
            val pdf = encrypt(textPdf("plain.pdf"), method, "user1", "owner1", permissions = -4 and 16.inv())
            assertEquals(method, PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(pdf))
            assertEquals(method, PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(pdf, ""))
            assertEquals(method, PdfPageInfo.Err(PdfError.WrongPassword), engine.pageInfo(pdf, "nope"))
            val ok = engine.pageInfo(pdf, "user1") as PdfPageInfo.Ok
            assertTrue(ok.encrypted)
            assertFalse("copy bit cleared ($method)", ok.canCopy)
            assertTrue(ok.canPrint)
            val owner = engine.pageInfo(pdf, "owner1") as PdfPageInfo.Ok
            assertTrue("owner password reports full permissions", owner.canCopy && owner.canPrint)
            assertTrue(method, render(pdf, "user1") is PdfRender.Ok)
            assertEquals(PdfRender.Err(PdfError.Encrypted), render(pdf, null))
            assertEquals(PdfRender.Err(PdfError.WrongPassword), render(pdf, "x"))
            val t = text(pdf, "user1") as PdfPageText.Ok
            assertTrue("text extracted ($method): ${t.units}", t.units >= "Hello secret".length)
            assertTrue(PageTextFormat.read(File(dir, "text.bin")) is PageTextParse.Ok)
            assertEquals(PdfPageText.Err(PdfError.WrongPassword), text(pdf, "bad"))
        }
    }

    @Test
    fun ownerPasswordOnlyOpensWithoutAPassword() {
        val pdf = encrypt(textPdf("plain.pdf"), "aes-256", "", "owner1", permissions = -4 and 16.inv())
        val ok = engine.pageInfo(pdf) as PdfPageInfo.Ok
        assertTrue(ok.encrypted)
        assertFalse(ok.canCopy)
        assertTrue(render(pdf, null) is PdfRender.Ok)
    }

    @Test
    fun toolsStillRefuseEncryptedInput() {
        val pdf = encrypt(textPdf("plain.pdf"), "rc4-128", "", "owner1")
        val out = File(dir, "out.pdf")
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.compress(pdf, CompressionLevel.MEDIUM, out))
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.merge(listOf(pdf, pdf), out))
        assertFalse(out.exists())
    }

    @Test
    fun passwordCapOnBothSides() {
        assertEquals(null, ReaderPassword.check("a".repeat(127)))
        assertNotNull(ReaderPassword.check("a".repeat(128)))
        assertNotNull(ReaderPassword.check("é".repeat(64))) // 128 bytes of UTF-8
        assertNotNull(ReaderPassword.check("a\u0000b"))
        val pdf = encrypt(textPdf("plain.pdf"), "aes-256", "user1", "owner1")
        assertTrue((engine.pageInfo(pdf, "a".repeat(128)) as PdfPageInfo.Err).error is PdfError.Io)
    }

    @Test
    fun requestsNeverPrintThePassword() {
        val r = RenderRequest(0, 1f, 0, 0, 1, 1)
        for (req in listOf(EngineRequest.PageInfo("/p", "hunter2"), EngineRequest.RenderPage("/p", r, "/o", "hunter2"), EngineRequest.PageText("/p", 0, "/o", "hunter2"))) {
            assertFalse(req.toString(), req.toString().contains("hunter2"))
        }
        assertFalse(ScanMemo.Key("/p", 1, 1, 1, ScanMemo.authTag("hunter2")).toString().contains("hunter2"))
        assertTrue(ScanMemo.authTag("a") != ScanMemo.authTag("b") && ScanMemo.authTag(null) == 0L)
    }

    // ------------------------------------------------------------------ hostile, encrypted

    @Test
    fun imageGateStillRefusesOnEncryptedFiles() {
        // Declared size over the limit, and a JPEG whose header cannot be read: refused with the password.
        val huge = stream("/Type/XObject/Subtype/Image/Width 60000/Height 60000/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/FlateDecode", deflate(ByteArray(16)))
        val badJpeg = stream("/Type/XObject/Subtype/Image/Width 10/Height 10/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode", ByteArray(64) { 0x41 })
        for ((label, img) in listOf("huge" to huge, "jpeg" to badJpeg)) {
            val plain = textPdf("$label.pdf", "/XObject<</Im0 6 0 R>>", " q 10 0 0 10 0 0 cm /Im0 Do Q", listOf(img))
            for (method in listOf("rc4-128", "aes-256")) {
                val pdf = encrypt(plain, method, "user1", "owner1")
                assertEquals("$label $method render", PdfRender.Err(PdfError.OutOfMemory), render(pdf, "user1"))
                assertEquals("$label $method text", PdfPageText.Err(PdfError.OutOfMemory), text(pdf, "user1"))
            }
        }
    }

    @Test
    fun benignEncryptedJpegPassesTheGateOnDecryptedData() {
        val jpegFile = app.venkat.offkit.Fixtures.jpeg(dir, width = 40, height = 30)
        val jpeg = jpegFile.readBytes()
        val img = stream("/Type/XObject/Subtype/Image/Width 40/Height 30/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode", jpeg)
        val plain = textPdf("photo.pdf", "/XObject<</Im0 6 0 R>>", " q 100 0 0 75 0 0 cm /Im0 Do Q", listOf(img))
        assertTrue(render(plain, null) is PdfRender.Ok)
        for (method in listOf("rc4-128", "aes-256")) {
            ScanMemo.clear()
            assertTrue(method, render(encrypt(plain, method, "user1", "owner1"), "user1") is PdfRender.Ok)
        }
    }

    @Test
    fun objectStreamBombIsRefusedAfterDecryption() {
        val small = encryptedObjStmPdf("small.pdf", padding = 1000)
        assertTrue("hand-made RC4 file is valid", engine.pageInfo(small, "user1") is PdfPageInfo.Ok)
        assertEquals(PdfPageInfo.Err(PdfError.WrongPassword), engine.pageInfo(small, "bad"))
        val bomb = encryptedObjStmPdf("bomb.pdf", padding = 17 shl 20)
        // Pass 1 sees only ciphertext: the raw scan alone lets it through.
        ObjStmScan.check(bomb)
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb, "user1"))
        assertEquals(PdfRender.Err(PdfError.OutOfMemory), render(bomb, "user1"))
        assertEquals(PdfPageText.Err(PdfError.OutOfMemory), text(bomb, "user1"))
    }

    /** K7-2: pass 2 reads the filter as `Filter|F` too. */
    @Test
    fun objectStreamBombWithAbbreviatedFilterKeyIsRefusedAfterDecryption() {
        val small = encryptedObjStmPdf("small-f.pdf", padding = 1000, filterKey = "F")
        assertTrue("benign /F object stream opens", engine.pageInfo(small, "user1") is PdfPageInfo.Ok)
        val bomb = encryptedObjStmPdf("bomb-f.pdf", padding = 17 shl 20, filterKey = "F")
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb, "user1"))
        assertEquals(PdfRender.Err(PdfError.OutOfMemory), render(bomb, "user1"))
    }

    /** M2-2: a nested dictionary's `/Filter` does not hide the outer one from pass 1 (so pass 2 measures it). */
    @Test
    fun objectStreamBombWithNestedFilterKeyIsRefusedAfterDecryption() {
        val small = encryptedObjStmPdf("small-nested.pdf", padding = 1000, dictTail = "/DecodeParms<</Columns 4>>/X<</Filter[]>>")
        assertTrue("benign nested object stream opens", engine.pageInfo(small, "user1") is PdfPageInfo.Ok)
        val bomb = encryptedObjStmPdf("bomb-nested.pdf", padding = 17 shl 20, dictTail = "/X<</Filter[]>>")
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(bomb, "user1"))
        assertEquals(PdfRender.Err(PdfError.OutOfMemory), render(bomb, "user1"))
    }

    /** K7-3: a repair triggered while pass 2 resolves the object streams refuses the document. */
    @Test
    fun repairDuringObjectStreamPass2IsRefused() {
        val broken = encryptedObjStmPdf("broken-xref.pdf", padding = 1000, objStmOffsetSkew = 3)
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), engine.pageInfo(broken, "user1"))
    }

    // ------------------------------------------------------------------ RC4 (V2 R3) by hand

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun md5(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("MD5").run { parts.forEach { update(it) }; digest() }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("ARCFOUR").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ARCFOUR")); doFinal(data) }

    private val pad = intArrayOf(
        0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
    ).map { it.toByte() }.toByteArray()

    private fun padded(pw: String) = (pw.toByteArray(Charsets.ISO_8859_1) + pad).copyOf(32)

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02X", it) }

    /**
     * An RC4-128 (V2 R3) PDF whose catalog and page tree live in a Flate object stream (object 1)
     * padded with [padding] spaces after its objects, with an xref stream (object 5).
     */
    private fun encryptedObjStmPdf(name: String, padding: Int, filterKey: String = "Filter", objStmOffsetSkew: Int = 0, dictTail: String = ""): File {
        val id = ByteArray(16) { it.toByte() }
        val p = -4
        // Algorithm 3: O
        var ok = md5(padded("owner1"))
        repeat(50) { ok = md5(ok) }
        var o = rc4(ok, padded("user1"))
        for (i in 1..19) o = rc4(ByteArray(16) { (ok[it].toInt() xor i).toByte() }, o)
        // Algorithm 2: file key
        val pb = byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())
        var key = md5(padded("user1"), o, pb, id)
        repeat(50) { key = md5(key) }
        // Algorithm 5: U
        var u = rc4(key, md5(pad, id))
        for (i in 1..19) u = rc4(ByteArray(16) { (key[it].toInt() xor i).toByte() }, u)
        u += ByteArray(16)
        fun objKey(num: Int) = md5(key, byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), 0, 0))

        val objs = "<</Type/Catalog/Pages 3 0 R>> <</Type/Pages/Kids[4 0 R]/Count 1>> "
        val header = "2 0 3 ${"<</Type/Catalog/Pages 3 0 R>> ".length} "
        val plain = (header + objs).toByteArray() + ByteArray(padding) { ' '.code.toByte() }
        val objStmData = rc4(objKey(1), deflate(plain))

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val off = IntArray(7)
        fun obj(num: Int, body: ByteArray) {
            off[num] = out.size()
            out.write("$num 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray())
        }
        obj(1, stream("/Type/ObjStm/N 2/First ${header.length}/$filterKey/FlateDecode$dictTail", objStmData))
        obj(4, "<</Type/Page/Parent 3 0 R/MediaBox[0 0 200 200]>>".toByteArray())
        obj(6, "<</Filter/Standard/V 2/R 3/Length 128/P $p/O<${hex(o)}>/U<${hex(u)}>>>".toByteArray())
        off[5] = out.size()
        val rows = ByteArrayOutputStream()
        fun row(t: Int, a: Int, b: Int) {
            rows.write(t); rows.write(byteArrayOf((a shr 24).toByte(), (a shr 16).toByte(), (a shr 8).toByte(), a.toByte())); rows.write(b shr 8); rows.write(b)
        }
        row(0, 0, 65535); row(1, off[1] + objStmOffsetSkew, 0); row(2, 1, 0); row(2, 1, 1); row(1, off[4], 0); row(1, off[5], 0); row(1, off[6], 0)
        val xref = rows.toByteArray()
        obj(5, stream("/Type/XRef/Size 7/W[1 4 2]/Root 2 0 R/Encrypt 6 0 R/ID[<${hex(id)}><${hex(id)}>]", xref))
        out.write("startxref\n${off[5]}\n%%EOF\n".toByteArray())
        return File(dir, name).also { it.writeBytes(out.toByteArray()) }
    }
}
