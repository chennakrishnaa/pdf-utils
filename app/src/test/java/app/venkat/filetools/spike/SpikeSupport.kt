package app.venkat.filetools.spike

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.Device
import java.io.File
import kotlin.random.Random

/** Save options used for every output file in the spike. */
const val SAVE_OPTS = "garbage=deduplicate,compress,objstms"

/**
 * Collects MuPDF objects and destroys them (in reverse order) on close.
 * Never destroys the shared singletons ColorSpace.Device* or PDFObject.Null.
 */
class Native : AutoCloseable {
    private val items = ArrayList<Any>()

    fun <T : Any> add(o: T?): T {
        if (o != null) items.add(o)
        @Suppress("UNCHECKED_CAST")
        return o as T
    }

    override fun close() {
        for (o in items.asReversed()) destroy(o)
        items.clear()
    }

    companion object {
        fun destroy(o: Any?) {
            when (o) {
                null -> {}
                is PDFObject -> if (o !== PDFObject.Null) o.destroy()
                is ColorSpace -> if (o !== ColorSpace.DeviceRGB && o !== ColorSpace.DeviceGray &&
                    o !== ColorSpace.DeviceCMYK && o !== ColorSpace.DeviceBGR) o.destroy()
                is Page -> o.destroy()
                is Document -> o.destroy() // PDFDocument too
                is Image -> o.destroy()
                is Pixmap -> o.destroy()
                is Buffer -> o.destroy()
                is Device -> o.destroy()
                is Font -> o.destroy()
                is Link -> o.destroy()
                is StructuredText -> o.destroy()
                is PDFGraftMap -> o.destroy()
                else -> error("don't know how to destroy ${o.javaClass}")
            }
        }
    }
}

/* ---------------- raw image byte builders (decoded by MuPDF's PNM loader) ---------------- */

/** Binary PPM (P6), RGB. [px] returns 0xRRGGBB for (x, y), y=0 is the TOP row. */
fun ppm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
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

/** Binary PGM (P5), gray. */
fun pgm(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
    val header = "P5\n$w $h\n255\n".toByteArray()
    val out = ByteArray(header.size + w * h)
    System.arraycopy(header, 0, out, 0, header.size)
    var i = header.size
    for (y in 0 until h) for (x in 0 until w) out[i++] = px(x, y).toByte()
    return out
}

/** PAM (P7) with arbitrary tuple type; [px] returns `depth` channel values. */
fun pam(w: Int, h: Int, depth: Int, tupl: String, px: (Int, Int) -> IntArray): ByteArray {
    val header = "P7\nWIDTH $w\nHEIGHT $h\nDEPTH $depth\nMAXVAL 255\nTUPLTYPE $tupl\nENDHDR\n".toByteArray()
    val out = ByteArray(header.size + w * h * depth)
    System.arraycopy(header, 0, out, 0, header.size)
    var i = header.size
    for (y in 0 until h) for (x in 0 until w) for (v in px(x, y)) out[i++] = v.toByte()
    return out
}

/** Decode raw (PNM/PAM) bytes with MuPDF and re-encode as JPEG. */
fun toJpeg(raw: ByteArray, quality: Int = 90, invertCmyk: Boolean = false): ByteArray {
    Native().use { n ->
        val img = n.add(Image(raw))
        val pix = n.add(img.toPixmap())
        return n.add(pix.asJPEG(quality, invertCmyk)).asByteArray()
    }
}

fun toPng(raw: ByteArray): ByteArray {
    Native().use { n ->
        val img = n.add(Image(raw))
        val pix = n.add(img.toPixmap())
        return n.add(pix.asPNG()).asByteArray()
    }
}

fun noise(seed: Int) = Random(seed)
fun clamp(v: Int) = v.coerceIn(0, 255)

/* ---------------- PDF building ---------------- */

/**
 * Appends a page of size [w]x[h] pt that paints [imgRef] with the given PDF `cm` matrix.
 * The image is registered as /Im0 in the page's /Resources /XObject.
 */
fun addImagePage(doc: PDFDocument, imgRef: PDFObject, w: Float, h: Float, cm: FloatArray) {
    Native().use { n ->
        val res = n.add(doc.newDictionary())
        val xobj = n.add(doc.newDictionary())
        xobj.put("Im0", imgRef)
        res.put("XObject", xobj)
        val c = cm.joinToString(" ") { fmt(it) }
        val page = n.add(doc.addPage(Rect(0f, 0f, w, h), 0, res, "q $c cm /Im0 Do Q"))
        doc.insertPage(-1, page)
    }
}

/** Full-page, upright (no rotation) image page. */
fun addImagePage(doc: PDFDocument, imgRef: PDFObject, w: Float, h: Float) =
    addImagePage(doc, imgRef, w, h, floatArrayOf(w, 0f, 0f, h, 0f, 0f))

fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

/** Appends a page with one line of Helvetica text per entry. */
fun addTextPage(doc: PDFDocument, lines: List<String>, w: Float = 595f, h: Float = 842f) {
    Native().use { n ->
        val font = n.add(Font("Helvetica"))
        val fontRef = n.add(doc.addSimpleFont(font, Font.SIMPLE_ENCODING_LATIN))
        val res = n.add(doc.newDictionary())
        val fonts = n.add(doc.newDictionary())
        fonts.put("F1", fontRef)
        res.put("Font", fonts)
        val sb = StringBuilder("BT /F1 24 Tf 72 ${fmt(h - 100)} Td 30 TL\n")
        for (l in lines) sb.append("(").append(l.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") '\n")
        sb.append("ET")
        val page = n.add(doc.addPage(Rect(0f, 0f, w, h), 0, res, sb.toString()))
        doc.insertPage(-1, page)
    }
}

fun pageText(doc: Document, i: Int): String {
    Native().use { n ->
        val page = n.add(doc.loadPage(i))
        val st = n.add(page.toStructuredText())
        return st.asText()
    }
}

fun openPdf(f: File): PDFDocument = Document.openDocument(f.absolutePath) as PDFDocument

/** Returns the /Im0 XObject of page [i] as an INDIRECT ref (caller destroys). */
fun pageImage(doc: PDFDocument, i: Int): PDFObject {
    Native().use { n ->
        val page = n.add(doc.findPage(i))
        val res = n.add(page.get("Resources"))
        val xo = n.add(res.get("XObject"))
        return xo.get("Im0")
    }
}

/* ---------------- pixmap sampling ---------------- */

/** Mean RGB over the rectangle [x0,x1) x [y0,y1) of an RGB (no alpha) pixmap. */
fun meanRgb(pix: Pixmap, x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray {
    val s = pix.samples
    val stride = pix.stride
    val nc = pix.numberOfComponents + (if (pix.alpha) 1 else 0)
    val acc = DoubleArray(3)
    var cnt = 0
    for (y in y0 until y1) for (x in x0 until x1) {
        val o = y * stride + x * nc
        for (k in 0 until 3) acc[k] += (s[o + k].toInt() and 0xff).toDouble()
        cnt++
    }
    for (k in 0 until 3) acc[k] /= cnt.toDouble()
    return acc
}

fun rgbAt(pix: Pixmap, x: Int, y: Int): IntArray = IntArray(3) { pix.getSample(x, y, it).toInt() and 0xff }

/* ---------------- EXIF splice ---------------- */

/**
 * Inserts a minimal big-endian EXIF APP1 segment (one IFD0 entry: Orientation=[exif])
 * directly after SOI. MuPDF only inspects the FIRST saved APP marker, so it must come first.
 */
fun withExifOrientation(jpeg: ByteArray, exif: Int): ByteArray {
    require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
    val payload = byteArrayOf(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, // TIFF header, IFD0 at 8
        0, 1, // 1 entry
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, exif.toByte(), 0, 0, // Orientation SHORT x1
        0, 0, 0, 0, // next IFD = none (needed: parser requires offset+12 < length)
    )
    val len = payload.size + 2
    val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
}

/**
 * PDF `cm` for drawing an image upright on a [pw]x[ph] pt page, given MuPDF's orientation
 * value (Image.getOrientation(), NOT the EXIF value). Derived from fz_image_orientation_matrix
 * (y-down unit square) converted to PDF space (y-up, image row 0 at v=1):
 *   cm = [W*a, -H*b, -W*c, H*d, W*(c+e), H*(1-d-f)]
 */
fun uprightCm(orientation: Int, pw: Float, ph: Float): FloatArray {
    val m = when (orientation) {
        0, 1 -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        2 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f)
        3 -> floatArrayOf(-1f, 0f, 0f, -1f, 1f, 1f)
        4 -> floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f)
        5 -> floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f)
        6 -> floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f)
        7 -> floatArrayOf(1f, 0f, 0f, -1f, 0f, 1f)
        8 -> floatArrayOf(0f, -1f, -1f, 0f, 1f, 1f)
        else -> error("bad orientation $orientation")
    }
    val (a, b, c, d, e) = m
    val f = m[5]
    return floatArrayOf(pw * a, -ph * b, -pw * c, ph * d, pw * (c + e), ph * (1 - d - f))
}

/** True when the displayed image is transposed (page box must swap width/height). */
fun isQuarterTurn(orientation: Int) = orientation != 0 && orientation % 2 == 0

/**
 * Builds a direct PDF object from Kotlin values: Int, Float, Boolean, "/Name", "(string)",
 * PDFObject, List (array), Map (dictionary). Caller destroys the result.
 * (The Java API has no PDF-syntax parser; addRawStream's 2nd arg must be a PDFObject.)
 */
fun PDFDocument.obj(v: Any): PDFObject = when (v) {
    is PDFObject -> v
    is Int -> newInteger(v)
    is Float -> newReal(v)
    is Boolean -> newBoolean(v)
    is ByteArray -> newByteString(v)
    is String -> if (v.startsWith("/")) newName(v.substring(1)) else newString(v)
    // GOTCHA (1.28.5 JNI bug): PDFObject.push(PDFObject) DROPS the item's native ref without
    // clearing the Java wrapper -> a later destroy()/GC finalizer double-frees (heap corruption,
    // "malloc(): unaligned tcache chunk"). Append with put(size(), obj) instead, which keeps.
    is List<*> -> newArray().also { a -> for (e in v) { val o = obj(e!!); a.put(a.size(), o); if (o !== e) Native.destroy(o) } }
    is Map<*, *> -> newDictionary().also { d ->
        for ((k, e) in v) { val o = obj(e!!); d.put(k as String, o); if (o !== e) Native.destroy(o) }
    }
    else -> error("unsupported ${v.javaClass}")
}

/** Adds an already-encoded stream with the given dictionary; returns the indirect ref. */
fun PDFDocument.addRaw(dict: Map<String, Any>, data: ByteArray): PDFObject {
    Native().use { n ->
        val d = n.add(obj(dict))
        val buf = n.add(Buffer())
        buf.writeBytes(data)
        // GOTCHA (from reading jni/pdfdocument.c, 1.28.5): avoid PDFDocument.addObject(dict):
        // the JNI calls pdf_add_object_drop and returns the *input* Java wrapper (whose native
        // ref was just dropped) instead of the new indirect ref. addRawStream/addStream are fine.
        return addRawStream(buf, d)
    }
}

/** Writes [bytes] and returns the file. */
fun File.writeAndGet(bytes: ByteArray): File { writeBytes(bytes); return this }

/** Runs [block] and returns "OK: <result>" or "<ExceptionClass>: <message>". */
inline fun outcome(block: () -> Any?): String = try {
    "OK: ${block()}"
} catch (t: Throwable) {
    "${t.javaClass.name}: ${t.message}"
}
