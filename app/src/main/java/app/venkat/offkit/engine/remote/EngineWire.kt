package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.Process
import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import java.io.File
import java.io.IOException

/**
 * One [PdfEngine] call as it crosses the process boundary. Only file PATHS travel, never bytes;
 * the engine process re-checks every path ([EngineServer]).
 */
sealed interface EngineRequest {
    /** Every input and output path of the request (validated by the server before running). */
    val paths: List<String>

    /** The output path, or null for [Estimate]. */
    val out: String?

    data class ImagesToPdf(
        val images: List<String>,
        val pageSize: PageSize,
        override val out: String,
        val compression: CompressionLevel?,
    ) : EngineRequest {
        override val paths get() = images + out
    }

    data class Estimate(val image: String) : EngineRequest {
        override val paths get() = listOf(image)
        override val out: String? get() = null
    }

    data class Compress(val pdf: String, val level: CompressionLevel, override val out: String) : EngineRequest {
        override val paths get() = listOf(pdf, out)
    }

    data class Merge(val pdfs: List<String>, override val out: String) : EngineRequest {
        override val paths get() = pdfs + out
    }

    data class AppendImages(val pdf: String, val images: List<String>, override val out: String) : EngineRequest {
        override val paths get() = listOf(pdf) + images + out
    }

    /**
     * Decode/normalise the raw image bytes at [raw] into [destDir] as image input [index]
     * ([ImagePreparer]); the reply is [EngineWire.encodePrepared]. Runs in the engine process so
     * platform codecs never see untrusted image data in the UI process.
     */
    data class NormalizeImage(val raw: String, val destDir: String, val index: Int) : EngineRequest {
        override val paths get() = listOf(raw, destDir)
        override val out: String? get() = null
    }
}

/** Largest image input index accepted by [EngineRequest.NormalizeImage] (file names are `img_NNN`). */
internal const val MAX_IMAGE_INDEX = 99_999

/**
 * Runs [request] on [engine] (or [preparer]): a [PdfResult], a [PdfEstimate] for
 * [EngineRequest.Estimate], or a [PreparedImage] for [EngineRequest.NormalizeImage].
 */
internal fun EngineRequest.execute(engine: () -> PdfEngine, preparer: ImagePreparer, job: JobControl): Any = when (this) {
    is EngineRequest.NormalizeImage -> preparer.prepareImage(File(raw), File(destDir), index, job)
    else -> execute(engine(), job)
}

private fun EngineRequest.execute(engine: PdfEngine, job: JobControl): Any = when (this) {
    is EngineRequest.NormalizeImage -> error("not an engine request")
    is EngineRequest.ImagesToPdf -> engine.imagesToPdf(images.map(::File), pageSize, File(out), job, compression)
    is EngineRequest.Estimate -> engine.estimateImage(File(image))
    is EngineRequest.Compress -> engine.compress(File(pdf), level, File(out), job)
    is EngineRequest.Merge -> engine.merge(pdfs.map(::File), File(out), job)
    is EngineRequest.AppendImages -> engine.appendImages(File(pdf), images.map(::File), File(out), job)
}

/**
 * Bundle encoding of [EngineRequest], [PdfResult], [PdfError] and [PdfEstimate]. Bundles hold only
 * strings, string arrays, ints and longs, so no class loading happens on decode.
 *
 * Every variant round-trips exactly except [PdfError.Io], whose cause cannot cross as a
 * Throwable: it is sent as class name + message and rebuilt as
 * `Io(IOException("<class>: <message>"))` (just `"<class>"` when the message was null).
 */
object EngineWire {
    private const val OP = "op"
    private const val IMAGES = "images"
    private const val PDFS = "pdfs"
    private const val PDF = "pdf"
    private const val OUT = "out"
    private const val PAGE_SIZE = "pageSize"
    private const val LEVEL = "level"
    private const val RAW = "raw"
    private const val DEST_DIR = "destDir"
    private const val INDEX = "index"
    private const val FILE_NAME = "fileName"
    private const val PID = "pid"

    private const val KIND = "kind"
    private const val PAGES = "pages"
    private const val BYTES = "bytes"
    private const val ERROR = "error"
    private const val IO_CLASS = "ioClass"
    private const val IO_MESSAGE = "ioMessage"
    private const val ORIGINAL = "original"
    private const val LEVEL_PREFIX = "level_"

    private const val KIND_OK = "ok"
    private const val KIND_ERR = "err"
    private const val KIND_EST_OK = "estOk"
    private const val KIND_EST_ERR = "estErr"
    private const val KIND_IMG_OK = "imgOk"

    // ---------------------------------------------------------------- requests

    fun encodeRequest(r: EngineRequest): Bundle = Bundle().apply {
        when (r) {
            is EngineRequest.ImagesToPdf -> {
                putString(OP, "imagesToPdf")
                putStringArray(IMAGES, r.images.toTypedArray())
                putString(PAGE_SIZE, r.pageSize.name)
                putString(OUT, r.out)
                r.compression?.let { putString(LEVEL, it.name) }
            }
            is EngineRequest.Estimate -> {
                putString(OP, "estimate")
                putString(PDF, r.image)
            }
            is EngineRequest.Compress -> {
                putString(OP, "compress")
                putString(PDF, r.pdf)
                putString(LEVEL, r.level.name)
                putString(OUT, r.out)
            }
            is EngineRequest.Merge -> {
                putString(OP, "merge")
                putStringArray(PDFS, r.pdfs.toTypedArray())
                putString(OUT, r.out)
            }
            is EngineRequest.NormalizeImage -> {
                putString(OP, "normalizeImage")
                putString(RAW, r.raw)
                putString(DEST_DIR, r.destDir)
                putInt(INDEX, r.index)
            }
            is EngineRequest.AppendImages -> {
                putString(OP, "appendImages")
                putString(PDF, r.pdf)
                putStringArray(IMAGES, r.images.toTypedArray())
                putString(OUT, r.out)
            }
        }
    }

    /** Decodes a request bundle; throws [IllegalArgumentException] if it is malformed. */
    fun decodeRequest(b: Bundle): EngineRequest {
        fun str(k: String) = requireNotNull(b.getString(k)) { "missing $k" }
        fun list(k: String) = requireNotNull(b.getStringArray(k)) { "missing $k" }.map { requireNotNull(it) }
        return when (val op = b.getString(OP)) {
            "imagesToPdf" -> EngineRequest.ImagesToPdf(
                list(IMAGES),
                PageSize.valueOf(str(PAGE_SIZE)),
                str(OUT),
                b.getString(LEVEL)?.let(CompressionLevel::valueOf),
            )
            "estimate" -> EngineRequest.Estimate(str(PDF))
            "compress" -> EngineRequest.Compress(str(PDF), CompressionLevel.valueOf(str(LEVEL)), str(OUT))
            "merge" -> EngineRequest.Merge(list(PDFS), str(OUT))
            "appendImages" -> EngineRequest.AppendImages(str(PDF), list(IMAGES), str(OUT))
            "normalizeImage" -> {
                require(b.containsKey(INDEX)) { "missing $INDEX" }
                val index = b.getInt(INDEX)
                require(index in 0..MAX_IMAGE_INDEX) { "bad index $index" }
                EngineRequest.NormalizeImage(str(RAW), str(DEST_DIR), index)
            }
            else -> throw IllegalArgumentException("unknown op $op")
        }
    }

    // ---------------------------------------------------------------- results

    fun encodeResult(r: PdfResult): Bundle = Bundle().apply {
        when (r) {
            is PdfResult.Ok -> {
                putString(KIND, KIND_OK)
                putInt(PAGES, r.pages)
                putLong(BYTES, r.bytes)
            }
            is PdfResult.Err -> {
                putString(KIND, KIND_ERR)
                putError(this, r.error)
            }
        }
    }

    /** Decodes a result bundle; a malformed one becomes `Err(Io)`. */
    fun decodeResult(b: Bundle): PdfResult = when (b.getString(KIND)) {
        KIND_OK -> PdfResult.Ok(b.getInt(PAGES), b.getLong(BYTES))
        KIND_ERR -> PdfResult.Err(getError(b))
        else -> PdfResult.Err(malformed())
    }

    fun encodeEstimate(e: PdfEstimate): Bundle = Bundle().apply {
        when (e) {
            is PdfEstimate.Ok -> {
                putString(KIND, KIND_EST_OK)
                putLong(ORIGINAL, e.original)
                e.byLevel.forEach { (level, bytes) -> putLong(LEVEL_PREFIX + level.name, bytes) }
            }
            is PdfEstimate.Err -> {
                putString(KIND, KIND_EST_ERR)
                putError(this, e.error)
            }
        }
    }

    /** Decodes an estimate bundle; a malformed one becomes `Err(Io)`. */
    fun decodeEstimate(b: Bundle): PdfEstimate = when (b.getString(KIND)) {
        KIND_EST_OK -> PdfEstimate.Ok(
            b.getLong(ORIGINAL),
            CompressionLevel.entries
                .filter { b.containsKey(LEVEL_PREFIX + it.name) }
                .associateWith { b.getLong(LEVEL_PREFIX + it.name) },
        )
        KIND_EST_ERR -> PdfEstimate.Err(getError(b))
        else -> PdfEstimate.Err(malformed())
    }

    /**
     * Encodes a [PreparedImage]. `Ok` carries only the file NAME (the client rebuilds it inside
     * the directory it asked for) and the engine process id [pid] (diagnostics and tests).
     */
    fun encodePrepared(p: PreparedImage, pid: Int = Process.myPid()): Bundle = when (p) {
        is PreparedImage.Ok -> Bundle().apply {
            putString(KIND, KIND_IMG_OK)
            putString(FILE_NAME, p.file.name)
            putInt(PID, pid)
        }
        is PreparedImage.Err -> encodeResult(PdfResult.Err(p.error))
    }

    /**
     * Decodes a [PreparedImage] bundle for a request whose destination was [destDir]. The name must
     * be one [app.venkat.offkit.core.ImageNormalizer.normalizedFile] produces for [index];
     * anything else (or a malformed bundle) becomes `Err(Io)`.
     */
    fun decodePrepared(b: Bundle, destDir: File, index: Int): PreparedImage = when (b.getString(KIND)) {
        KIND_IMG_OK -> {
            val name = b.getString(FILE_NAME)
            val expected = listOf("jpg", "png").map { app.venkat.offkit.core.ImageNormalizer.normalizedFile(destDir, index, it).name }
            if (name != null && name in expected) PreparedImage.Ok(File(destDir, name)) else PreparedImage.Err(malformed())
        }
        KIND_ERR -> PreparedImage.Err(getError(b))
        else -> PreparedImage.Err(malformed())
    }

    /** The engine process id carried by an `Ok` [encodePrepared] bundle, or 0. */
    fun preparedPid(b: Bundle): Int = b.getInt(PID, 0)

    /** Encodes whatever [EngineRequest.execute] returned. */
    internal fun encodeAny(value: Any): Bundle = when (value) {
        is PdfResult -> encodeResult(value)
        is PdfEstimate -> encodeEstimate(value)
        is PreparedImage -> encodePrepared(value)
        else -> throw IllegalArgumentException("not a result: $value")
    }

    // ---------------------------------------------------------------- errors

    private fun putError(b: Bundle, e: PdfError) {
        val code = when (e) {
            PdfError.Encrypted -> "Encrypted"
            PdfError.Corrupt -> "Corrupt"
            PdfError.UnsupportedImage -> "UnsupportedImage"
            PdfError.OutOfMemory -> "OutOfMemory"
            PdfError.ImageTooLarge -> "ImageTooLarge"
            PdfError.Cancelled -> "Cancelled"
            PdfError.NotImplemented -> "NotImplemented"
            PdfError.EngineCrashed -> "EngineCrashed"
            PdfError.TimedOut -> "TimedOut"
            is PdfError.Io -> {
                b.putString(IO_CLASS, e.cause.javaClass.name)
                e.cause.message?.let { b.putString(IO_MESSAGE, it) }
                "Io"
            }
        }
        b.putString(ERROR, code)
    }

    private fun getError(b: Bundle): PdfError = when (b.getString(ERROR)) {
        "Encrypted" -> PdfError.Encrypted
        "Corrupt" -> PdfError.Corrupt
        "UnsupportedImage" -> PdfError.UnsupportedImage
        "OutOfMemory" -> PdfError.OutOfMemory
        "ImageTooLarge" -> PdfError.ImageTooLarge
        "Cancelled" -> PdfError.Cancelled
        "NotImplemented" -> PdfError.NotImplemented
        "EngineCrashed" -> PdfError.EngineCrashed
        "TimedOut" -> PdfError.TimedOut
        "Io" -> {
            val cls = b.getString(IO_CLASS) ?: "?"
            val msg = b.getString(IO_MESSAGE)
            PdfError.Io(IOException(if (msg == null) cls else "$cls: $msg"))
        }
        else -> malformed()
    }

    private fun malformed() = PdfError.Io(IOException("Malformed reply from the engine process"))
}
