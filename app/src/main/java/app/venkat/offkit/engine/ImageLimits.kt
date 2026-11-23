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
 * - A PNG is always decoded to a full pixmap (plus MuPDF's inflated sample buffer, which can be
 *   twice that for 16-bit samples, plus a Java-heap copy for the size estimate), so PNG is capped
 *   lower, at [MAX_PNG_PIXELS].
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
 * baseline JPEG 159 MB, 50 MP PNG 690 MB (hence the PNG cap of 40 MP).
 */
internal object ImageLimits {
    /** Largest accepted width or height, in pixels. */
    const val MAX_EDGE = 65_500

    /**
     * Largest accepted PNG, in pixels (width × height). Lowered from 50 MP after measuring: a flat
     * 50 MP RGB PNG peaked at 690 MB RSS in `estimate` (pixmap + raw stream + its Java-heap copy for
     * the deflate estimate), 30 MP at 466 MB; 40 MP ≈ 580 MB.
     */
    const val MAX_PNG_PIXELS = 40_000_000L

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

    /** Verdict for a PNG with IHDR [width] × [height] (read as unsigned 32-bit values). */
    fun png(width: Long, height: Long): Verdict =
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) Verdict.UNSUPPORTED
        else dimensions(width, height, MAX_PNG_PIXELS)

    private val ALLOWED_COMPONENTS = setOf(1, 3, 4)
}
