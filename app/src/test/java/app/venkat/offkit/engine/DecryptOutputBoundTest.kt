package app.venkat.offkit.engine

import app.venkat.offkit.EncryptedFixtures
import app.venkat.offkit.engine.ops.DecryptCopyOp
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Z3-1: the decrypted print copy is bounded ([DecryptCopyOp.maxOutputBytes]). The engine-side
 * after-save check with real MuPDF here; the kill while writing is `RemoteEngineTest`'s
 * `decryptOutputWatch*` tests.
 */
class DecryptOutputBoundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(tmp.root, "reader-bound").apply { mkdirs() }
        ScanMemo.clear()
    }

    @After
    fun tearDown() = ScanMemo.clear()

    @Test
    fun overlappingStreamsAreCopiedAsTheRestOfTheFileEach() {
        // Small N, so the measured blow-up stays small: about n x filler.
        val src = File(dir, "hostile.pdf").apply { writeBytes(overlappingStreams(n = 40, fillerBytes = 50_000, encrypted = true)) }
        assertTrue(src.length() < 60_000)
        val out = File(dir, "print-big.pdf")
        val r = DecryptCopyOp(outputMarginBytes = 64L shl 20).run(src, out, JobControl.None)
        assertTrue("$r", r is PdfResult.Ok)
        assertTrue("output ${out.length()}", out.length() > 40L * 50_000)
    }

    @Test
    fun anOversizedCopyIsDeletedAndReportedAsTooLarge() {
        val src = File(dir, "hostile.pdf").apply { writeBytes(overlappingStreams(n = 40, fillerBytes = 50_000, encrypted = true)) }
        val out = File(dir, "print-big.pdf")
        // Cap: 2 x ~51 KB + 256 KB, well under the ~2 MB the save writes.
        val r = DecryptCopyOp(outputMarginBytes = 256L shl 10).run(src, out, JobControl.None)
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), r)
        assertFalse(out.exists())
        assertEquals(listOf("hostile.pdf"), dir.list()!!.toList())
    }

    @Test
    fun theEncryptedFixturesStayFarUnderTheCap() {
        for (b64 in listOf(EncryptedFixtures.RC4_B64, EncryptedFixtures.AES256_B64, EncryptedFixtures.OWNER_ONLY_B64)) {
            ScanMemo.clear()
            val src = EncryptedFixtures.write(dir, "f.pdf", b64)
            val out = File(dir, "print-f.pdf")
            val pw = when (b64) {
                EncryptedFixtures.RC4_B64 -> EncryptedFixtures.RC4_USER
                EncryptedFixtures.AES256_B64 -> EncryptedFixtures.AES256_USER
                else -> null
            }
            // The tightest cap that could matter: 2 x input, no margin.
            assertTrue(DecryptCopyOp(outputMarginBytes = 0).run(src, out, JobControl.None, pw) is PdfResult.Ok)
        }
    }

    /** A healthy protected PDF whose objects live in object streams: the copy writes them out plainly. */
    @Test
    fun aProtectedPdfWithALargeObjectStreamStaysUnderTheCap() {
        val src = File(dir, "objstm.pdf")
        // A plain file with 20,000 objects in object streams, then encrypted by MuPDF keeping them there.
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(4)
        val n = 20_000
        val plain = HostilePdf()
            .add(1, "<</Type/Catalog/Pages 2 0 R>>")
            .add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
            .add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<</Junk 4 0 R>>>>")
            .add(4, (0 until n).joinToString(" ", "[", "]") { "${10 + it} 0 R" })
        for (k in 0 until n / 1000) {
            plain.objStm(100_000 + k, (0 until 1000).map { j -> val num = 10 + k * 1000 + j; num to "<</N $num/T($text$num)>>".toByteArray(Charsets.ISO_8859_1) })
        }
        val plainFile = File(tmp.root, "plain-objstm.pdf").apply { writeBytes(plain.bytes()) }
        val doc = Document.openDocument(plainFile.absolutePath) as PDFDocument
        try {
            doc.save(src.absolutePath, "compress,objstms,encrypt=aes-256,owner-password=owner")
        } finally {
            doc.destroy()
        }
        val out = File(dir, "print-objstm.pdf")
        val r = DecryptCopyOp().run(src, out, JobControl.None)
        assertTrue("$r", r is PdfResult.Ok)
        // The object-stream contents are written out uncompressed: several times the input.
        println("objstm: input ${src.length()} B, copy ${out.length()} B")
        assertTrue("input ${src.length()} B, copy ${out.length()} B", out.length() <= 2 * src.length() + (8L shl 20))
        assertTrue(out.length() > 2 * src.length())
        assertTrue(out.length() <= DecryptCopyOp.maxOutputBytes(src.length()))
        val copy = Document.openDocument(out.absolutePath)
        try {
            assertFalse(copy.needsPassword())
        } finally {
            copy.destroy()
        }
    }

    companion object {
        private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)

        /**
         * Review Z3-1's shape: [n] stream objects with `/Length 1` and no `endstream`, followed by
         * [fillerBytes] of filler and a classic xref table (no `endstream` token anywhere), so MuPDF
         * copies each stream from its offset to the end of the file. [encrypted]: the RC4
         * owner-password-only fixture's `/Encrypt` and `/ID` are reused, so it opens with no prompt.
         */
        fun overlappingStreams(n: Int, fillerBytes: Int, encrypted: Boolean): ByteArray {
            var encrypt: String? = null
            var id: String? = null
            if (encrypted) {
                val f = File.createTempFile("owner", ".pdf").apply { writeBytes(EncryptedFixtures.bytes(EncryptedFixtures.OWNER_ONLY_B64)) }
                val d = Document.openDocument(f.absolutePath) as PDFDocument
                try {
                    val trailer = d.trailer
                    val e = trailer.get("Encrypt")
                    val i = trailer.get("ID")
                    encrypt = e.toString(true, true)
                    id = i.toString(true, true)
                    e.destroy()
                    i.destroy()
                    trailer.destroy()
                } finally {
                    d.destroy()
                    f.delete()
                }
            }
            val out = ByteArrayOutputStream()
            out.write("%PDF-1.7\n%âãÏÓ\n".latin1())
            val offs = ArrayList<Int>()
            fun obj(body: String) {
                offs += out.size()
                out.write("${offs.size} 0 obj\n$body\nendobj\n".latin1())
            }
            obj("<</Type/Catalog/Pages 2 0 R>>")
            obj("<</Type/Pages/Kids[3 0 R]/Count 1>>")
            obj("<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>")
            obj(encrypt ?: "null")
            repeat(n) {
                offs += out.size()
                out.write("${offs.size} 0 obj\n<</Length 1>>stream\nX\n".latin1())
            }
            out.write(ByteArray(fillerBytes) { 'A'.code.toByte() })
            out.write("\n".latin1())
            val xref = out.size()
            val sb = StringBuilder("xref\n0 ${offs.size + 1}\n0000000000 65535 f \n")
            for (o in offs) sb.append(String.format("%010d 00000 n \n", o))
            val enc = if (encrypted) "/Encrypt 4 0 R/ID $id" else ""
            sb.append("trailer\n<</Size ${offs.size + 1}/Root 1 0 R$enc>>\nstartxref\n$xref\n%%EOF\n")
            out.write(sb.toString().latin1())
            return out.toByteArray()
        }
    }
}
