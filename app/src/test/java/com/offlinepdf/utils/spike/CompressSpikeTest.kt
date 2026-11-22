package com.offlinepdf.utils.spike

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What the compressor should do with one image XObject. */
enum class Action { RECOMPRESS_RGB, RECOMPRESS_GRAY, SKIP }

data class Verdict(val action: Action, val reason: String)

/**
 * Recipe 7: classify an image XObject dictionary. [maskTargets] = object numbers referenced
 * as another image's /SMask or /Mask (those look like plain 8-bit gray images but must not be
 * touched independently).
 */
fun classify(doc: PDFDocument, num: Int, dict: PDFObject, maskTargets: Set<Int>): Verdict {
    if (num in maskTargets) return Verdict(Action.SKIP, "is SMask/Mask of another image")
    Native().use { n ->
        // Missing keys come back as PDFObject.Null (isNull == true), never Kotlin null.
        val imageMask = n.add(dict.get("ImageMask"))
        if (imageMask.isBoolean && imageMask.asBoolean()) return Verdict(Action.SKIP, "ImageMask")
        if (!n.add(dict.get("SMask")).isNull) return Verdict(Action.SKIP, "SMask")
        val mask = n.add(dict.get("Mask"))
        if (!mask.isNull) {
            val r = n.add(mask.resolve())
            return Verdict(Action.SKIP, if (r.isArray) "Mask colour-key array" else "Mask stencil stream")
        }
        val filters = ArrayList<String>()
        val filter = n.add(dict.get("Filter")).let { n.add(it.resolve()) }
        if (filter.isName) filters.add(filter.asName())
        else if (filter.isArray) for (i in 0 until filter.size()) filters.add(n.add(filter.get(i)).asName())
        for (bad in listOf("JBIG2Decode", "CCITTFaxDecode", "CCF", "JPXDecode"))
            if (bad in filters) return Verdict(Action.SKIP, "Filter $bad")
        val bpc = n.add(dict.get("BitsPerComponent")).asInteger()
        if (bpc in 1..7) return Verdict(Action.SKIP, "BitsPerComponent $bpc")
        val cs = n.add(n.add(dict.get("ColorSpace")).resolve())
        val family = when {
            cs.isName -> cs.asName()
            cs.isArray -> n.add(cs.get(0)).asName()
            else -> ""
        }
        return when (family) {
            "DeviceRGB", "CalRGB" -> Verdict(Action.RECOMPRESS_RGB, family)
            "DeviceGray", "CalGray" -> Verdict(Action.RECOMPRESS_GRAY, family)
            "ICCBased" -> {
                val nComp = n.add(n.add(n.add(cs.get(1)).resolve()).get("N")).asInteger()
                when (nComp) {
                    1 -> Verdict(Action.RECOMPRESS_GRAY, "ICCBased N=1")
                    3 -> Verdict(Action.RECOMPRESS_RGB, "ICCBased N=3")
                    else -> Verdict(Action.SKIP, "ICCBased N=$nComp")
                }
            }
            // DeviceCMYK, Indexed, Separation, DeviceN, Lab, Pattern, missing: skip.
            else -> Verdict(Action.SKIP, "ColorSpace ${family.ifEmpty { "missing" }}")
        }
    }
}

/** Collects object numbers used as /SMask or /Mask (stream) by any image. */
fun collectMaskTargets(doc: PDFDocument): Set<Int> {
    val out = HashSet<Int>()
    for (num in 1 until doc.countObjects()) Native().use { n ->
        // NB: isStream()/readRawStream() only work on the INDIRECT ref, not on resolve()d dicts.
        val obj = n.add(doc.newIndirect(num, 0))
        if (!obj.isStream) return@use
        if (n.add(obj.get("Subtype")).let { !it.isName || it.asName() != "Image" }) return@use
        for (k in listOf("SMask", "Mask")) {
            val v = n.add(obj.get(k))
            if (v.isIndirect) out.add(v.asIndirect())
        }
    }
    return out
}

/**
 * Recipe 7: downscale + re-encode every eligible image in place.
 * Returns map objNum -> verdict.
 */
fun compressImages(doc: PDFDocument, maxDim: Int, quality: Int): Map<Int, Verdict> {
    val masks = collectMaskTargets(doc)
    val result = LinkedHashMap<Int, Verdict>()
    for (num in 1 until doc.countObjects()) Native().use { n ->
        val obj = n.add(doc.newIndirect(num, 0)) // keep the indirect ref: isStream() is false on resolve()d
        if (!obj.isStream) return@use
        if (n.add(obj.get("Subtype")).let { !it.isName || it.asName() != "Image" }) return@use
        val v = classify(doc, num, obj, masks)
        result[num] = v
        if (v.action == Action.SKIP) return@use

        val img = n.add(doc.loadImage(obj))
        val scale = minOf(1.0, maxDim.toDouble() / max(img.width, img.height))
        val nw = max(1, (img.width * scale).roundToInt())
        val nh = max(1, (img.height * scale).roundToInt())
        val cs = if (v.action == Action.RECOMPRESS_GRAY) ColorSpace.DeviceGray else ColorSpace.DeviceRGB
        val pix = n.add(Pixmap(cs, nw, nh, false))
        pix.clear(255)
        val dev = n.add(DrawDevice(pix))
        dev.fillImage(img, Matrix(nw.toFloat(), 0f, 0f, nh.toFloat(), 0f, 0f), 1f, 0)
        dev.close()
        val jpeg = n.add(pix.asJPEG(quality, false))

        obj.writeRawStream(jpeg) // keeps Filter; updates Length
        obj.put("Width", nw)
        obj.put("Height", nh)
        obj.put("BitsPerComponent", 8)
        // NB: put(String, String) writes a PDF *string*; names need doc.newName().
        obj.put("ColorSpace", n.add(doc.newName(if (cs === ColorSpace.DeviceGray) "DeviceGray" else "DeviceRGB")))
        obj.put("Filter", n.add(doc.newName("DCTDecode")))
        for (k in listOf("DecodeParms", "Decode", "SMaskInData", "Intent", "Alternates")) obj.delete(k)
    }
    return result
}

class CompressSpikeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Photo-like RGB: smooth gradient, reddish top-left, bluish bottom-right, plus noise. */
    private fun photo(w: Int, h: Int): ByteArray {
        val rnd = noise(7)
        return ppm(w, h) { x, y ->
            val tl = x < w / 2 && y < h / 2
            val br = x >= w / 2 && y >= h / 2
            var r = 90 + 100 * x / w; var g = 110; var b = 90 + 100 * y / h
            if (tl) { r += 60; b -= 40 }
            if (br) { b += 60; r -= 40 }
            val nz = rnd.nextInt(-40, 41)
            (clamp(r + nz) shl 16) or (clamp(g + rnd.nextInt(-40, 41)) shl 8) or clamp(b + nz)
        }
    }

    private fun quadrants(pix: Pixmap): List<DoubleArray> {
        val w = pix.width; val h = pix.height
        return listOf(
            meanRgb(pix, 0, 0, w / 2, h / 2), meanRgb(pix, w / 2, 0, w, h / 2),
            meanRgb(pix, 0, h / 2, w / 2, h), meanRgb(pix, w / 2, h / 2, w, h),
        )
    }

    private fun renderQuadrants(f: File): List<DoubleArray> = Native().use { n ->
        val doc = n.add(openPdf(f))
        val page = n.add(doc.loadPage(0))
        val pix = n.add(page.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false))
        quadrants(pix)
    }

    @Test
    fun recipe7_downscaleAndReplaceInPlace() {
        val src = tmp.newFile("big.pdf")
        val origW = 3000; val origH = 2000
        val jpeg = toJpeg(photo(origW, origH), 92)
        Native().use { n ->
            val doc = n.add(PDFDocument())
            val img = n.add(Image(jpeg))
            val ref = n.add(doc.addImage(img))
            addImagePage(doc, ref, 600f, 400f)
            doc.save(src.absolutePath, SAVE_OPTS)
        }
        val before = renderQuadrants(src)

        val out = tmp.newFile("small.pdf")
        Native().use { n ->
            val doc = n.add(openPdf(src))
            val verdicts = compressImages(doc, 1000, 60)
            println("recipe7 verdicts: $verdicts")
            assertEquals(listOf(Action.RECOMPRESS_RGB), verdicts.values.map { it.action })
            doc.save(out.absolutePath, SAVE_OPTS)
        }
        println("recipe7 sizes: ${src.length()} -> ${out.length()}")
        assertTrue("expected >5x smaller: ${src.length()} -> ${out.length()}", out.length() * 5 < src.length())

        Native().use { n ->
            val doc = n.add(openPdf(out))
            assertEquals(1, doc.countPages())
            val im = n.add(pageImage(doc, 0))
            assertEquals(1000, n.add(im.get("Width")).asInteger())
            assertEquals(667, n.add(im.get("Height")).asInteger())
            assertEquals("DCTDecode", n.add(im.get("Filter")).asName())
            val loaded = n.add(doc.loadImage(im))
            assertEquals(1000, loaded.width)
            assertEquals(667, loaded.height)
        }
        val after = renderQuadrants(out)
        for (q in 0 until 4) for (k in 0 until 3)
            assertTrue("quadrant $q channel $k: ${before[q][k]} vs ${after[q][k]}", abs(before[q][k] - after[q][k]) < 6.0)
        // sanity: the tint is actually there, so a flip/swap would fail the check above
        assertTrue(before[0][0] - before[0][2] > 50 && before[3][2] - before[3][0] > 50)
    }

    /** Gray and CMYK handling + every skip case, classified from the image dictionary. */
    @Test
    fun recipe7_grayRecompressAndSkipCases() {
        val src = tmp.newFile("mixed.pdf")
        val names = LinkedHashMap<String, Int>() // label -> object number
        Native().use { n ->
            val doc = n.add(PDFDocument())
            fun addImg(label: String, bytes: ByteArray) {
                val img = n.add(Image(bytes))
                val ref = n.add(doc.addImage(img))
                names[label] = ref.asIndirect()
                addImagePage(doc, ref, 300f, 200f)
            }
            addImg("gray", toJpeg(pgm(600, 400) { x, y -> (x + y) % 256 }, 92))
            addImg("rgbaPng", toPng(pam(60, 40, 4, "RGB_ALPHA") { x, _ -> intArrayOf(200, 10, 10, x * 4) }))
            addImg("cmykJpeg", toJpeg(pam(64, 64, 4, "CMYK") { _, _ -> intArrayOf(0, 255, 255, 0) }, 90))
            doc.save(src.absolutePath, SAVE_OPTS)
        }
        println("recipe7 labels->objnum (pre-save numbering): $names")

        val out = tmp.newFile("mixed-out.pdf")
        Native().use { n ->
            val doc = n.add(openPdf(src))
            // dump the rgba png + cmyk dicts for the report
            for (i in 0 until doc.countPages()) {
                val im = n.add(pageImage(doc, i))
                println("recipe7 page $i dict: ${im.toString(true)}")
            }
            val verdicts = compressImages(doc, 300, 60)
            // Re-key verdicts by page (object numbers change on save with garbage collection).
            val byPage = (0 until doc.countPages()).map { i -> verdicts[n.add(n.add(doc.findPage(i)).get("Resources")).let { r ->
                n.add(n.add(r.get("XObject")).get("Im0")).asIndirect() }]!! }
            println("recipe7 verdicts by page: $byPage")
            val labels = names.keys.toList()
            val expected = mapOf("gray" to Action.RECOMPRESS_GRAY, "rgbaPng" to Action.SKIP, "cmykJpeg" to Action.SKIP)
            for ((i, l) in labels.withIndex()) assertEquals(l, expected[l], byPage[i].action)
            assertEquals("SMask", byPage[labels.indexOf("rgbaPng")].reason)
            // the PNG's SMask object itself is seen during iteration and skipped as a mask target
            assertTrue(verdicts.values.any { it.reason == "is SMask/Mask of another image" })
            doc.save(out.absolutePath, SAVE_OPTS)
        }
        Native().use { n ->
            val doc = n.add(openPdf(out))
            val gray = n.add(pageImage(doc, 0))
            assertEquals(300, n.add(gray.get("Width")).asInteger())
            assertEquals(200, n.add(gray.get("Height")).asInteger())
            assertEquals("DeviceGray", n.add(gray.get("ColorSpace")).asName())
            val img = n.add(doc.loadImage(gray))
            assertEquals(1, img.numberOfComponents)
            // every page still renders
            for (i in 0 until doc.countPages()) {
                val page = n.add(doc.loadPage(i))
                n.add(page.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false))
            }
        }
    }

    /** Skip detection on direct (not-in-document) image dictionaries. */
    @Test
    @org.junit.Ignore("native heap corruption (malloc: unaligned tcache chunk) when building direct dicts; bisecting")
    fun recipe7_skipClassificationFromDict() {
        Native().use { n ->
            val doc = n.add(PDFDocument())
            val base = mapOf<String, Any>("Type" to "/XObject", "Subtype" to "/Image", "Width" to 8, "Height" to 8, "BitsPerComponent" to 8, "ColorSpace" to "/DeviceRGB")
            val tint = mapOf("FunctionType" to 2, "Domain" to listOf(0, 1), "C0" to listOf(0, 0, 0, 0), "C1" to listOf(0, 1, 0, 0), "N" to 1)
            val cases = linkedMapOf<String, Pair<Map<String, Any>, String>>(
                "plainRgb" to (base to "DeviceRGB"),
                "imageMask" to (base - "ColorSpace" + mapOf("ImageMask" to true, "BitsPerComponent" to 1) to "ImageMask"),
                "colorKey" to (base + ("Mask" to listOf(0, 10, 0, 10, 0, 10)) to "Mask colour-key array"),
                "bpc1" to (base + mapOf("ColorSpace" to "/DeviceGray", "BitsPerComponent" to 1) to "BitsPerComponent 1"),
                "jbig2" to (base + ("Filter" to "/JBIG2Decode") to "Filter JBIG2Decode"),
                "ccittArray" to (base + ("Filter" to listOf("/CCITTFaxDecode")) to "Filter CCITTFaxDecode"),
                "indexed" to (base + ("ColorSpace" to listOf("/Indexed", "/DeviceRGB", 1, "abcdef")) to "ColorSpace Indexed"),
                "separation" to (base + ("ColorSpace" to listOf("/Separation", "/Spot", "/DeviceCMYK", tint)) to "ColorSpace Separation"),
                "deviceN" to (base + ("ColorSpace" to listOf("/DeviceN", listOf("/A", "/B"), "/DeviceCMYK", tint)) to "ColorSpace DeviceN"),
                "cmyk" to (base + ("ColorSpace" to "/DeviceCMYK") to "ColorSpace DeviceCMYK"),
                "gray" to (base + ("ColorSpace" to "/DeviceGray") to "DeviceGray"),
            )
            for ((label, c) in cases) {
                val d = n.add(doc.obj(c.first))
                val v = classify(doc, -1, d, emptySet())
                assertEquals(label, c.second, v.reason)
            }
            // A stream /Mask (stencil) is detected the same way: !get("Mask").isNull && !resolve().isArray
            val stencil = n.add(doc.obj(base - "ColorSpace" + mapOf("ImageMask" to true, "BitsPerComponent" to 1)))
            val withStencil = n.add(doc.obj(base))
            withStencil.put("Mask", stencil)
            assertEquals("Mask stencil stream", classify(doc, -1, withStencil, emptySet()).reason)
        }
    }

    /**
     * CMYK polarity: draw a CMYK image into a CMYK pixmap, asJPEG(q, invertCMYK), store with
     * /ColorSpace /DeviceCMYK /Filter /DCTDecode, render, and see which flag keeps the colour.
     */
    @Test
    fun recipe7_cmykPolarity() {
        val red = intArrayOf(0, 255, 255, 0) // C M Y K -> red
        val results = HashMap<Boolean, DoubleArray>()
        for (invert in listOf(false, true)) Native().use { n ->
            val doc = n.add(PDFDocument())
            val srcImg = n.add(Image(pam(64, 64, 4, "CMYK") { _, _ -> red }))
            val pix = n.add(Pixmap(ColorSpace.DeviceCMYK, 32, 32, false))
            pix.clear(0) // CMYK 0 = white
            val dev = n.add(DrawDevice(pix))
            dev.fillImage(srcImg, Matrix(32f, 0f, 0f, 32f, 0f, 0f), 1f, 0)
            dev.close()
            val jpeg = n.add(pix.asJPEG(90, invert)).asByteArray()
            val ref = n.add(doc.addRaw(mapOf("Type" to "/XObject", "Subtype" to "/Image", "Width" to 32, "Height" to 32,
                "ColorSpace" to "/DeviceCMYK", "BitsPerComponent" to 8, "Filter" to "/DCTDecode"), jpeg))
            addImagePage(doc, ref, 32f, 32f)
            val f = tmp.newFile("cmyk-$invert.pdf")
            doc.save(f.absolutePath, SAVE_OPTS)
            val d2 = n.add(openPdf(f))
            val page = n.add(d2.loadPage(0))
            val out = n.add(page.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false))
            results[invert] = meanRgb(out, 4, 4, 28, 28)
        }
        println("recipe7 cmyk polarity: invert=false -> ${results[false]!!.toList()}, invert=true -> ${results[true]!!.toList()}")
        val ok = results[false]!!
        assertTrue("invertCMYK=false keeps red: ${ok.toList()}", ok[0] > 180 && ok[1] < 80 && ok[2] < 80)
    }
}
