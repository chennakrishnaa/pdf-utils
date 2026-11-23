package app.venkat.offkit.engine

import java.io.File

/** [file] declares more pixels than [ImageLimits] allow; it was rejected before any decode. */
class ImageTooLargeException(val file: File) : RuntimeException("image too large: ${file.name}")

/**
 * Declared-size limits applied before any image is decoded (security audit SA-1/2/3/6).
 *
 * MuPDF and libjpeg size their buffers from the image's OWN header (JPEG SOFn, PNG IHDR), and a
 * header can declare billions of pixels in a few hundred bytes. Nothing downstream bounds that
 * allocation on Android (no address-space cap, memory is overcommitted, the low-memory killer kills
 * the app), so every image is checked here, from its header, before MuPDF sees it.
 *
 * Reasoning for the numbers (a phone gives an app a few hundred MB of native memory at best):
 * - A decoded RGB pixel is 3 bytes: 50 MP ≈ 150 MB raw, the largest single buffer we accept.
 * - A PNG is always decoded to a full pixmap (plus MuPDF's inflated sample buffer, which is twice
 *   as large for 16-bit samples, an alpha channel that becomes a separate SMask, and a Java-heap
 *   copy of the raw stream for the size estimate). Peak memory grows ~14 MB per MP for 8-bit RGB,
 *   and an attacker can craft a tiny PNG just under any cap, so PNG is capped twice: at
 *   [MAX_PNG_PIXELS] (20 MP) and at [MAX_PNG_SAMPLE_BYTES] of declared decoded samples (60 MB, what
 *   20 MP 8-bit RGB needs), which bounds 16-bit and alpha formats: 8-bit RGBA 15 MP, 16-bit RGB
 *   10 MP, 16-bit RGBA 7.5 MP.
 * - A progressive or multi-scan JPEG (SOF2/6/10/14, lossless/hierarchical/arithmetic types, or a
 *   sequential frame whose first scan does not carry every component) makes libjpeg buffer the
 *   WHOLE image as DCT coefficients, about 2 bytes per sample ≈ 6 B/px: 50 MP ≈ 300 MB. Capped at
 *   [MAX_BUFFERED_JPEG_PIXELS].
 * - A single-scan baseline (SOF0) or extended-sequential Huffman (SOF1) JPEG is streamed by libjpeg
 *   a few rows at a time, and MuPDF decodes it with DCT scaling (down to 1/8) when it only needs a
 *   smaller pixmap, so it may be larger: [MAX_SEQUENTIAL_JPEG_PIXELS].
 * - No edge may exceed [MAX_EDGE] (and none may be 0).
 * Peak resident memory (host x86_64, MuPDF 1.28.5, -Xmx512m, flat-colour images right at the cap,
 * worst of `estimate` and `imagesToPdf` at every level): 50 MP progressive JPEG 238 MB, 250 MP
 * baseline JPEG 159 MB; 8-bit RGB PNG: 12 MP 213 MB, 30 MP 466 MB, 50 MP 690 MB (hence the PNG
 * limits). PNGs right at the PNG limits, peak RSS of `estimate` / `imagesToPdf` Original / HIGH
 * (`estimate` is the worst, 1.2-1.9 s): 20 MP 8-bit RGB 339/192/260 MB, 20 MP interlaced 8-bit
 * RGB 338/191/261 MB, 20 MP 8-bit palette 307/191/298 MB, 15 MP 8-bit RGBA 327/191/242 MB,
 * 10 MP 16-bit RGB 234/161/168 MB, 7.5 MP 16-bit RGBA 251/163/175 MB: all under ~400 MB.
 */
internal object ImageLimits {
    /** Largest accepted width or height, in pixels. */
    const val MAX_EDGE = 65_500

    /**
     * Largest accepted PNG, in pixels (width × height), whatever its sample format. Lowered from
     * 40 MP after measuring (see the class KDoc): a flat 30 MP 8-bit RGB PNG peaked at 466 MB RSS.
     */
    const val MAX_PNG_PIXELS = 20_000_000L

    /**
     * Largest accepted PNG, in declared decoded sample bytes: width × height × channels × bytes per
     * sample, from the IHDR colour type and bit depth (see [pngBytesPerPixel]). Equal to what a
     * [MAX_PNG_PIXELS] 8-bit RGB image needs (60 MB), so e.g. a 16-bit RGBA PNG is capped at 7.5 MP.
     */
    const val MAX_PNG_SAMPLE_BYTES = MAX_PNG_PIXELS * 3

    /** Largest accepted progressive / multi-scan / non-sequential JPEG, in pixels. */
    const val MAX_BUFFERED_JPEG_PIXELS = 50_000_000L

    /** Largest accepted single-scan baseline or extended-sequential JPEG, in pixels. */
    const val MAX_SEQUENTIAL_JPEG_PIXELS = 250_000_000L

    /** Most images one Images-to-PDF or Add-images job may contain (SA-9). */
    const val MAX_IMAGES_PER_JOB = 500

    /** Largest input PDF any operation opens, in bytes (1 GiB, SA-9). */
    const val MAX_INPUT_PDF_BYTES = 1L shl 30

    enum class Verdict { OK, UNSUPPORTED, TOO_LARGE }

    /** Width/height validity shared by all formats. */
    fun dimensions(width: Long, height: Long, maxPixels: Long): Verdict = when {
        width <= 0 || height <= 0 -> Verdict.UNSUPPORTED
        width > MAX_EDGE || height > MAX_EDGE -> Verdict.TOO_LARGE
        width * height > maxPixels -> Verdict.TOO_LARGE
        else -> Verdict.OK
    }

    /** True for SOF markers that libjpeg decodes a few rows at a time (single-scan only). */
    fun isSequentialSof(marker: Int) = marker == 0xC0 || marker == 0xC1

    /**
     * Verdict for a JPEG whose single frame header is [frame] and whose first scan header lists
     * [firstScanComponents] components (-1 if there is no scan). [frameCount] is the number of
     * frame headers in the whole file; anything but exactly one is rejected.
     */
    fun jpeg(frame: ImagePages.JpegFrame?, frameCount: Int, firstScanComponents: Int): Verdict {
        if (frame == null || frameCount != 1) return Verdict.UNSUPPORTED
        if (frame.components !in ALLOWED_COMPONENTS) return Verdict.UNSUPPORTED
        val streamed = isSequentialSof(frame.marker) && firstScanComponents == frame.components
        val cap = if (streamed) MAX_SEQUENTIAL_JPEG_PIXELS else MAX_BUFFERED_JPEG_PIXELS
        return dimensions(frame.width.toLong(), frame.height.toLong(), cap)
    }

    /**
     * Verdict for a PNG with IHDR [width] × [height] (read as unsigned 32-bit values), [bitDepth]
     * and [colorType]: within [MAX_PNG_PIXELS] and [MAX_PNG_SAMPLE_BYTES]; an invalid colour type /
     * bit depth combination is unsupported.
     */
    fun png(width: Long, height: Long, bitDepth: Int, colorType: Int): Verdict {
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return Verdict.UNSUPPORTED
        val dims = dimensions(width, height, MAX_PNG_PIXELS)
        if (dims != Verdict.OK) return dims
        val bytesPerPixel = pngBytesPerPixel(bitDepth, colorType) ?: return Verdict.UNSUPPORTED
        return if (width * height * bytesPerPixel > MAX_PNG_SAMPLE_BYTES) Verdict.TOO_LARGE else Verdict.OK
    }

    /**
     * Decoded bytes per pixel of a PNG sample format, or null for a combination the PNG spec does
     * not allow: channels (gray 1, RGB 3, gray+alpha 2, RGBA 4; palette 3, as it is expanded to RGB)
     * × bytes per sample (2 for 16-bit, else 1: sub-byte samples are unpacked to a byte each).
     */
    fun pngBytesPerPixel(bitDepth: Int, colorType: Int): Int? {
        val channels = when (colorType) {
            0 -> if (bitDepth in intArrayOf(1, 2, 4, 8, 16)) 1 else return null
            2 -> if (bitDepth == 8 || bitDepth == 16) 3 else return null
            3 -> if (bitDepth in intArrayOf(1, 2, 4, 8)) 3 else return null
            4 -> if (bitDepth == 8 || bitDepth == 16) 2 else return null
            6 -> if (bitDepth == 8 || bitDepth == 16) 4 else return null
            else -> return null
        }
        return channels * (if (bitDepth == 16) 2 else 1)
    }

    private val ALLOWED_COMPONENTS = setOf(1, 3, 4)
}
