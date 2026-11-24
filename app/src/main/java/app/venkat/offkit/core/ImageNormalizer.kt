package app.venkat.offkit.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The content of an image Uri is not an image Android can decode. */
class UnsupportedImageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns an image Uri into a file the PDF engine can embed: JPEG or PNG.
 *
 * - JPEG and PNG content (detected by magic bytes, not by MIME type or name) is copied through
 *   byte-for-byte; the engine embeds it directly and applies JPEG EXIF orientation itself.
 * - Anything else Android can decode (WebP, HEIC/HEIF, GIF, BMP, ...) is decoded (first frame),
 *   EXIF-rotated, downsampled so its long edge is at most [MAX_LONG_EDGE_PX], flattened onto white
 *   if it has transparency, and written as JPEG quality [JPEG_QUALITY].
 *
 * Blocking; call off the main thread. At most one decoded bitmap is held at a time (apart from a
 * brief second one while rotating on API < 28).
 */
object ImageNormalizer {
    const val MAX_LONG_EDGE_PX = 4096
    const val JPEG_QUALITY = 92

    /**
     * Number of transcodes (non-JPEG/PNG decodes) performed in THIS process. Lets on-device tests
     * show that decoding happens in the `:engine` process and never in the UI process.
     */
    private val transcodes = java.util.concurrent.atomic.AtomicInteger()

    val transcodeCountInThisProcess: Int get() = transcodes.get()

    /**
     * Copies [uri] and normalises it in THIS process. Production code must not call this in the
     * UI process: `PdfJobs` copies the raw bytes and lets the `:engine` process run [normalizeFile]
     * (through `ImagePreparer`). Kept for in-process tests.
     */
    @VisibleForTesting
    @Throws(IOException::class, UnsupportedImageException::class)
    fun normalize(
        context: Context,
        uri: Uri,
        destDir: File,
        index: Int,
        isCancelled: () -> Boolean = { false },
    ): File {
        destDir.mkdirs()
        val raw = rawFile(destDir, index)
        UriIo.copyToFile(context, uri, raw, isCancelled)
        return normalizeFile(raw, destDir, index)
    }

    /** `img_NNN.raw` in [destDir]: where the raw bytes of image input [index] are copied. */
    fun rawFile(destDir: File, index: Int): File = File(destDir, "${baseName(index)}.raw")

    /** The normalised file name of image input [index] for [kind] ("jpg" or "png"). */
    fun normalizedFile(destDir: File, index: Int, kind: String): File = File(destDir, "${baseName(index)}.$kind")

    private fun baseName(index: Int) = String.format(Locale.ROOT, "img_%03d", index)

    /**
     * Normalises the raw image bytes in [raw] into [destDir] (`img_NNN.jpg` or `img_NNN.png`, see
     * [normalizedFile]) and returns that file. [raw] is always deleted. Decodes with platform
     * codecs when the content is neither JPEG nor PNG: run it in the `:engine` process only.
     *
     * @throws UnsupportedImageException if the content cannot be decoded as an image.
     * @throws IOException if [raw] cannot be read or [destDir] cannot be written.
     */
    @Throws(IOException::class, UnsupportedImageException::class)
    fun normalizeFile(raw: File, destDir: File, index: Int): File {
        try {
            val kind = sniff(raw)
            if (kind != null) {
                val dest = normalizedFile(destDir, index, kind)
                dest.delete()
                if (!raw.renameTo(dest)) throw IOException("Cannot rename $raw")
                return dest
            }
            val dest = normalizedFile(destDir, index, "jpg")
            transcodes.incrementAndGet()
            transcode(raw, dest)
            return dest
        } finally {
            raw.delete()
        }
    }

    /** Returns "jpg" or "png" if [file] starts with that format's magic bytes, else null. */
    fun sniff(file: File): String? {
        val head = ByteArray(8)
        val n = FileInputStream(file).use { input ->
            var read = 0
            while (read < head.size) {
                val r = input.read(head, read, head.size - read)
                if (r < 0) break
                read += r
            }
            read
        }
        fun at(i: Int) = head[i].toInt() and 0xFF
        if (n >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
        if (n >= 8 && PNG_MAGIC.indices.all { at(it) == PNG_MAGIC[it] }) return "png"
        return null
    }

    private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun transcode(src: File, dest: File) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(src)
        } else {
            decodeWithBitmapFactory(src)
        }
        try {
            flattenOntoWhite(bitmap)
            try {
                val ok = FileOutputStream(dest).buffered().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (!ok) throw IOException("JPEG encoding failed")
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(src: File): Bitmap {
        try {
            val source = ImageDecoder.createSource(src)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val (w, h) = targetSize(info.size.width, info.size.height)
                if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            }
        } catch (e: IOException) {
            throw UnsupportedImageException("Cannot decode image", e)
        } catch (e: RuntimeException) {
            // Decoders may also signal malformed input with unchecked exceptions.
            throw UnsupportedImageException("Cannot decode image", e)
        }
    }

    /** API < 28 path: power-of-two subsampling to the size limit plus manual EXIF rotation. */
    @VisibleForTesting
    internal fun decodeWithBitmapFactory(src: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnsupportedImageException("Cannot decode image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: throw UnsupportedImageException("Cannot decode image")
        return applyExifOrientation(src, decoded)
    }

    private fun applyExifOrientation(src: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(src.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            return if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also {
                rotated.recycle()
            }
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    /** Composites white behind [bitmap] in place, so transparent pixels become white, not black. */
    private fun flattenOntoWhite(bitmap: Bitmap) {
        if (!bitmap.hasAlpha()) return
        if (bitmap.isMutable) {
            Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            bitmap.setHasAlpha(false)
        }
    }

    /** Size scaled down (aspect preserved) so the long edge is at most [MAX_LONG_EDGE_PX]. */
    @VisibleForTesting
    internal fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_LONG_EDGE_PX) return width to height
        val scale = MAX_LONG_EDGE_PX.toDouble() / long
        val w = (width * scale).roundToInt().coerceIn(1, MAX_LONG_EDGE_PX)
        val h = (height * scale).roundToInt().coerceIn(1, MAX_LONG_EDGE_PX)
        return w to h
    }
}
