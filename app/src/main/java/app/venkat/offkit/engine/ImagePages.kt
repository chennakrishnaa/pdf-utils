package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale

/** [file] could not be decoded as an image (unknown format, unreadable or missing). */
class UnsupportedImageFileException(val file: File, cause: Throwable?) :
    RuntimeException("unsupported image file: ${file.name}", cause)

/** Builds PDF pages that show one image each. Not thread-safe; see [PdfEngine]. */
internal object ImagePages {
    /** PDF's maximum page dimension in points (14400 pt = 200 in). */
    private const val MAX_PAGE_EDGE = 14400f

    /** Margin around the image on fixed paper sizes (A4, Letter). */
    private const val PAPER_MARGIN = 24f

    private enum class Format { JPEG, PNG, OTHER }

    /**
     * An opened image. [img] is created from exactly the bytes that `addImage` embeds; [dataLength] is
     * their count (for a JPEG: the image up to its end marker, minus stripped metadata segments).
     * [orientation] is MuPDF's orientation code of the ORIGINAL file: when metadata was stripped,
     * `img.orientation` is 0 and must not be used.
     */
    private class Opened(val img: Image, val format: Format, val dataLength: Long, val orientation: Int)

    /**
     * Opens [file] as a MuPDF [Image] (caller must `destroy()` it) after checking that it is complete.
     * See [open] for the rules. JPEG metadata is stripped, so the returned image's `orientation` may
     * be 0 even when the file carries an EXIF orientation.
     *
     * @throws UnsupportedImageFileException if the file is missing, unreadable, not an image MuPDF
     *   can decode, truncated, or has no pixels.
     */
    fun loadImage(file: File): Image = open(file).img

    /**
     * Checks [file] structurally, then opens it. MuPDF decodes lazily and silently accepts a cut-off
     * file, so completeness is checked here, in one streaming pass ([jpegScan] / [pngEnd]):
     * - JPEG (`FF D8 FF`): marker segments are walked from the start up to the end-of-image marker.
     * - PNG: chunks are walked from the signature up to `IEND`.
     * - Anything else is rejected as unsupported before any MuPDF call (SR-4); the app's
     *   ImageNormalizer transcodes other formats to JPEG/PNG with the same signatures first.
     *
     * Data after the end of the image (motion-photo videos, camera trailers, padding) is accepted
     * but never handed to MuPDF. JPEG metadata segments (see [isJpegMetadata]: EXIF with GPS,
     * camera details and thumbnail, XMP, IPTC, comments) are removed too, losslessly: everything
     * else, including all data from the first start-of-scan on, is kept byte for byte. When
     * anything is removed, the kept bytes are read into memory once and the image is created from
     * them; the EXIF orientation is first read from the original header ([jpegOrientation]). With
     * nothing to remove the file is opened by path, exactly as before.
     */
    private fun open(file: File): Opened {
        val format: Format
        val length: Long
        val end: Long
        var layout: JpegLayout? = null
        var png: PngLayout? = null
        var declaredW = -1L
        var declaredH = -1L
        try {
            format = formatOf(file)
            length = file.length()
            end = when (format) {
                Format.JPEG -> jpegScan(file)?.also { layout = it }?.end
                Format.PNG -> pngScan(file)?.also { png = it; declaredW = it.width; declaredH = it.height }?.end
                // SR-4: only positively identified, gated formats ever reach MuPDF. MuPDF would
                // recognise a JPEG from `FF D8` alone (and GIF, BMP, TIFF, ...) with no size gate.
                Format.OTHER -> null
            } ?: throw UnsupportedImageFileException(file, null)
        } catch (e: IOException) {
            throw UnsupportedImageFileException(file, e)
        }
        // Declared-size gate, before ANY MuPDF call on the file (see ImageLimits).
        val verdict = when (format) {
            Format.JPEG -> layout!!.let { l ->
                l.frame?.let { declaredW = it.width.toLong(); declaredH = it.height.toLong() }
                ImageLimits.jpeg(l.frame, l.frameCount, l.firstScanComponents)
            }
            Format.PNG -> png!!.let { ImageLimits.png(it.width, it.height, it.bitDepth, it.colorType, it.transparency) }
            Format.OTHER -> ImageLimits.Verdict.UNSUPPORTED // unreachable: rejected above
        }
        when (verdict) {
            ImageLimits.Verdict.OK -> Unit
            ImageLimits.Verdict.UNSUPPORTED -> throw UnsupportedImageFileException(file, null)
            ImageLimits.Verdict.TOO_LARGE -> throw ImageTooLargeException(file)
        }
        // Edits: metadata segments are dropped, kept JFIF/Adobe segments rewritten bare (ascending, disjoint).
        val edits = (layout?.metadata.orEmpty().map { Rewrite(it, ByteArray(0)) } + layout?.rewrites.orEmpty())
            .sortedBy { it.span.start }
        var orientation = -1
        val img = try {
            when {
                edits.isNotEmpty() -> {
                    orientation = jpegOrientation(file, layout!!)
                    Image(readKept(file, end, edits))
                }
                end >= length -> Image(file.absolutePath)
                else -> Image(readPrefix(file, end))
            }
        } catch (e: IOException) {
            throw UnsupportedImageFileException(file, e)
        } catch (e: RuntimeException) {
            throw UnsupportedImageFileException(file, e)
        }
        try {
            if (img.width <= 0 || img.height <= 0) throw UnsupportedImageFileException(file, null)
            // MuPDF must work with exactly the size the gate checked.
            if (img.width.toLong() != declaredW || img.height.toLong() != declaredH) {
                throw UnsupportedImageFileException(file, null)
            }
            if (orientation < 0) orientation = img.orientation
            return Opened(img, format, end + edits.sumOf { it.bytes.size - (it.span.end - it.span.start) }, orientation)
        } catch (t: Throwable) {
            img.destroy()
            throw t
        }
    }

    private fun readPrefix(file: File, n: Long): ByteArray {
        if (n > Int.MAX_VALUE - 16) throw IOException("image too large")
        return ByteArray(n.toInt()).also { b -> RandomAccessFile(file, "r").use { it.readFully(b) } }
    }

    /**
     * Bytes `[0, end)` of [file] with each edit's span (ascending, disjoint) replaced by its bytes
     * (empty = dropped), in one array.
     */
    private fun readKept(file: File, end: Long, edits: List<Rewrite>): ByteArray {
        val n = end + edits.sumOf { it.bytes.size - (it.span.end - it.span.start) }
        if (n > Int.MAX_VALUE - 16) throw IOException("image too large")
        val out = ByteArray(n.toInt())
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            var o = 0
            for (e in edits + Rewrite(Span(end, end), ByteArray(0))) {
                val k = (e.span.start - pos).toInt()
                raf.seek(pos)
                raf.readFully(out, o, k)
                o += k
                e.bytes.copyInto(out, o)
                o += e.bytes.size
                pos = e.span.end
            }
        }
        return out
    }

    /**
     * MuPDF's orientation code for the JPEG [file], read from its unmodified header only: the bytes
     * up to the end of the first start-of-scan header, followed by an end-of-image marker. MuPDF
     * only parses the header when an [Image] is created, so this is cheap and uses MuPDF's own EXIF
     * reading (which, for example, only looks at the first saved APP1/APP2/APP13 segment).
     */
    private fun jpegOrientation(file: File, layout: JpegLayout): Int {
        val headerEnd = if (layout.scanStart > 0) layout.scanStart else layout.end
        val head = readPrefix(file, headerEnd)
        val probe = Image(head + byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        try {
            return probe.orientation
        } finally {
            probe.destroy()
        }
    }

    /**
     * JPEG (`FF D8 FF`) or PNG (the full 8-byte signature), the same signatures as
     * `core.ImageNormalizer.sniff`; anything else is [Format.OTHER] and rejected by [open] (SR-4).
     */
    private fun formatOf(file: File): Format {
        val head = ByteArray(8)
        val n = RandomAccessFile(file, "r").use { raf -> raf.read(head).let { if (it < 0) 0 else it } }
        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return Format.JPEG
        val png = n >= 8 && PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it].toByte() }
        return if (png) Format.PNG else Format.OTHER
    }

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Sequential reader with its own buffer and position; [read] returns -1 at end of file. */
    private class ByteReader(private val input: InputStream) {
        private val buf = ByteArray(64 * 1024)
        private var n = 0
        private var i = 0
        var pos = 0L
            private set

        fun read(): Int {
            if (i == n) {
                n = input.read(buf, 0, buf.size)
                i = 0
                if (n <= 0) { n = 0; return -1 }
            }
            pos++
            return buf[i++].toInt() and 0xFF
        }

        /** Reads up to [count] bytes into [dst]; returns how many were read (fewer only at end of file). */
        fun read(dst: ByteArray, count: Int): Int {
            var k = 0
            while (k < count) {
                val b = read()
                if (b < 0) break
                dst[k++] = b.toByte()
            }
            return k
        }

        /** Skips exactly [count] bytes; false if the file ends first. */
        fun skip(count: Long): Boolean {
            var left = count
            while (left > 0) {
                if (i == n) {
                    if (read() < 0) return false
                    left--
                    continue
                }
                val k = minOf(left, (n - i).toLong()).toInt()
                i += k
                pos += k
                left -= k
            }
            return true
        }
    }

    /**
     * Offset just past the JPEG end-of-image marker `FF D9` of [file], or null if the file ends
     * first (truncated) or a segment length is invalid. Single streaming pass:
     * - after SOI `FF D8`, each marker is `FF` (any number of `FF` fill bytes) + code; outside
     *   entropy data a non-`FF` byte where a marker is expected is skipped (resync to the next `FF`,
     *   as libjpeg does);
     * - standalone markers (`01`, `D0`-`D8`) have no length; every other marker except EOI is
     *   followed by a 2-byte big-endian length (>= 2, includes itself) and skipped by it, so APPn
     *   payloads (EXIF thumbnails with their own `FF D9`, MPF data) are never scanned;
     * - after a start-of-scan `FF DA` header, entropy-coded data is scanned: `FF 00` (stuffing),
     *   `FF D0`-`FF D7` (restart) and `FF FF` (fill) are data; any other `FF xx` is a marker and
     *   continues the walk (DHT, DNL, further SOS for progressive files, ...);
     * - `FF D9` ends the image.
     */
    internal fun jpegEnd(file: File): Long? = jpegScan(file)?.end

    internal fun jpegEnd(input: InputStream): Long? = jpegScan(input)?.end

    /** Byte range `[start, end)` of a file. */
    internal data class Span(val start: Long, val end: Long)

    /**
     * Result of [jpegScan]: [end] is the offset just past `FF D9`; [scanStart] the offset just past
     * the first start-of-scan header (-1 if there is none); [metadata] the segments before it that
     * [isJpegMetadata] selects, ascending, each from its first `FF` (fill bytes included) to the end
     * of its payload.
     */
    internal class JpegLayout(
        val end: Long,
        val scanStart: Long,
        val metadata: List<Span>,
        /** The first frame header (SOFn) before the first start-of-scan, or null if there is none. */
        val frame: JpegFrame? = null,
        /** Number of frame headers in the whole walked range (before and after the first scan). */
        val frameCount: Int = 0,
        /** Component count of the first start-of-scan header; -1 if there is none. */
        val firstScanComponents: Int = -1,
        /** Kept segments to replace by their bare defined header (see [isJpegMetadata]); ascending. */
        val rewrites: List<Rewrite> = emptyList(),
    )

    /** Replace bytes [span] of the file by [bytes]. */
    internal class Rewrite(val span: Span, val bytes: ByteArray)

    /** A JPEG frame header: SOF [marker] (`0xC0`..`0xCF`), declared size and component count. */
    internal class JpegFrame(val marker: Int, val width: Int, val height: Int, val components: Int)

    internal fun jpegScan(file: File): JpegLayout? = FileInputStream(file).use { jpegScan(it) }

    /** True for the frame-header markers SOF0-SOF15 (`C4` DHT, `C8` JPG and `CC` DAC are not frames). */
    private fun isSof(b: Int) = b in 0xC0..0xCF && b != 0xC4 && b != 0xC8 && b != 0xCC

    /**
     * The structural walk documented at [jpegEnd]. Additionally, before the first start-of-scan,
     * the first [META_PEEK] payload bytes of each APPn/COM segment are read to classify it with
     * [isJpegMetadata]. Nothing after the first SOS is ever classified. Frame headers (SOFn) are
     * counted everywhere; the first one before the first SOS is parsed into [JpegLayout.frame], and
     * the component count of the first SOS header is recorded (see [ImageLimits.jpeg]).
     *
     * With [headerOnly] the walk stops right after the first SOS header and returns a layout whose
     * `end` is -1 (or null if the data ends first); entropy data is never read. Used to inspect
     * JPEG streams inside PDFs, which need not have an end-of-image marker.
     */
    internal fun jpegScan(input: InputStream, headerOnly: Boolean = false): JpegLayout? {
        val r = ByteReader(input)
        if (r.read() != 0xFF || r.read() != 0xD8) return null
        val metadata = ArrayList<Span>()
        var scanStart = -1L
        val head = ByteArray(META_PEEK)
        var frame: JpegFrame? = null
        var frameCount = 0
        var scanComponents = -1
        val rewrites = ArrayList<Rewrite>()
        while (true) {
            var b = r.read()
            if (b < 0) return null
            if (b != 0xFF) continue // entropy data, or garbage between segments
            val markerStart = r.pos - 1
            do b = r.read() while (b == 0xFF)
            when {
                b < 0 -> return null
                b == 0xD9 -> return JpegLayout(r.pos, scanStart, metadata, frame, frameCount, scanComponents, rewrites)
                b == 0x00 -> continue // stuffed byte (or stray FF 00 outside a scan)
                b in 0xD0..0xD7 -> continue // restart marker
                b == 0x01 || b == 0xD8 -> continue // standalone markers
                else -> { // segment with a length; after an SOS header the loop scans entropy data
                    val hi = r.read()
                    val lo = r.read()
                    if (lo < 0) return null
                    val len = (hi shl 8) or lo
                    if (len < 2) return null
                    var payload = len - 2L
                    if (scanStart < 0 && (b in 0xE0..0xEF || b == 0xFE)) {
                        val k = r.read(head, minOf(payload, META_PEEK.toLong()).toInt())
                        if (k < minOf(payload, META_PEEK.toLong())) return null
                        payload -= k
                        if (isJpegMetadata(b, head, k)) {
                            metadata.add(Span(markerStart, r.pos + payload))
                        } else if (k == META_PEEK && payload > 0 && (b == 0xE0 || b == 0xEE)) {
                            // Kept JFIF / Adobe segment with bytes beyond its defined header: rewrite it bare.
                            val tail = if (b == 0xE0) byteArrayOf(0, 0) else ByteArray(0) // JFIF: thumbnail 0x0
                            val seg = byteArrayOf(0xFF.toByte(), b.toByte(), 0, (2 + k + tail.size).toByte()) + head + tail
                            rewrites.add(Rewrite(Span(markerStart, r.pos + payload), seg))
                        }
                    }
                    if (isSof(b)) {
                        frameCount++
                        if (frame == null && scanStart < 0 && payload < 6) {
                            frame = JpegFrame(b, 0, 0, 0) // too short to be a frame header: rejected by the gate
                        } else if (frame == null && scanStart < 0) {
                            if (r.read(head, 6) < 6) return null
                            payload -= 6
                            frame = JpegFrame(
                                marker = b,
                                height = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF),
                                width = ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF),
                                components = head[5].toInt() and 0xFF,
                            )
                        }
                    }
                    if (b == 0xDA && scanStart < 0 && payload >= 1) {
                        scanComponents = r.read()
                        if (scanComponents < 0) return null
                        payload -= 1
                    }
                    if (!r.skip(payload)) return null
                    if (b == 0xDA && scanStart < 0) {
                        scanStart = r.pos
                        if (headerOnly) return JpegLayout(-1, scanStart, metadata, frame, frameCount, scanComponents)
                    }
                }
            }
        }
    }

    /** Payload bytes inspected per APPn/COM segment: the length of `ICC_PROFILE\0`. */
    private const val META_PEEK = 12

    /**
     * True when the JPEG segment with [marker] (whose payload starts with the first [n] bytes of
     * [head]) is metadata that the embedded image does not need. Checked against libjpeg (jdmarker.c)
     * and MuPDF 1.28.5 (load-jpeg.c, filter-dct.c), which read only: APP0 `JFIF\0` (colour space
     * guess, density), APP14 `Adobe` (colour transform, CMYK inversion), APP2 `ICC_PROFILE\0` (colour
     * profile), and APP1 EXIF / APP13 Photoshop (orientation and resolution, used for placement only
     * and taken from the original header instead, see [jpegOrientation]). So everything else is
     * dropped: APP0 other than JFIF (JFXX thumbnails), APP1 (EXIF incl. GPS, thumbnail; XMP), APP2
     * other than ICC (MPF), APP3-APP13, APP14 other than Adobe, APP15 and COM.
     *
     * Kept segments are cleaned by [jpegScan] (security audit SA-7): a JFIF APP0 is rewritten to its
     * bare 16-byte header (version, units, densities, thumbnail 0×0, so an embedded JFIF thumbnail
     * and any trailing bytes are dropped); an Adobe APP14 is cut to its 12 defined payload bytes
     * (version, flags, colour transform, so the transform is still honoured). ICC_PROFILE APP2 chunks
     * are kept unchanged because the colours depend on them: text tags inside the profile (its
     * description, copyright, device model) therefore survive.
     */
    private fun isJpegMetadata(marker: Int, head: ByteArray, n: Int): Boolean = when (marker) {
        0xE0 -> !startsWith(head, n, JFIF)
        0xE2 -> !startsWith(head, n, ICC_PROFILE)
        0xEE -> !startsWith(head, n, ADOBE)
        else -> marker in 0xE1..0xEF || marker == 0xFE
    }

    private fun startsWith(head: ByteArray, n: Int, sig: ByteArray): Boolean =
        n >= sig.size && sig.indices.all { head[it] == sig[it] }

    private val JFIF = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ICC_PROFILE = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ADOBE = "Adobe".toByteArray(Charsets.ISO_8859_1)

    /**
     * Offset just past the CRC of the PNG `IEND` chunk of [file], or null if the file ends before
     * it or a chunk runs past the end of the file. Chunks are skipped by seeking; CRCs are not checked.
     */
    internal fun pngEnd(file: File): Long? = pngScan(file)?.end

    /**
     * Result of [pngScan]: [end] as for [pngEnd]; [width]/[height] from the IHDR chunk as unsigned
     * values and its [bitDepth] / [colorType] bytes, all -1 when the first chunk is not a 13-byte
     * IHDR (such a file is rejected); [transparency] if the file has a `tRNS` chunk (which makes
     * MuPDF decode an alpha channel).
     */
    internal class PngLayout(
        val end: Long,
        val width: Long,
        val height: Long,
        val bitDepth: Int,
        val colorType: Int,
        val transparency: Boolean,
    )

    /** The walk of [pngEnd], also reading the declared size from the first chunk (IHDR). */
    internal fun pngScan(file: File): PngLayout? {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            var pos = 8L
            var width = -1L
            var height = -1L
            var bitDepth = -1
            var colorType = -1
            var transparency = false
            while (pos + 12 <= fileLen) {
                raf.seek(pos)
                val len = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = raf.readInt()
                val next = pos + 12 + len
                if (next > fileLen) return null
                if (pos == 8L && type == IHDR && len == 13L) {
                    width = raf.readInt().toLong() and 0xFFFFFFFFL
                    height = raf.readInt().toLong() and 0xFFFFFFFFL
                    bitDepth = raf.readUnsignedByte()
                    colorType = raf.readUnsignedByte()
                }
                if (type == TRNS) transparency = true
                if (type == IEND) return PngLayout(next, width, height, bitDepth, colorType, transparency)
                pos = next
            }
            return null
        }
    }

    private const val IEND = 0x49454E44 // "IEND"
    private const val IHDR = 0x49484452 // "IHDR"
    private const val TRNS = 0x74524E53 // "tRNS"

    /**
     * Downscales [img] so that its long edge is at most [level]'s `maxLongEdgePx` (never upscales)
     * and encodes it as JPEG at the level's quality. The STORED pixels are drawn (drawing an [Image]
     * into a [Pixmap] does not apply EXIF orientation), so the caller keeps using the original's
     * orientation when placing it. One-component images are encoded as gray, everything else as
     * RGB; transparency is flattened onto white. This one routine is used both for embedding and for
     * [estimate], so estimates are the real stream sizes. The caller must `destroy()` the result.
     */
    fun encodeJpeg(img: Image, level: CompressionLevel): Buffer {
        val scale = minOf(1.0, level.maxLongEdgePx.toDouble() / max(img.width, img.height))
        val nw = max(1, (img.width * scale).roundToInt())
        val nh = max(1, (img.height * scale).roundToInt())
        val gray = img.numberOfComponents == 1 && !img.imageMask
        val cs = if (gray) ColorSpace.DeviceGray else ColorSpace.DeviceRGB // singletons: never destroy
        val pix = Pixmap(cs, nw, nh, false)
        try {
            pix.clear(255)
            val dev = DrawDevice(pix)
            try {
                dev.fillImage(img, Matrix(nw.toFloat(), 0f, 0f, nh.toFloat(), 0f, 0f), 1f, 0)
                dev.close()
            } finally {
                dev.destroy()
            }
            return pix.asJPEG(level.jpegQuality, false)
        } finally {
            pix.destroy()
        }
    }

    /**
     * Bytes of image stream data that `addImage` embeds for [opened], unchanged: [Opened.dataLength]
     * for a JPEG (embedded verbatim as DCTDecode, without trailer or metadata). Anything else (PNG) is added to
     * a scratch document, where MuPDF stores raw pixels that the final save (`compress` option)
     * deflates with zlib at the default level; that deflate is reproduced here with
     * [java.util.zip.Deflater] at the same level, for the image stream plus its /SMask stream.
     */
    private fun originalEmbeddedSize(opened: Opened): Long {
        if (opened.format == Format.JPEG) return opened.dataLength
        val scratch = PDFDocument()
        try {
            val ref = scratch.addImage(opened.img)
            try {
                var total = savedStreamSize(ref)
                val smask = ref.get("SMask")
                if (smask !== PDFObject.Null) {
                    try {
                        if (smask.isStream) total += savedStreamSize(smask)
                    } finally {
                        smask.destroy()
                    }
                }
                return total
            } finally {
                ref.destroy()
            }
        } finally {
            scratch.destroy()
        }
    }

    /**
     * Runs [block], which decodes or embeds the picked image [file]. A MuPDF failure there (for
     * example a PNG with corrupt IDAT data: "ran out of output before input", or "Overly large
     * image") means the image is unusable, so it becomes [UnsupportedImageFileException]; failures
     * that are really out-of-memory (see [isOutOfMemoryFailure]) propagate unchanged.
     */
    private inline fun <T> decoding(file: File, block: () -> T): T = try {
        block()
    } catch (e: UnsupportedImageFileException) {
        throw e
    } catch (e: RuntimeException) {
        if (isOutOfMemoryFailure(e)) throw e
        throw UnsupportedImageFileException(file, e)
    }

    /** Size of stream [obj] after a `compress` save: unfiltered data is deflated, filtered data kept. */
    private fun savedStreamSize(obj: PDFObject): Long {
        val raw = obj.readRawStream()
        val filter = obj.get("Filter")
        val filtered = filter !== PDFObject.Null
        if (filtered) filter.destroy()
        if (filtered) return raw.size.toLong()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val chunk = ByteArray(64 * 1024)
            var n = 0L
            while (!deflater.finished()) n += deflater.deflate(chunk)
            return n
        } finally {
            deflater.end()
        }
    }

    /**
     * Size estimate for [file] (see [PdfEngine.estimateImage]). The image is opened once and each
     * level is derived from that same [Image] with [encodeJpeg] (MuPDF caches the decoded pixels).
     *
     * @throws UnsupportedImageFileException if the image cannot be used.
     */
    fun estimate(file: File): PdfEstimate.Ok {
        val opened = open(file)
        val img = opened.img
        try {
            return decoding(file) {
                val original = originalEmbeddedSize(opened)
                val byLevel = CompressionLevel.entries.associateWith { level ->
                    val jpeg = encodeJpeg(img, level)
                    try {
                        minOf(jpeg.length.toLong(), original)
                    } finally {
                        jpeg.destroy()
                    }
                }
                PdfEstimate.Ok(original, byLevel)
            }
        } finally {
            img.destroy()
        }
    }

    /**
     * Adds the image XObject for [img] to [doc]: unchanged when [compression] is null, otherwise the
     * [encodeJpeg] version if it is strictly smaller than [originalEmbeddedSize], else unchanged.
     */
    private fun addImageObject(doc: PDFDocument, opened: Opened, compression: CompressionLevel?): PDFObject {
        val img = opened.img
        if (compression == null) return doc.addImage(img)
        val jpeg = encodeJpeg(img, compression)
        try {
            if (jpeg.length.toLong() >= originalEmbeddedSize(opened)) return doc.addImage(img)
            val small = Image(jpeg)
            try {
                return doc.addImage(small)
            } finally {
                small.destroy()
            }
        } finally {
            jpeg.destroy()
        }
    }

    /**
     * Appends one page to [doc] showing [image] upright (EXIF orientation applied); with
     * [compression] null, JPEG pixel data is embedded unchanged (up to its end-of-image marker;
     * trailing data and metadata segments such as EXIF/GPS are dropped, see [open]). With a [compression] level the embedded pixels may be downscaled and re-encoded (see [addImageObject]); the page box and placement
     * never depend on [compression]: they always come from the original image.
     *
     * - [fixedSize] non-null: the page box is exactly `fixedSize.width x fixedSize.height` (origin 0,0)
     *   and the image is fitted inside it without margin, centred; [pageSize] is ignored.
     * - [PageSize.FIT_IMAGE]: page box = displayed image size at 1 px = 1 pt, scaled down
     *   proportionally if the long edge exceeds 14400 pt.
     * - [PageSize.A4] / [PageSize.LETTER]: portrait, or landscape when the displayed image is wider
     *   than tall; image scaled (up or down) to fit inside a 24 pt margin, centred.
     *
     * @throws UnsupportedImageFileException if [image] cannot be decoded.
     */
    fun appendImagePage(
        doc: PDFDocument,
        image: File,
        pageSize: PageSize,
        fixedSize: Rect? = null,
        compression: CompressionLevel? = null,
    ) {
        val opened = open(image)
        val img = opened.img
        try {
            val orientation = opened.orientation
            val quarterTurn = isQuarterTurn(orientation)
            val dw = (if (quarterTurn) img.height else img.width).toFloat() // displayed width, px
            val dh = (if (quarterTurn) img.width else img.height).toFloat() // displayed height, px
            if (dw <= 0f || dh <= 0f) throw UnsupportedImageFileException(image, null)

            val pageW: Float
            val pageH: Float
            val margin: Float
            when {
                fixedSize != null -> {
                    pageW = fixedSize.x1 - fixedSize.x0
                    pageH = fixedSize.y1 - fixedSize.y0
                    margin = 0f
                }
                pageSize == PageSize.FIT_IMAGE -> {
                    val s = minOf(1f, MAX_PAGE_EDGE / maxOf(dw, dh))
                    pageW = dw * s
                    pageH = dh * s
                    margin = 0f
                }
                else -> {
                    val (shortEdge, longEdge) = if (pageSize == PageSize.A4) 595f to 842f else 612f to 792f
                    val landscape = dw > dh
                    pageW = if (landscape) longEdge else shortEdge
                    pageH = if (landscape) shortEdge else longEdge
                    margin = PAPER_MARGIN
                }
            }
            // Fit the displayed image into the box inside the margin, preserving aspect, centred.
            val boxW = pageW - 2 * margin
            val boxH = pageH - 2 * margin
            val scale = minOf(boxW / dw, boxH / dh)
            val w = dw * scale
            val h = dh * scale
            val x = (pageW - w) / 2
            val y = (pageH - h) / 2

            val cm = uprightCm(orientation, x, y, w, h)
            val ref = decoding(image) { addImageObject(doc, opened, compression) }
            val xobjects = doc.newDictionary()
            val resources = doc.newDictionary()
            try {
                xobjects.put("Im0", ref)
                resources.put("XObject", xobjects)
                val content = "q ${cm.joinToString(" ") { num(it) }} cm /Im0 Do Q"
                val page = doc.addPage(Rect(0f, 0f, pageW, pageH), 0, resources, content)
                try {
                    doc.insertPage(-1, page)
                } finally {
                    page.destroy()
                }
            } finally {
                resources.destroy()
                xobjects.destroy()
                ref.destroy()
            }
        } finally {
            img.destroy()
        }
    }

    /** Displayed size of page [pageIndex] of [doc] as `Rect(0, 0, w, h)` (honours /Rotate and the CropBox). */
    fun pageSizeOf(doc: PDFDocument, pageIndex: Int): Rect {
        val page = doc.loadPage(pageIndex)
        try {
            val b = page.bounds
            return Rect(0f, 0f, b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            page.destroy()
        }
    }

    /** True when MuPDF orientation [o] is a quarter turn, i.e. displayed width/height are swapped. */
    private fun isQuarterTurn(o: Int) = o != 0 && o % 2 == 0

    /**
     * PDF `cm` matrix [a b c d e f] that draws an image upright so that it exactly covers the
     * box (x, y, w, h) on the page (PDF user space: origin bottom-left, y up; w x h is the
     * DISPLAYED size, i.e. already swapped for quarter turns).
     *
     * [orientation] is MuPDF's value from Image.getOrientation(), NOT the EXIF value
     * (EXIF 1..8 -> 1,5,3,7,6,4,8,2; 0 = no EXIF). MuPDF's fz_image_orientation_matrix gives
     * a transform [a b c d e f] of the unit square in y-down image space. An image XObject maps
     * the unit square with image row 0 at v = 1 (y-up), so converting to PDF space and scaling to
     * the box gives:
     *   cm = [w*a, -h*b, -w*c, h*d, x + w*(c+e), y + h*(1-d-f)]
     * The (x, y) offset is a plain translation added to e and f. Proven for all 8 values
     * in the T1c spike (full-page case x = y = 0).
     */
    private fun uprightCm(orientation: Int, x: Float, y: Float, w: Float, h: Float): FloatArray {
        val m = when (orientation) {
            0, 1 -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
            2 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, 0f, -1f, 1f, 1f)
            4 -> floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f)
            5 -> floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f)
            6 -> floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f)
            7 -> floatArrayOf(1f, 0f, 0f, -1f, 0f, 1f)
            8 -> floatArrayOf(0f, -1f, -1f, 0f, 1f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f) // unknown value: draw as stored
        }
        val (a, b, c, d, e) = m
        val f = m[5]
        return floatArrayOf(w * a, -h * b, -w * c, h * d, x + w * (c + e), y + h * (1 - d - f))
    }

    /** Fixed-point, locale-independent number for content streams (no exponents, no decimal commas). */
    private fun num(v: Float): String {
        val r = if (v == 0f) 0f else v // avoid "-0.0000"
        return String.format(Locale.ROOT, "%.4f", r)
    }
}
