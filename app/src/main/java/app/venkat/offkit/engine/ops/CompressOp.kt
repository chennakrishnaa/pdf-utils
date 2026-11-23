package app.venkat.offkit.engine.ops

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.ImageLimits
import app.venkat.offkit.engine.ImagePages
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfResult
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Implementation of [app.venkat.offkit.engine.PdfEngine.compress].
 *
 * Pass 1 collects the object numbers that are another image's `/SMask` or `/Mask` stream; those
 * are never touched. Pass 2 visits every image XObject stream and, if [classify] allows it,
 * downscales it so its long edge is at most [CompressionLevel.maxLongEdgePx] (never upscaling),
 * re-encodes it as JPEG at [CompressionLevel.jpegQuality] and replaces the stream in place — but
 * only when the new JPEG is strictly smaller than the existing encoded stream. Images over
 * [MAX_PIXELS] are skipped without decoding, and so is a DCT image whose stream header (SOF) does
 * not declare exactly the dictionary's size or fails [ImageLimits.jpeg] (a progressive JPEG can
 * otherwise make libjpeg allocate without bound). Only one image is decoded at a time.
 *
 * Whole-file guarantee: if the saved result is not smaller than the input, `out` is replaced by a
 * byte-for-byte copy of the input, so the output is never larger than the original. The caller
 * detects "no gain" by comparing `Ok.bytes` with the input size. Either way the delivered file is
 * reopened and every page loaded first ([PdfInputs.validateOutput] / [PdfInputs.checkPages]); if
 * that fails the result is `Err(Corrupt)`, never `Ok` with an unreadable file.
 *
 * Progress is reported once per image examined (total = number of image objects; a document
 * without images reports `(1, 1)` once); cancellation is checked before each image and before
 * saving. Uncaught-throwable mapping and deletion of a partial `out` on error are done by the
 * caller ([app.venkat.offkit.engine.MuPdfEngine]).
 */
class CompressOp {
    fun run(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult = PdfInputs.toResult {
        val doc = PdfInputs.open(pdf)
        try {
            val (images, maskTargets) = scanImages(doc)
            val total = images.size
            for ((index, num) in images.withIndex()) {
                if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)
                recompress(doc, num, maskTargets, level)
                job.progress(index + 1, total)
            }
            if (total == 0) job.progress(1, 1)
            if (job.isCancelled()) return@toResult PdfResult.Err(PdfError.Cancelled)

            val pages = doc.countPages()
            doc.save(out.absolutePath, PdfInputs.SAVE_OPTIONS)
            if (out.length() >= pdf.length()) {
                // Deliver the original bytes, but only if the original itself is fully readable.
                PdfInputs.checkPages(pdf, pages, job)?.let { return@toResult it }
                pdf.copyTo(out, overwrite = true)
            } else {
                PdfInputs.validateOutput(out, pages, job)?.let { return@toResult it }
            }
            PdfResult.Ok(pages = pages, bytes = out.length())
        } finally {
            doc.destroy()
        }
    }

    /** Pass 1: object numbers of all image XObject streams, and of those used as SMask/Mask targets. */
    private fun scanImages(doc: PDFDocument): Pair<List<Int>, Set<Int>> {
        val images = ArrayList<Int>()
        val masks = HashSet<Int>()
        for (num in 1 until doc.countObjects()) Scope().use { s ->
            // isStream()/readRawStream() only work on the indirect reference, not on resolve()d dicts.
            val obj = s.add(doc.newIndirect(num, 0))
            if (!isImageStream(s, obj)) return@use
            images.add(num)
            for (k in MASK_KEYS) {
                val v = s.add(obj.get(k))
                if (v.isIndirect) masks.add(v.asIndirect())
            }
        }
        return images to masks
    }

    private fun isImageStream(s: Scope, obj: PDFObject): Boolean {
        if (!obj.isStream) return false
        val subtype = s.add(obj.get("Subtype"))
        return subtype.isName && subtype.asName() == "Image"
    }

    /** Pass 2 for one image object: replace it with a smaller JPEG or leave it untouched. */
    private fun recompress(doc: PDFDocument, num: Int, maskTargets: Set<Int>, level: CompressionLevel) {
        Scope().use { s ->
            val obj = s.add(doc.newIndirect(num, 0))
            val kind = classify(s, num, obj, maskTargets)
            if (kind == Kind.SKIP) return

            val w = s.add(obj.get("Width")).let { if (it.isNumber) it.asInteger() else 0 }
            val h = s.add(obj.get("Height")).let { if (it.isNumber) it.asInteger() else 0 }
            if (w <= 0 || h <= 0 || w.toLong() * h.toLong() > MAX_PIXELS) return
            // Encoded size from /Length (SA-9: readRawStream() would copy the whole stream to the heap).
            val length = s.add(s.add(obj.get("Length")).resolve()).let { if (it.isNumber) it.asInteger() else 0 }
            if (length <= 0) return

            // MuPDF sizes a DCT decode from the stream's own SOF, not from the dictionary (SA-6).
            val filters = filtersOf(s, obj)
            if ("DCTDecode" in filters) {
                if (filters.size != 1 || !dctHeaderMatches(obj, w, h, length)) return
            }

            val img = s.add(doc.loadImage(obj))
            // Last line of defence: MuPDF must decode exactly what was checked above.
            if (img.width != w || img.height != h) return
            val scale = minOf(1.0, level.maxLongEdgePx.toDouble() / max(img.width, img.height))
            val nw = max(1, (img.width * scale).roundToInt())
            val nh = max(1, (img.height * scale).roundToInt())
            val cs = if (kind == Kind.GRAY) ColorSpace.DeviceGray else ColorSpace.DeviceRGB // singletons: never destroy
            val pix = s.add(Pixmap(cs, nw, nh, false))
            pix.clear(255)
            val dev = s.add(DrawDevice(pix))
            dev.fillImage(img, Matrix(nw.toFloat(), 0f, 0f, nh.toFloat(), 0f, 0f), 1f, 0)
            dev.close()
            val jpeg = s.add(pix.asJPEG(level.jpegQuality, false))

            if (jpeg.length >= length) return

            obj.writeRawStream(jpeg) // keeps the object number; updates Length
            obj.put("Width", nw)
            obj.put("Height", nh)
            obj.put("BitsPerComponent", 8)
            // put(String, String) writes a PDF string; names need doc.newName().
            obj.put("ColorSpace", s.add(doc.newName(if (kind == Kind.GRAY) "DeviceGray" else "DeviceRGB")))
            obj.put("Filter", s.add(doc.newName("DCTDecode")))
            for (k in DROPPED_KEYS) obj.delete(k)
        }
    }

    /**
     * True when the DCT stream of [obj] ([length] bytes per `/Length`) has exactly one frame header
     * declaring [w] × [h] and passes [ImageLimits.jpeg]. Only the header is parsed; streams larger
     * than [MAX_DCT_INSPECT_BYTES] are not read at all (false: the image is left untouched).
     */
    private fun dctHeaderMatches(obj: PDFObject, w: Int, h: Int, length: Int): Boolean {
        if (length > MAX_DCT_INSPECT_BYTES) return false
        val raw = obj.readRawStream()
        val layout = ImagePages.jpegScan(raw.inputStream(), headerOnly = true) ?: return false
        val frame = layout.frame ?: return false
        if (frame.width != w || frame.height != h) return false
        return ImageLimits.jpeg(frame, layout.frameCount, layout.firstScanComponents) == ImageLimits.Verdict.OK
    }

    /** Filter names of image [dict] (a single name or an array of names). */
    private fun filtersOf(s: Scope, dict: PDFObject): List<String> {
        val filter = s.add(s.add(dict.get("Filter")).resolve())
        val filters = ArrayList<String>()
        if (filter.isName) {
            filters.add(filter.asName())
        } else if (filter.isArray) {
            for (i in 0 until filter.size()) {
                val f = s.add(s.add(filter.get(i)).resolve())
                if (f.isName) filters.add(f.asName())
            }
        }
        return filters
    }

    private enum class Kind { RGB, GRAY, SKIP }

    /** Decides how an image XObject may be recompressed; everything not clearly safe is skipped. */
    private fun classify(s: Scope, num: Int, dict: PDFObject, maskTargets: Set<Int>): Kind {
        if (num in maskTargets) return Kind.SKIP
        // Missing keys come back as PDFObject.Null (isNull == true), never Kotlin null.
        val imageMask = s.add(dict.get("ImageMask"))
        if (imageMask.isBoolean && imageMask.asBoolean()) return Kind.SKIP
        if (!s.add(dict.get("SMask")).isNull) return Kind.SKIP
        if (!s.add(dict.get("Mask")).isNull) return Kind.SKIP

        if (filtersOf(s, dict).any { it in UNSUPPORTED_FILTERS }) return Kind.SKIP

        val bpc = s.add(dict.get("BitsPerComponent"))
        if (!bpc.isNumber || bpc.asInteger() != 8) return Kind.SKIP

        val cs = s.add(s.add(dict.get("ColorSpace")).resolve())
        val family = when {
            cs.isName -> cs.asName()
            cs.isArray && cs.size() > 0 -> s.add(s.add(cs.get(0)).resolve()).let { if (it.isName) it.asName() else "" }
            else -> ""
        }
        return when (family) {
            "DeviceRGB", "CalRGB" -> Kind.RGB
            "DeviceGray", "CalGray" -> Kind.GRAY
            "ICCBased" -> {
                if (cs.size() < 2) return Kind.SKIP
                val n = s.add(s.add(s.add(cs.get(1)).resolve()).get("N"))
                when (if (n.isNumber) n.asInteger() else 0) {
                    1 -> Kind.GRAY
                    3 -> Kind.RGB
                    else -> Kind.SKIP
                }
            }
            // DeviceCMYK, Indexed, Separation, DeviceN, Lab, Pattern, missing: skip.
            else -> Kind.SKIP
        }
    }

    /** Collects MuPDF objects and destroys them (in reverse order) when the scope closes. */
    private class Scope : AutoCloseable {
        private val items = ArrayList<Any>()

        fun <T : Any> add(item: T): T {
            items.add(item)
            return item
        }

        override fun close() {
            for (i in items.indices.reversed()) {
                when (val item = items[i]) {
                    is PDFObject -> if (item !== PDFObject.Null) item.destroy()
                    is Image -> item.destroy()
                    is Pixmap -> item.destroy()
                    is DrawDevice -> item.destroy()
                    is Buffer -> item.destroy()
                }
            }
            items.clear()
        }
    }

    private companion object {
        /**
         * Images larger than this (width × height, from the dictionary, which for DCT must also match
         * the stream's SOF) are skipped rather than decoded: 40 MP × 3 B ≈ 120 MB per decode.
         */
        const val MAX_PIXELS = 40_000_000L

        /**
         * DCT streams longer than this are not read to inspect their header (the binding can only
         * copy a whole stream to the Java heap); such images are left untouched.
         */
        const val MAX_DCT_INSPECT_BYTES = 32 * 1024 * 1024

        val MASK_KEYS = listOf("SMask", "Mask")
        val UNSUPPORTED_FILTERS = setOf("JBIG2Decode", "CCITTFaxDecode", "CCF", "JPXDecode")
        val DROPPED_KEYS = listOf("DecodeParms", "Decode", "SMaskInData", "Intent", "Alternates")
    }
}
