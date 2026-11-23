package app.venkat.paperpixel.spike

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class SpikeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun textPdf(name: String, pages: List<String>, link: Pair<Rect, String>? = null): File {
        val f = tmp.newFile(name)
        Native().use { n ->
            val doc = n.add(PDFDocument())
            for (p in pages) addTextPage(doc, listOf(p))
            if (link != null) {
                val page = n.add(doc.loadPage(0))
                n.add(page.createLink(link.first, link.second))
            }
            doc.save(f.absolutePath, SAVE_OPTS)
        }
        return f
    }

    /* ---------------- Recipe 3 ---------------- */
    @Test
    fun recipe3_textRoundTrip() {
        val f = textPdf("t.pdf", listOf("Hello Spike 123"))
        Native().use { n ->
            val doc = n.add(Document.openDocument(f.absolutePath))
            assertTrue(pageText(doc, 0).contains("Hello Spike 123"))
        }
    }

    /* ---------------- Recipe 5 ---------------- */
    @Test
    fun recipe5_mergeByGrafting() {
        val a = textPdf("a.pdf", listOf("Alpha one", "Alpha two"))
        val b = textPdf("b.pdf", listOf("Bravo one", "Bravo two", "Bravo three"))
        val out = tmp.newFile("merged.pdf")
        Native().use { n ->
            val dst = n.add(PDFDocument())
            for (f in listOf(a, b)) {
                val src = Document.openDocument(f.absolutePath) as PDFDocument
                val map = dst.newPDFGraftMap() // one map per source document
                for (i in 0 until src.countPages()) map.graftPage(-1, src, i)
                map.destroy()
                src.destroy() // sources may be destroyed BEFORE dst.save: graft copies streams eagerly
            }
            dst.save(out.absolutePath, SAVE_OPTS)
        }
        Native().use { n ->
            val doc = n.add(Document.openDocument(out.absolutePath))
            assertEquals(5, doc.countPages())
            val expect = listOf("Alpha one", "Alpha two", "Bravo one", "Bravo two", "Bravo three")
            for (i in 0 until 5) assertTrue("page $i", pageText(doc, i).contains(expect[i]))
        }
    }

    /* ---------------- Recipe 6 ---------------- */
    @Test
    fun recipe6_linkLostOnGraftThenRecreated() {
        val uri = "https://example.com/spike?x=1"
        val rect = Rect(72f, 100f, 272f, 140f) // fitz space: origin top-left, y down
        val src = textPdf("l.pdf", listOf("Linked page"), rect to uri)
        val grafted = tmp.newFile("grafted.pdf")
        val fixed = tmp.newFile("fixed.pdf")
        Native().use { n ->
            val s = n.add(Document.openDocument(src.absolutePath) as PDFDocument)
            val sp = n.add(s.loadPage(0))
            val links = sp.links
            assertEquals("source really has the link", 1, links.size)
            assertEquals(uri, links[0].uri)
            println("recipe6 source link bounds=${links[0].bounds}")

            val dst = n.add(PDFDocument())
            val map = n.add(dst.newPDFGraftMap())
            map.graftPage(-1, s, 0)
            dst.save(grafted.absolutePath, SAVE_OPTS)
            Native().use { m ->
                val g = m.add(Document.openDocument(grafted.absolutePath))
                val gp = m.add(g.loadPage(0))
                assertEquals("graft drops /Annots -> links gone", 0, (gp.links ?: emptyArray()).size)
            }
            // re-create on the destination page
            val dp = n.add(dst.loadPage(0))
            for (l in links) { n.add(dp.createLink(l.bounds, l.uri)); l.destroy() }
            dst.save(fixed.absolutePath, SAVE_OPTS)
        }
        Native().use { n ->
            val d = n.add(Document.openDocument(fixed.absolutePath))
            val p = n.add(d.loadPage(0))
            val links = p.links
            assertEquals(1, links.size)
            assertEquals(uri, links[0].uri)
            val b = links[0].bounds
            for ((x, y) in listOf(b.x0 to rect.x0, b.y0 to rect.y0, b.x1 to rect.x1, b.y1 to rect.y1))
                assertTrue("$b vs $rect", abs(x - y) < 0.01f)
            links.forEach { it.destroy() }
        }
    }

    /* ---------------- Recipe 4 ---------------- */
    @Test
    fun recipe4_encryptionAndCorruptInputs() {
        val plain = textPdf("p.pdf", listOf("Secret text"))
        val enc = tmp.newFile("enc.pdf")
        Native().use { n ->
            val doc = n.add(Document.openDocument(plain.absolutePath) as PDFDocument)
            doc.save(enc.absolutePath, "garbage=deduplicate,compress,encrypt=aes-256,user-password=userpw,owner-password=ownerpw")
        }
        Native().use { n ->
            val doc = n.add(Document.openDocument(enc.absolutePath))
            assertTrue(doc.needsPassword())
            println("recipe4 encrypted countPages before auth: " + outcome { doc.countPages() })
            println("recipe4 encrypted loadPage before auth: " + outcome { doc.loadPage(0).also { it.destroy() } })
            println("recipe4 encrypted graft before auth: " + outcome {
                val dst = PDFDocument(); try { dst.graftPage(-1, doc as PDFDocument, 0) } finally { dst.destroy() } })
            assertFalse(doc.authenticatePassword("wrong"))
            assertTrue(doc.authenticatePassword("userpw"))
            assertTrue(pageText(doc, 0).contains("Secret text"))
        }
        val results = LinkedHashMap<String, String>()
        fun probe(label: String, f: File) {
            results[label] = outcome {
                val d = Document.openDocument(f.absolutePath)
                try {
                    val pages = d.countPages()
                    val p = d.loadPage(0); val t = p.toStructuredText().asText(); p.destroy()
                    "pages=$pages repaired=${(d as? PDFDocument)?.wasRepaired()} needsPw=${d.needsPassword()} text=${t.trim().take(20)}"
                } finally { d.destroy() }
            }
        }
        val garbage = ByteArray(4096).also { kotlin.random.Random(1).nextBytes(it) }
        probe("garbage.pdf", tmp.newFile("garbage.pdf").writeAndGet(garbage))
        probe("garbage.bin", tmp.newFile("garbage.bin").writeAndGet(garbage))
        probe("empty.pdf", tmp.newFile("empty.pdf"))
        val good = plain.readBytes()
        probe("truncated50.pdf", tmp.newFile("truncated50.pdf").writeAndGet(good.copyOf(good.size / 2)))
        probe("truncated90.pdf", tmp.newFile("truncated90.pdf").writeAndGet(good.copyOf(good.size * 9 / 10)))
        probe("headerOnly.pdf", tmp.newFile("headerOnly.pdf").writeAndGet("%PDF-1.7\n".toByteArray()))
        results["Image(garbage)"] = outcome { Image(garbage).also { it.destroy() } }
        results["Image(pdfBytes)"] = outcome { Image(good).also { it.destroy() } }
        results["Image(empty)"] = outcome { Image(ByteArray(0)).also { it.destroy() } }
        results["openDocument(missing)"] = outcome { Document.openDocument(File(tmp.root, "nope.pdf").absolutePath) }
        for ((k, v) in results) println("recipe4 $k -> $v")
        assertEquals("java.lang.RuntimeException: no objects found", results["garbage.pdf"])
        assertEquals("java.lang.RuntimeException: no objects found", results["empty.pdf"])
        assertTrue(results["garbage.bin"]!!.startsWith("java.lang.RuntimeException: cannot find document handler for file"))
        // truncated PDFs are REPAIRED silently: may open with 0 pages (loadPage then throws
        // IllegalArgumentException from the Java side) or open fine with wasRepaired()=true.
        assertEquals("java.lang.IllegalArgumentException: page number out of range", results["truncated50.pdf"])
        assertTrue(results["truncated90.pdf"]!!.contains("repaired=true"))
        assertEquals("java.lang.RuntimeException: unknown image file format", results["Image(garbage)"])
        assertEquals("java.lang.RuntimeException: unknown image file format", results["Image(pdfBytes)"])
    }

    /* ---------------- Recipe 1 ---------------- */
    @Test
    fun recipe1_jpegEmbeddedUnchanged_pngWithAlpha() {
        val jpeg = toJpeg(ppm(300, 200) { x, y -> (x shl 16) or (y shl 8) or 0x40 }, 85)
        val png = toPng(pam(60, 40, 4, "RGB_ALPHA") { x, y -> intArrayOf(200, x * 4, y * 6, x * 4) })
        val f = tmp.newFile("img.pdf")
        Native().use { n ->
            val doc = n.add(PDFDocument())
            for ((bytes, w, h) in listOf(Triple(jpeg, 300f, 200f), Triple(png, 60f, 40f))) {
                val img = n.add(Image(bytes))
                val ref = n.add(doc.addImage(img))
                addImagePage(doc, ref, w, h)
            }
            doc.save(f.absolutePath, SAVE_OPTS)
        }
        Native().use { n ->
            val doc = n.add(openPdf(f))
            val im = n.add(pageImage(doc, 0))
            assertEquals("DCTDecode", n.add(im.get("Filter")).asName())
            assertArrayEquals(jpeg, im.readRawStream())
            val pim = n.add(pageImage(doc, 1))
            val smask = n.add(pim.get("SMask"))
            println("recipe1 png filter=${n.add(pim.get("Filter"))} cs=${n.add(pim.get("ColorSpace")).toString(true)} " +
                "bpc=${n.add(pim.get("BitsPerComponent"))} decodeParms=${n.add(pim.get("DecodeParms"))} smask=$smask " +
                "smaskFilter=${n.add(smask.get("Filter"))} smaskCS=${n.add(smask.get("ColorSpace"))}")
            assertTrue(smask.isIndirect)
            assertEquals("FlateDecode", n.add(pim.get("Filter")).asName())
        }
    }

    /* ---------------- Recipe 2 ---------------- */
    @Test
    fun recipe2_exifOrientation_allEight() {
        // raw 200x100: red block at raw top-left, green block at raw top-right, rest white
        val raw = ppm(200, 100) { x, y ->
            when {
                x < 50 && y < 50 -> 0xFF0000
                x >= 150 && y < 50 -> 0x00FF00
                else -> 0xFFFFFF
            }
        }
        val base = toJpeg(raw, 95)
        val exifToMupdf = mapOf(1 to 1, 2 to 5, 3 to 3, 4 to 7, 5 to 6, 6 to 4, 7 to 8, 8 to 2)
        // expected displayed corners of (red, green): TL TR BL BR
        val expected = mapOf(1 to ("TL" to "TR"), 2 to ("TR" to "TL"), 3 to ("BR" to "BL"), 4 to ("BL" to "BR"),
            5 to ("TL" to "BL"), 6 to ("TR" to "BR"), 7 to ("BR" to "TR"), 8 to ("BL" to "TL"))
        for (exif in 1..8) Native().use { n ->
            val img = n.add(Image(withExifOrientation(base, exif)))
            val o = img.orientation
            assertEquals("exif $exif", exifToMupdf[exif], o)
            assertEquals(200, img.width) // width/height are the STORED (unrotated) dims
            val (pw, ph) = if (isQuarterTurn(o)) img.height.toFloat() to img.width.toFloat() else img.width.toFloat() to img.height.toFloat()
            val doc = n.add(PDFDocument())
            val ref = n.add(doc.addImage(img))
            addImagePage(doc, ref, pw, ph, uprightCm(o, pw, ph))
            val f = tmp.newFile("o$exif.pdf")
            doc.save(f.absolutePath, SAVE_OPTS)
            val d2 = n.add(Document.openDocument(f.absolutePath))
            val page = n.add(d2.loadPage(0))
            val pix = n.add(page.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false))
            assertEquals(pw.toInt(), pix.width); assertEquals(ph.toInt(), pix.height)
            val w = pix.width; val h = pix.height
            val corners = mapOf("TL" to rgbAt(pix, 10, 10), "TR" to rgbAt(pix, w - 11, 10),
                "BL" to rgbAt(pix, 10, h - 11), "BR" to rgbAt(pix, w - 11, h - 11))
            fun isRed(c: IntArray) = c[0] > 200 && c[1] < 60 && c[2] < 60
            fun isGreen(c: IntArray) = c[1] > 200 && c[0] < 60 && c[2] < 60
            val red = corners.filterValues(::isRed).keys
            val green = corners.filterValues(::isGreen).keys
            assertEquals("exif $exif red", setOf(expected[exif]!!.first), red)
            assertEquals("exif $exif green", setOf(expected[exif]!!.second), green)
        }
    }

    /* ---------------- Recipe 8 ---------------- */
    @Test
    fun recipe8_documentUsedOnAnotherThread() {
        val f = textPdf("th.pdf", listOf("Thread text"))
        var doc: PDFDocument? = null
        val t1 = Thread { doc = Document.openDocument(f.absolutePath) as PDFDocument }
        t1.start(); t1.join()
        var text = ""
        var err: Throwable? = null
        val t2 = Thread {
            try {
                text = pageText(doc!!, 0)
                doc!!.destroy()
            } catch (t: Throwable) { err = t }
        }
        t2.start(); t2.join()
        if (err != null) throw err!!
        assertTrue(text.contains("Thread text"))
    }
}
