package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * SR-4: the engine accepts only files positively identified as JPEG (`FF D8 FF`) or PNG (8-byte
 * signature) and gated; everything else is UnsupportedImage before any MuPDF call. Plain JUnit.
 *
 * Safe by construction: every input is a tiny real image (32×24) that MuPDF itself COULD decode,
 * so a regression shows up as an `Ok` instead of `UnsupportedImage`, never as a memory bomb.
 */
class ImageSniffTest {
    private val engine = MuPdfEngine()

    private fun encoded(format: String): ByteArray {
        val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        assertTrue(format, ImageIO.write(img, format, out))
        return out.toByteArray()
    }

    @Test
    fun nonJpegPngInputs_areUnsupported_everywhere() {
        val jpeg = encoded("jpeg")
        val ffd800 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00) + jpeg.copyOfRange(2, jpeg.size)
        val png = encoded("png")
        val shortPngSig = png.copyOf().also { it[7] = 0x00 } // "\x89PNG\r\n\x1a" + wrong 8th byte
        val inputs = mapOf(
            "ffd800.jpg" to ffd800,
            "gif.jpg" to encoded("gif"),
            "bmp.png" to encoded("bmp"),
            "tiff.jpg" to encoded("tiff"),
            "png-sig.png" to shortPngSig,
            "empty.jpg" to ByteArray(0),
        )
        val base = Fixtures.textPdf(dir, "base.pdf", listOf("Base"))
        for ((name, bytes) in inputs) {
            val f = File(dir, name).also { it.writeBytes(bytes) }
            assertEquals(name, PdfEstimate.Err(PdfError.UnsupportedImage), engine.estimateImage(f))
            for (level in listOf(null, CompressionLevel.HIGH)) {
                val o = File(dir, "o-$name.pdf").also { it.delete() }
                assertEquals("$name $level", PdfResult.Err(PdfError.UnsupportedImage), engine.imagesToPdf(listOf(f), PageSize.FIT_IMAGE, o, compression = level))
            }
            val o = File(dir, "a-$name.pdf").also { it.delete() }
            assertEquals(name, PdfResult.Err(PdfError.UnsupportedImage), engine.appendImages(base, listOf(f), o))
        }
    }

    @Test
    fun realJpegAndPng_stillWork() {
        for ((name, bytes) in mapOf("ok.jpg" to encoded("jpeg"), "ok.png" to encoded("png"))) {
            val f = File(dir, name).also { it.writeBytes(bytes) }
            assertTrue(name, engine.estimateImage(f) is PdfEstimate.Ok)
            val o = File(dir, "o-$name.pdf").also { it.delete() }
            assertTrue(name, engine.imagesToPdf(listOf(f), PageSize.FIT_IMAGE, o) is PdfResult.Ok)
        }
    }

    companion object {
        private lateinit var dir: File

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("image-sniff").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
