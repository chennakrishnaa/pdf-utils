package com.offlinepdf.utils

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.Deflater

/**
 * Deterministic test inputs generated in code (no binary fixture files), plus read-back helpers.
 * Compiled into both JVM unit tests and androidTest: imports only java.*, kotlin.* and
 * com.artifex.mupdf.fitz.*. Every builder takes a target directory and returns the created file.
 * Not thread-safe (MuPDF objects are used on the calling thread only).
 */
object Fixtures {
    const val USER_PASSWORD = "userpw"
    const val OWNER_PASSWORD = "ownerpw"

    const val A4_WIDTH = 595f
    const val A4_HEIGHT = 842f

    /** 0xRRGGBB colours painted in the four quadrants of the *displayed* (upright) image. */
    const val RED = 0xFF0000
    const val GREEN = 0x00FF00
    const val BLUE = 0x0000FF
    const val YELLOW = 0xFFFF00

    /** Rectangle of the link made by [linkPdf], in MuPDF page coordinates (origin top-left): x0, y0, x1, y1. */
    val LINK_RECT: FloatArray get() = floatArrayOf(72f, 100f, 320f, 140f)

    private const val SAVE_OPTIONS = "garbage=deduplicate,compress,objstms"

    /* ------------------------------------------------------------------ images */

    /** Photo-like noisy RGB JPEG (quality 90), [width]x[height] px. */
    fun jpeg(dir: File, name: String = "photo.jpg", width: Int = 400, height: Int = 300, seed: Long = 1L): File =
        File(dir, name).also { it.writeBytes(encodeJpeg(ppm(width, height, photoPixels(width, height, seed)), 90)) }

    /** Photo-like noisy RGB PNG (no alpha). */
    fun png(dir: File, name: String = "photo.png", width: Int = 400, height: Int = 300, seed: Long = 1L): File =
        File(dir, name).also { it.writeBytes(encodePng(ppm(width, height, photoPixels(width, height, seed)))) }

    /** RGB PNG with an alpha channel (alpha ramps 0..255 left to right; colours noisy). */
    fun pngWithAlpha(dir: File, name: String = "alpha.png", width: Int = 400, height: Int = 300, seed: Long = 1L): File {
        val px = photoPixels(width, height, seed)
        val raw = pam(width, height, 4, "RGB_ALPHA") { x, y ->
            val c = px(x, y)
            intArrayOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff, if (width > 1) 255 * x / (width - 1) else 255)
        }
        return File(dir, name).also { it.writeBytes(encodePng(raw)) }
    }

    /**
     * JPEG of [width]x[height] STORED pixels carrying EXIF Orientation [exifOrientation] (1..8).
     * The pixels are laid out so that, once the orientation is applied, the displayed image is upright
     * with quadrants TL = [RED], TR = [GREEN], BL = [BLUE], BR = [YELLOW]. The displayed size is
     * [width]x[height] for orientations 1..4 and [height]x[width] for 5..8 (use a non-square size).
     */
    fun jpegWithExifOrientation(dir: File, name: String, width: Int, height: Int, exifOrientation: Int): File {
        require(exifOrientation in 1..8)
        val transposed = exifOrientation >= 5
        val dw = if (transposed) height else width // displayed W
        val dh = if (transposed) width else height // displayed H
        val raw = ppm(width, height) { x, y ->
            // Displayed position (X, Y) of stored pixel (x, y) under each EXIF orientation.
            val (dx, dy) = when (exifOrientation) {
                1 -> x to y
                2 -> (dw - 1 - x) to y
                3 -> (dw - 1 - x) to (dh - 1 - y)
                4 -> x to (dh - 1 - y)
                5 -> y to x
                6 -> (dw - 1 - y) to x
                7 -> (dw - 1 - y) to (dh - 1 - x)
                else -> y to (dh - 1 - x)
            }
            val left = dx < dw / 2
            val top = dy < dh / 2
            when {
                top && left -> RED
                top -> GREEN
                left -> BLUE
                else -> YELLOW
            }
        }
        val jpeg = withExifOrientation(encodeJpeg(raw, 95), exifOrientation)
        return File(dir, name).also { it.writeBytes(jpeg) }
    }

    /** Noisy 8-bit gray JPEG (quality 90, one component), [width]x[height] px. */
    fun grayJpeg(dir: File, name: String = "gray.jpg", width: Int = 400, height: Int = 300, seed: Long = 1L): File {
        val px = photoPixels(width, height, seed)
        return File(dir, name).also { it.writeBytes(encodeJpeg(pgm(width, height) { x, y -> px(x, y) shr 8 and 0xff }, 90)) }
    }

    /** Copy of [file] next to it (name prefixed "truncated-") keeping the first [keepFraction] of its bytes. */
    fun truncated(file: File, keepFraction: Double): File {
        require(keepFraction in 0.0..1.0)
        val bytes = file.readBytes()
        return File(file.parentFile, "truncated-" + file.name).also {
            it.writeBytes(bytes.copyOf((bytes.size * keepFraction).toInt()))
        }
    }

    /** 4 KiB of random bytes named like a JPEG. */
    fun notAnImage(dir: File, name: String = "not-an-image.jpg"): File =
        File(dir, name).also { it.writeBytes(randomBytes(4096, 11L)) }

    /* ------------------------------------------------------------------ PDFs */

    /** 4 KiB of random bytes named like a PDF. */
    fun corruptPdf(dir: File, name: String = "corrupt.pdf"): File =
        File(dir, name).also { it.writeBytes(randomBytes(4096, 12L)) }

    /** A zero-byte file named like a PDF. */
    fun emptyPdfFile(dir: File, name: String = "empty.pdf"): File =
        File(dir, name).also { it.writeBytes(ByteArray(0)) }

    /** One page per entry of [pageTexts] (Helvetica 24 pt, extractable), page size [pageWidth]x[pageHeight] pt. */
    fun textPdf(
        dir: File,
        name: String,
        pageTexts: List<String>,
        pageWidth: Float = A4_WIDTH,
        pageHeight: Float = A4_HEIGHT,
    ): File {
        val f = File(dir, name)
        buildPdf { doc -> for (t in pageTexts) addPage(doc, pageWidth, pageHeight, null, t) }.save(f)
        return f
    }

    /** One A4 text page with an external URI link [uri] at [LINK_RECT]. */
    fun linkPdf(dir: File, name: String, uri: String): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Link page")
            val page = doc.loadPage(0)
            try {
                val r = LINK_RECT
                page.createLink(Rect(r[0], r[1], r[2], r[3]), uri).destroy()
            } finally {
                page.destroy()
            }
        }.save(f)
        return f
    }

    /** Two text pages ("Secret page 1", "Secret page 2"), AES-256 encrypted; opening requires [USER_PASSWORD]. */
    fun encryptedPdf(dir: File, name: String = "encrypted.pdf"): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Secret page 1")
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Secret page 2")
        }.save(f, "garbage=deduplicate,compress,encrypt=aes-256,user-password=$USER_PASSWORD,owner-password=$OWNER_PASSWORD")
        return f
    }

    /**
     * Two text pages ("Restricted page 1", "Restricted page 2"), AES-256 encrypted with only an owner password
     * ([OWNER_PASSWORD]) and an empty user password: it opens without a password (`needsPassword()` is false) but
     * carries an /Encrypt dictionary with permission restrictions.
     */
    fun ownerPasswordPdf(dir: File, name: String = "owner-only.pdf"): File {
        val f = File(dir, name)
        buildPdf { doc ->
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Restricted page 1")
            addPage(doc, A4_WIDTH, A4_HEIGHT, null, "Restricted page 2")
        }.save(f, "garbage=deduplicate,compress,encrypt=aes-256,owner-password=$OWNER_PASSWORD,permissions=-44")
        return f
    }

    /**
     * [pages] pages, each a full-page noisy RGB JPEG (quality 92, [imageWidth]x[imageHeight] px, different seed per
     * page, DCTDecode / DeviceRGB) drawn at 1 px = 1 pt, plus the extractable text "Photo page N" (N from 1).
     * With the defaults the file is several MB.
     */
    fun photoPdf(dir: File, name: String, pages: Int = 2, imageWidth: Int = 3200, imageHeight: Int = 2400): File {
        val f = File(dir, name)
        buildPdf { doc ->
            for (i in 0 until pages) {
                val jpeg = encodeJpeg(ppm(imageWidth, imageHeight, photoPixels(imageWidth, imageHeight, 100L + i)), 92)
                val ref = addRaw(doc, imageDict(imageWidth, imageHeight, 8, "/DeviceRGB", "/DCTDecode"), jpeg)
                try {
                    addPage(doc, imageWidth.toFloat(), imageHeight.toFloat(), ref, "Photo page ${i + 1}")
                } finally {
                    ref.destroy()
                }
            }
        }.save(f)
        return f
    }

    /**
     * Four pages, one image each (drawn full page at 1 px = 1 pt) plus the text "Mixed page N":
     *  - page 1: RGB PNG with alpha via addImage, 2400x1600 -> FlateDecode, ICCBased RGB, with /SMask
     *    (the SMask is a separate 8-bit DeviceGray image, reported with isMaskTarget = true);
     *  - page 2: 1-bit DeviceGray image, 2400x1600, BitsPerComponent 1, FlateDecode (hand-built);
     *  - page 3: Indexed image ([/Indexed /DeviceRGB 3 <lookup>]), 2400x1600, 8 bpc, FlateDecode (hand-built);
     *  - page 4: gray JPEG, 2400x1600, DCTDecode, /DeviceGray, 8 bpc (hand-built).
     */
    fun mixedImagesPdf(dir: File, name: String = "mixed.pdf"): File {
        val f = File(dir, name)
        val w = 2400
        val h = 1600
        buildPdf { doc ->
            // (a) RGB + soft mask
            val alphaPng = encodePng(pam(w, h, 4, "RGB_ALPHA") { x, y ->
                intArrayOf(40 + 180 * x / w, 200 - 150 * y / h, 120, 255 * x / w)
            })
            val img = Image(alphaPng)
            try {
                val ref = doc.addImage(img)
                try { addPage(doc, w.toFloat(), h.toFloat(), ref, "Mixed page 1") } finally { ref.destroy() }
            } finally {
                img.destroy()
            }
            // (b) 1-bit gray: checkerboard of 40 px cells
            val rowBytes = (w + 7) / 8
            val bits = ByteArray(rowBytes * h)
            for (y in 0 until h) for (x in 0 until w) {
                if (((x / 40) + (y / 40)) % 2 == 0) {
                    val i = y * rowBytes + x / 8
                    bits[i] = (bits[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            addRawPage(doc, imageDict(w, h, 1, "/DeviceGray", "/FlateDecode"), deflate(bits), w, h, "Mixed page 2")
            // (c) Indexed, 4 palette entries in horizontal bands
            val lookup = byteArrayOf(255.toByte(), 0, 0, 0, 160.toByte(), 0, 0, 0, 255.toByte(), 240.toByte(), 240.toByte(), 240.toByte())
            val idx = ByteArray(w * h) { i -> ((i / w) * 4 / h + (i % w) / 600).mod(4).toByte() }
            val indexedCs = listOf("/Indexed", "/DeviceRGB", 3, lookup)
            addRawPage(doc, imageDict(w, h, 8, indexedCs, "/FlateDecode"), deflate(idx), w, h, "Mixed page 3")
            // (d) gray JPEG
            val rnd = Random(21L)
            val grayJpeg = encodeJpeg(pgm(w, h) { x, y -> clamp((x + y) * 255 / (w + h) + rnd.nextInt(41) - 20) }, 92)
            addRawPage(doc, imageDict(w, h, 8, "/DeviceGray", "/DCTDecode"), grayJpeg, w, h, "Mixed page 4")
        }.save(f)
        return f
    }

    /* ------------------------------------------------------------------ read-back */

    data class ImageInfo(
        val objectNumber: Int,
        val width: Int,
        val height: Int,
        /** 0 when absent (e.g. some ImageMasks). */
        val bitsPerComponent: Int,
        /** Filter name, or array elements joined by a space; null when absent. */
        val filter: String?,
        /** Colour space name, or the first element of a colour space array (e.g. "Indexed", "ICCBased"); null when absent. */
        val colorSpaceFamily: String?,
        val hasSMask: Boolean,
        val isImageMask: Boolean,
        /** True when another image references this one as its /SMask or /Mask. */
        val isMaskTarget: Boolean,
        /** Length of the encoded (raw) stream data. */
        val rawLength: Int,
        /** Colour components of the decoded image as MuPDF loads it (1 = gray, 3 = RGB, 4 = CMYK). */
        val components: Int = 0,
    )

    /** RGB of a rendered page, row-major, 0xRRGGBB per pixel. */
    class RenderedPage(val width: Int, val height: Int, val rgb: IntArray) {
        fun at(x: Int, y: Int): Int = rgb[y * width + x]
    }

    /** Samples near each corner (inset 5% of each dimension) of a rendered page, 0xRRGGBB. */
    data class CornerColors(val topLeft: Int, val topRight: Int, val bottomLeft: Int, val bottomRight: Int)

    fun pageCount(file: File): Int = withDoc(file) { it.countPages() }

    fun pageTexts(file: File): List<String> = withDoc(file) { doc ->
        (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                val st = page.toStructuredText()
                try { st.asText() } finally { st.destroy() }
            } finally {
                page.destroy()
            }
        }
    }

    /** Displayed page sizes in points (width, height), honouring /Rotate and the CropBox. */
    fun pageSizes(file: File): List<Pair<Float, Float>> = withDoc(file) { doc ->
        (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                val b = page.bounds
                (b.x1 - b.x0) to (b.y1 - b.y0)
            } finally {
                page.destroy()
            }
        }
    }

    /** Links of page [pageIndex]: (uri, [x0, y0, x1, y1] in MuPDF page coordinates). */
    fun links(file: File, pageIndex: Int): List<Pair<String, FloatArray>> = withDoc(file) { doc ->
        val page = doc.loadPage(pageIndex)
        try {
            val links = page.links ?: emptyArray()
            links.map { l ->
                val b = l.bounds
                val r = l.uri to floatArrayOf(b.x0, b.y0, b.x1, b.y1)
                l.destroy()
                r
            }
        } finally {
            page.destroy()
        }
    }

    /** Every image XObject stream in the file, in object-number order. */
    fun imageInfos(file: File): List<ImageInfo> = withDoc(file) { d ->
        val doc = d as PDFDocument
        val maskTargets = HashSet<Int>()
        val images = ArrayList<Int>()
        for (num in 1 until doc.countObjects()) {
            val obj = doc.newIndirect(num, 0)
            try {
                if (!obj.isStream || nameOf(obj, "Subtype") != "Image") continue
                images.add(num)
                for (k in listOf("SMask", "Mask")) {
                    val v = obj.get(k)
                    if (v.isIndirect) maskTargets.add(v.asIndirect())
                    destroyObj(v)
                }
            } finally {
                obj.destroy()
            }
        }
        images.map { num ->
            val obj = doc.newIndirect(num, 0)
            try {
                ImageInfo(
                    objectNumber = num,
                    width = intOf(obj, "Width"),
                    height = intOf(obj, "Height"),
                    bitsPerComponent = intOf(obj, "BitsPerComponent"),
                    filter = obj.get("Filter").use { f ->
                        when {
                            f.isName -> f.asName()
                            f.isArray -> (0 until f.size()).joinToString(" ") { i -> f.get(i).use { it.asName() } }
                            else -> null
                        }
                    },
                    colorSpaceFamily = obj.get("ColorSpace").use { cs ->
                        when {
                            cs.isName -> cs.asName()
                            cs.isArray -> cs.get(0).use { it.asName() }
                            else -> null
                        }
                    },
                    hasSMask = obj.get("SMask").use { !it.isNull },
                    isImageMask = obj.get("ImageMask").use { it.isBoolean && it.asBoolean() },
                    isMaskTarget = num in maskTargets,
                    rawLength = obj.readRawStream().size,
                    components = doc.loadImage(obj).let { img -> try { img.numberOfComponents } finally { img.destroy() } },
                )
            } finally {
                obj.destroy()
            }
        }
    }

    /** Renders page [pageIndex] so that its longer edge is about [maxEdgePx] pixels (RGB, white background). */
    fun render(file: File, pageIndex: Int, maxEdgePx: Int = 300): RenderedPage = withDoc(file) { doc ->
        val page = doc.loadPage(pageIndex)
        try {
            val b = page.bounds
            val scale = maxEdgePx / maxOf(b.x1 - b.x0, b.y1 - b.y0)
            val pix = page.toPixmap(Matrix(scale, 0f, 0f, scale, 0f, 0f), ColorSpace.DeviceRGB, false)
            try {
                val w = pix.width
                val h = pix.height
                val s = pix.samples
                val stride = pix.stride
                val n = pix.numberOfComponents + (if (pix.alpha) 1 else 0)
                val rgb = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val o = y * stride + x * n
                    rgb[y * w + x] = ((s[o].toInt() and 0xff) shl 16) or ((s[o + 1].toInt() and 0xff) shl 8) or (s[o + 2].toInt() and 0xff)
                }
                RenderedPage(w, h, rgb)
            } finally {
                pix.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    /** Colours sampled 5% in from each corner of the rendered page. */
    fun renderCornerColors(file: File, pageIndex: Int): CornerColors {
        val r = render(file, pageIndex, 200)
        val dx = maxOf(1, r.width / 20)
        val dy = maxOf(1, r.height / 20)
        return CornerColors(
            r.at(dx, dy), r.at(r.width - 1 - dx, dy),
            r.at(dx, r.height - 1 - dy), r.at(r.width - 1 - dx, r.height - 1 - dy),
        )
    }

    /** True when every channel of [a] and [b] differs by at most [tolerance]. */
    fun colorNear(a: Int, b: Int, tolerance: Int = 60): Boolean =
        (0..2).all { k -> kotlin.math.abs((a shr (8 * k) and 0xff) - (b shr (8 * k) and 0xff)) <= tolerance }

    /* ------------------------------------------------------------------ internals */

    private class Built(val doc: PDFDocument) {
        fun save(f: File, options: String = SAVE_OPTIONS) {
            try { doc.save(f.absolutePath, options) } finally { doc.destroy() }
        }
    }

    private inline fun buildPdf(block: (PDFDocument) -> Unit): Built {
        val doc = PDFDocument()
        try {
            block(doc)
        } catch (t: Throwable) {
            doc.destroy()
            throw t
        }
        return Built(doc)
    }

    private inline fun <T> withDoc(file: File, block: (Document) -> T): T {
        val doc = Document.openDocument(file.absolutePath)
        try {
            if (doc.needsPassword()) doc.authenticatePassword(USER_PASSWORD)
            return block(doc)
        } finally {
            doc.destroy()
        }
    }

    private inline fun <T> PDFObject.use(block: (PDFObject) -> T): T = try { block(this) } finally { destroyObj(this) }

    private fun destroyObj(o: PDFObject) { if (o !== PDFObject.Null) o.destroy() }

    private fun nameOf(obj: PDFObject, key: String): String? = obj.get(key).use { if (it.isName) it.asName() else null }

    private fun intOf(obj: PDFObject, key: String): Int = obj.get(key).use { if (it.isNumber) it.asInteger() else 0 }

    private fun imageDict(w: Int, h: Int, bpc: Int, colorSpace: Any, filter: String): Map<String, Any> = mapOf(
        "Type" to "/XObject", "Subtype" to "/Image", "Width" to w, "Height" to h,
        "BitsPerComponent" to bpc, "ColorSpace" to colorSpace, "Filter" to filter,
    )

    private fun addRawPage(doc: PDFDocument, dict: Map<String, Any>, data: ByteArray, w: Int, h: Int, text: String) {
        val ref = addRaw(doc, dict, data)
        try { addPage(doc, w.toFloat(), h.toFloat(), ref, text) } finally { ref.destroy() }
    }

    /**
     * Appends a [w]x[h] pt page. If [image] is non-null it is drawn over the full page as /Im0;
     * if [text] is non-null it is written in Helvetica 24 pt near the top left.
     */
    private fun addPage(doc: PDFDocument, w: Float, h: Float, image: PDFObject?, text: String?) {
        val res = doc.newDictionary()
        val toDestroy = ArrayList<PDFObject>()
        var font: Font? = null
        try {
            val sb = StringBuilder()
            if (image != null) {
                val xo = doc.newDictionary().also { toDestroy.add(it) }
                xo.put("Im0", image)
                res.put("XObject", xo)
                sb.append("q ${num(w)} 0 0 ${num(h)} 0 0 cm /Im0 Do Q\n")
            }
            if (text != null) {
                font = Font("Helvetica")
                val fontRef = doc.addSimpleFont(font, Font.SIMPLE_ENCODING_LATIN).also { toDestroy.add(it) }
                val fonts = doc.newDictionary().also { toDestroy.add(it) }
                fonts.put("F1", fontRef)
                res.put("Font", fonts)
                val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                sb.append("BT /F1 24 Tf 72 ${num(h - 100f)} Td ($escaped) Tj ET\n")
            }
            val page = doc.addPage(Rect(0f, 0f, w, h), 0, res, sb.toString())
            try { doc.insertPage(-1, page) } finally { page.destroy() }
        } finally {
            for (o in toDestroy) o.destroy()
            res.destroy()
            font?.destroy()
        }
    }

    private fun num(f: Float): String = String.format(Locale.ROOT, "%.4f", f)

    /** Adds an encoded stream with a hand-made dictionary; returns the indirect ref (caller destroys). */
    private fun addRaw(doc: PDFDocument, dict: Map<String, Any>, data: ByteArray): PDFObject {
        val d = obj(doc, dict)
        val buf = Buffer()
        try {
            buf.writeBytes(data)
            // Binding bug: PDFDocument.addObject returns a dangling wrapper; addRawStream is safe.
            return doc.addRawStream(buf, d)
        } finally {
            buf.destroy()
            d.destroy()
        }
    }

    /**
     * Builds a direct PDF object: Int, Float, Boolean, "/Name", other String (PDF string),
     * ByteArray (byte string), List (array), Map (dictionary). Caller destroys the result.
     */
    private fun obj(doc: PDFDocument, v: Any): PDFObject = when (v) {
        is Int -> doc.newInteger(v)
        is Float -> doc.newReal(v)
        is Boolean -> doc.newBoolean(v)
        is ByteArray -> doc.newByteString(v)
        is String -> if (v.startsWith("/")) doc.newName(v.substring(1)) else doc.newString(v)
        // Binding bug: PDFObject.push(PDFObject) double-frees; append with put(size(), o).
        is List<*> -> doc.newArray().also { a -> for (e in v) { val o = obj(doc, e!!); a.put(a.size(), o); o.destroy() } }
        is Map<*, *> -> doc.newDictionary().also { d -> for ((k, e) in v) { val o = obj(doc, e!!); d.put(k as String, o); o.destroy() } }
        else -> error("unsupported ${v.javaClass}")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater(6)
        try {
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!def.finished()) out.write(chunk, 0, def.deflate(chunk))
            return out.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun randomBytes(n: Int, seed: Long): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun clamp(v: Int) = v.coerceIn(0, 255)

    /** Photo-like pixel source: gradient with reddish top-left, bluish bottom-right, plus seeded noise. */
    private fun photoPixels(w: Int, h: Int, seed: Long): (Int, Int) -> Int {
        val rnd = Random(seed)
        return { x, y ->
            var r = 90 + 100 * x / w
            val g = 110
            var b = 90 + 100 * y / h
            if (x < w / 2 && y < h / 2) { r += 60; b -= 40 }
            if (x >= w / 2 && y >= h / 2) { b += 60; r -= 40 }
            val nz = rnd.nextInt(81) - 40
            (clamp(r + nz) shl 16) or (clamp(g + rnd.nextInt(81) - 40) shl 8) or clamp(b + nz)
        }
    }

    /** Binary PPM (P6). [px] returns 0xRRGGBB; must be called in row-major order (y outer). */
    private fun ppm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
        val header = "P6\n$w $h\n255\n".toByteArray()
        val out = ByteArray(header.size + w * h * 3)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) {
            val c = px(x, y)
            out[i++] = (c shr 16).toByte(); out[i++] = (c shr 8).toByte(); out[i++] = c.toByte()
        }
        return out
    }

    private fun pgm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
        val header = "P5\n$w $h\n255\n".toByteArray()
        val out = ByteArray(header.size + w * h)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) out[i++] = px(x, y).toByte()
        return out
    }

    private fun pam(w: Int, h: Int, depth: Int, tupl: String, px: (Int, Int) -> IntArray): ByteArray {
        val header = "P7\nWIDTH $w\nHEIGHT $h\nDEPTH $depth\nMAXVAL 255\nTUPLTYPE $tupl\nENDHDR\n".toByteArray()
        val out = ByteArray(header.size + w * h * depth)
        System.arraycopy(header, 0, out, 0, header.size)
        var i = header.size
        for (y in 0 until h) for (x in 0 until w) for (v in px(x, y)) out[i++] = v.toByte()
        return out
    }

    private fun encodeJpeg(raw: ByteArray, quality: Int): ByteArray {
        val img = Image(raw)
        try {
            val pix = img.toPixmap()
            try {
                val buf = pix.asJPEG(quality, false)
                try { return buf.asByteArray() } finally { buf.destroy() }
            } finally {
                pix.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    private fun encodePng(raw: ByteArray): ByteArray {
        val img = Image(raw)
        try {
            val pix = img.toPixmap()
            try {
                val buf = pix.asPNG()
                try { return buf.asByteArray() } finally { buf.destroy() }
            } finally {
                pix.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    /**
     * Inserts a minimal big-endian EXIF APP1 segment (IFD0: Orientation = [exif]) directly after SOI.
     * MuPDF only inspects the first APP marker, so it must come first.
     */
    private fun withExifOrientation(jpeg: ByteArray, exif: Int): ByteArray {
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val payload = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, // TIFF header, IFD0 at 8
            0, 1, // 1 entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, exif.toByte(), 0, 0, // Orientation SHORT x1
            0, 0, 0, 0, // next IFD = none
        )
        val len = payload.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
    }
}
