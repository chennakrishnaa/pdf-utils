package app.venkat.offkit.engine

import app.venkat.offkit.Fixtures
import app.venkat.offkit.engine.CompressFilterGateTest.Companion.deflate
import app.venkat.offkit.engine.CompressFilterGateTest.Companion.imagePage
import app.venkat.offkit.engine.ops.ColorSpaceGate
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * SR2-1: Compress vets an image's colour space ([ColorSpaceGate]) before `loadImage`, because
 * MuPDF inflates an `/ICCBased` profile stream completely and loads Indexed lookups, tint
 * functions and `/Alternate`s without bounds. Plain JUnit with real MuPDF.
 *
 * Safe by construction: the "bomb" profiles here decode to 5 MiB (just over the 4 MiB bound), so a
 * regressed gate costs a few MB and shows as a wrong result (image replaced), not a crash. The real
 * 1 GB shapes (`a-icc-bomb*.pdf`) were run only in a capped child JVM.
 */
class CompressColorSpaceGateTest {
    private val engine = MuPdfEngine()

    private val w = 300
    private val h = 300
    private val pixels = ByteArray(w * h * 3).also { Random(11).nextBytes(it) }

    private fun image(colorSpace: String, extra: List<Pair<String, ByteArray?>> = emptyList()): ByteArray =
        imagePage("<< /Type /XObject /Subtype /Image /Width $w /Height $h /BitsPerComponent 8 /ColorSpace $colorSpace >>", pixels, extra)

    private val bigProfile = deflate(ByteArray(ColorSpaceGate.MAX_PROFILE_DECODED_BYTES + (1 shl 20)))
    private val smallProfile = deflate(ByteArray(2048) { (it % 251).toByte() })

    @Test
    fun boundedInflate_tripsJustOverTheLimit() {
        val limit = 100_000
        assertTrue(ColorSpaceGate.decodedSizeWithin(deflate(ByteArray(limit)), limit))
        assertFalse(ColorSpaceGate.decodedSizeWithin(deflate(ByteArray(limit + 1)), limit))
        assertFalse(ColorSpaceGate.decodedSizeWithin(byteArrayOf(1, 2, 3, 4, 5), limit)) // not zlib
        // A 1 GB-shaped stream is refused after inflating only limit bytes: fast.
        val t0 = System.nanoTime()
        assertFalse(ColorSpaceGate.decodedSizeWithin(bigProfile, ColorSpaceGate.MAX_PROFILE_DECODED_BYTES))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
    }

    @Test
    fun oversizedOrUnvettedColourSpaces_areSkippedUntouched() {
        val cases = mapOf(
            "icc-inflates-5MiB" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Filter /FlateDecode >>" to bigProfile)),
            "icc-alternate-is-icc" to image(
                "[/ICCBased 6 0 R]",
                listOf("<< /N 3 /Alternate [/ICCBased 7 0 R] >>" to ByteArray(16), "<< /N 3 /Filter /FlateDecode >>" to smallProfile),
            ),
            "icc-double-flate" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Filter [/FlateDecode /FlateDecode] >>" to deflate(smallProfile))),
            "icc-flate-abbrev-array-ok-but-decodeparms" to image(
                "[/ICCBased 6 0 R]",
                listOf("<< /N 3 /Filter /Fl /DecodeParms << /Predictor 1 >> >>" to smallProfile),
            ),
            "icc-metadata" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Filter /FlateDecode /Metadata 7 0 R >>" to smallProfile, "<< >>" to ByteArray(4))),
            "icc-indirect-length" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Length 7 0 R >>" to ByteArray(16), "16" to null)),
            "icc-n4" to image("[/ICCBased 6 0 R]", listOf("<< /N 4 >>" to ByteArray(16))),
            "icc-direct-dict" to image("[/ICCBased << /N 3 >>]"),
            "bare-stream" to image("6 0 R", listOf("<< /N 3 >>" to ByteArray(16))),
            "indexed" to image("[/Indexed /DeviceRGB 255 6 0 R]", listOf("<< >>" to ByteArray(768))),
            "separation" to image("[/Separation /Spot /DeviceRGB 6 0 R]", listOf("<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0] /C1 [1 1 1] /N 1 >>" to null)),
            "cmyk" to image("/DeviceCMYK"),
            "lab" to image("[/Lab << /WhitePoint [1 1 1] >>]"),
            "resource-name" to image("/CS0"),
            "calrgb-indirect-dict" to image("[/CalRGB 6 0 R]", listOf("<< /WhitePoint [0.95 1 1.09] >>" to null)),
            "calrgb-extra-element" to image("[/CalRGB << /WhitePoint [0.95 1 1.09] >> /X]"),
        )
        for ((name, bytes) in cases) {
            val pdf = write("$name.pdf", bytes)
            val o = out("$name-out.pdf")
            val r = fast(name) { engine.compress(pdf, CompressionLevel.HIGH, o) }
            assertTrue("$name: $r", r is PdfResult.Ok)
            // The save may Flate an unfiltered stream; the decoded samples must be unchanged.
            assertArrayEquals("$name: image untouched", pixels, withImage(o) { it.readStream() })
        }
    }

    @Test
    fun vettedColourSpaces_areStillRecompressed() {
        val cases = mapOf(
            "device-rgb" to image("/DeviceRGB"),
            "rgb-abbrev" to image("/RGB"),
            "indirect-name" to image("6 0 R", listOf("/DeviceRGB" to null)),
            "calrgb" to image("[/CalRGB << /WhitePoint [0.9505 1 1.089] >>]"),
            "icc-flate" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Filter /FlateDecode >>" to smallProfile)),
            "icc-raw-alt" to image("[/ICCBased 6 0 R]", listOf("<< /N 3 /Alternate /DeviceRGB /Range [0 1 0 1 0 1] >>" to ByteArray(2048))),
            "icc-indirect-array" to image("6 0 R", listOf("[/ICCBased 7 0 R]" to null, "<< /N 3 /Filter [/FlateDecode] >>" to smallProfile)),
        )
        for ((name, bytes) in cases) {
            val pdf = write("$name.pdf", bytes)
            val o = out("$name-out.pdf")
            val r = fast(name) { engine.compress(pdf, CompressionLevel.HIGH, o) }
            assertTrue("$name: $r", r is PdfResult.Ok)
            assertTrue("$name: smaller", (r as PdfResult.Ok).bytes < pdf.length())
            assertEquals("$name: replaced", "DCTDecode", withImage(o) { it.get("Filter").asName() })
        }
    }

    @Test
    fun ordinaryIccPhoto_isStillRecompressed() {
        // MuPDF's own addImage writes an RGB PNG as Flate + [/ICCBased <sRGB profile>]: the common real-world shape.
        val png = Fixtures.png(dir, "icc.png", width = 1600, height = 1200)
        val pdf = File(dir, "icc-photo.pdf")
        val doc = PDFDocument()
        try {
            val img = com.artifex.mupdf.fitz.Image(png.absolutePath)
            val ref = doc.addImage(img)
            val res = doc.newDictionary()
            val xo = doc.newDictionary()
            xo.put("Im0", ref)
            res.put("XObject", xo)
            doc.insertPage(-1, doc.addPage(com.artifex.mupdf.fitz.Rect(0f, 0f, 1600f, 1200f), 0, res, "q 1600 0 0 1200 0 0 cm /Im0 Do Q"))
            img.destroy()
            doc.save(pdf.absolutePath, "compress")
        } finally {
            doc.destroy()
        }
        assertEquals("ICCBased", withImage(pdf) { it.get("ColorSpace").get(0).asName() })
        val o = out("icc-photo-out.pdf")
        val r = engine.compress(pdf, CompressionLevel.HIGH, o)
        assertTrue("$r", r is PdfResult.Ok)
        assertTrue("smaller", (r as PdfResult.Ok).bytes < pdf.length() / 2)
        assertEquals("DCTDecode", withImage(o) { it.get("Filter").asName() })
    }

    /* ------------------------------------------------------------------ helpers */

    private inline fun <T> fast(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms", ms < 2000)
        return r
    }

    /** Runs [block] on the largest image XObject of [file] (the page image, not a profile). */
    private fun <T> withImage(file: File, block: (PDFObject) -> T): T {
        val doc = Document.openDocument(file.absolutePath) as PDFDocument
        try {
            for (num in 1 until doc.countObjects()) {
                val obj = doc.newIndirect(num, 0)
                try {
                    if (obj.isStream && obj.get("Subtype").let { it.isName && it.asName() == "Image" }) return block(obj)
                } finally {
                    obj.destroy()
                }
            }
            error("no image in ${file.name}")
        } finally {
            doc.destroy()
        }
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    private fun out(name: String) = File(dir, name).also { it.delete() }

    companion object {
        private lateinit var dir: File

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("compress-cs-gate").toFile()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }
}
