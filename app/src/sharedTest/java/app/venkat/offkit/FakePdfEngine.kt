package app.venkat.offkit

import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.InProcessImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scriptable in-memory [PdfEngine] for UI and core tests. Never touches MuPDF.
 *
 * For each call it: records a [Call]; runs [progressSteps] steps, each one calling
 * [beforeStep], then checking `job.isCancelled()` (returns `Err(Cancelled)` and deletes
 * `out` if true), then reporting `job.progress(step, progressSteps)`; then checks
 * cancellation once more and finally produces the scripted result from [results]:
 * - no entry: writes [outputContent] to `out` and returns `Ok(1, outputContent.size)`;
 * - an `Ok`: writes [outputContent] to `out` and returns that `Ok` unchanged;
 * - an `Err`: deletes `out` and returns that `Err`.
 *
 * As an [ImagePreparer] it normalises images in the test process ([InProcessImagePreparer]), as
 * the `:engine` process does in the app, unless [prepare] is scripted. Every call is recorded in
 * [prepareCalls].
 *
 * As a [PdfPageRenderer] (the reader) every document has the pages [readerPages] (or fails with
 * [readerError]); [renderPage] writes a valid RGBA tile of one opaque colour, [readerColor] of the
 * page. Every reader call is recorded in [readerCalls].
 *
 * Safe to configure from the test thread while the engine runs on another thread.
 */
class FakePdfEngine : PdfEngine, ImagePreparer, PdfPageRenderer {
    enum class Op { IMAGES_TO_PDF, COMPRESS, MERGE, APPEND_IMAGES }

    /**
     * One recorded engine call. [inputs] is `images` (IMAGES_TO_PDF), `[pdf]` (COMPRESS),
     * `pdfs` (MERGE) or `[pdf] + images` (APPEND_IMAGES); the typed fields hold the
     * exact arguments, null where the operation does not take them.
     */
    data class Call(
        val op: Op,
        val inputs: List<File>,
        val out: File,
        val job: JobControl,
        val pageSize: PageSize? = null,
        val level: CompressionLevel? = null,
        val pdf: File? = null,
        val images: List<File>? = null,
        val pdfs: List<File>? = null,
        /** IMAGES_TO_PDF only: the `compression` argument (null = embed unchanged). */
        val compression: CompressionLevel? = null,
    )

    /** Every call, in order. */
    val calls: MutableList<Call> = CopyOnWriteArrayList()

    /** Scripted result per operation; absent means the default `Ok`. */
    val results: MutableMap<Op, PdfResult> = ConcurrentHashMap()

    /** Bytes written to `out` on success. */
    @Volatile
    var outputContent: ByteArray = "%PDF-1.7\n%fake\n".toByteArray()

    /** Number of `job.progress` steps emitted per call (0 = none). */
    @Volatile
    var progressSteps: Int = 3

    /**
     * Invoked on the engine thread before each step with the operation and the 1-based step
     * index. A test can block here (e.g. on a latch) to observe progress or cancel mid-run.
     */
    @Volatile
    var beforeStep: ((op: Op, step: Int) -> Unit)? = null

    /** Every [estimateImage] argument, in order. [estimateImage] does not run progress steps or [beforeStep]. */
    val estimateCalls: MutableList<File> = CopyOnWriteArrayList()

    /**
     * Scripted [estimateImage] result; null means the default: `original = file.length()` and
     * byLevel LOW / MEDIUM / HIGH = 50% / 25% / 10% of it.
     */
    @Volatile
    var estimate: ((File) -> PdfEstimate)? = null

    /** Every [prepareImage] raw file, in order. */
    val prepareCalls: MutableList<File> = CopyOnWriteArrayList()

    /** Scripted [prepareImage]; null means normalise in-process. The raw file is deleted afterwards. */
    @Volatile
    var prepare: ((raw: File, destDir: File, index: Int) -> PreparedImage)? = null

    override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage {
        prepareCalls += raw
        val scripted = prepare ?: return InProcessImagePreparer.prepareImage(raw, destDir, index, job)
        return try {
            scripted(raw, destDir, index)
        } finally {
            raw.delete()
        }
    }

    /** One recorded reader call: [request] and [out] are null for [pageInfo]. */
    data class ReaderCall(val pdf: File, val request: RenderRequest? = null, val out: File? = null)

    /** Every [pageInfo] and [renderPage] call, in order. */
    val readerCalls: MutableList<ReaderCall> = CopyOnWriteArrayList()

    /** Pages every document has, for [pageInfo] and for checking [renderPage] requests. Default: 3 x A4. */
    @Volatile
    var readerPages: List<PageDims> = List(3) { PageDims(595f, 842f) }

    /** When set, [pageInfo] and [renderPage] fail with it (and `out` is deleted). */
    @Volatile
    var readerError: PdfError? = null

    /**
     * Invoked on the engine thread at the start of each [renderPage] (after recording it). A test
     * can block here (e.g. on a latch) to observe loading, or cancel the job.
     */
    @Volatile
    var beforeRender: ((RenderRequest) -> Unit)? = null

    /** The opaque ARGB colour [renderPage] fills page [page] with (distinct for neighbouring pages). */
    fun readerColor(page: Int): Int {
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt())
        return palette[Math.floorMod(page, palette.size)]
    }

    /**
     * When set, reader calls behave as for a PDF that needs this password: no password (null or
     * empty) gives `Err(Encrypted)`, another one `Err(WrongPassword)`; with it, [pageInfo] reports
     * `encrypted` and [readerCanPrint] / [readerCanCopy].
     */
    @Volatile
    var readerPassword: String? = null

    @Volatile
    var readerCanPrint: Boolean = true

    @Volatile
    var readerCanCopy: Boolean = true

    /** The password sent with each reader call (info, render, text), in order; null for none. */
    val readerPasswords: MutableList<String?> = CopyOnWriteArrayList()

    /** Set while a password overload, its password checked, runs the plain call. */
    private val unlocked = ThreadLocal<Boolean>()

    private inline fun <T> asUnlocked(block: () -> T): T {
        unlocked.set(true)
        try { return block() } finally { unlocked.remove() }
    }

    /** A plain (no-password) reader call on a PDF that needs one: `Encrypted`. */
    private fun plainError(): PdfError? =
        if (unlocked.get() == true || readerPassword == null) null else passwordError(null)

    private fun passwordError(password: String?): PdfError? {
        readerPasswords += password
        val required = readerPassword ?: return null
        return when {
            password.isNullOrEmpty() -> PdfError.Encrypted
            password != required -> PdfError.WrongPassword
            else -> null
        }
    }

    // The password overloads check the password, then call the plain ones, so a test subclass that
    // overrides a plain one still sees every reader call.
    override fun pageInfo(pdf: File, password: String?, job: JobControl): PdfPageInfo =
        passwordError(password)?.let { PdfPageInfo.Err(it) } ?: asUnlocked { pageInfo(pdf, job) }

    override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo {
        plainError()?.let { return PdfPageInfo.Err(it) }
        readerCalls += ReaderCall(pdf)
        readerError?.let { return PdfPageInfo.Err(it) }
        if (job.isCancelled()) return PdfPageInfo.Err(PdfError.Cancelled)
        val pages = readerPages
        if (pages.size > RenderLimits.MAX_PAGES) return PdfPageInfo.Err(PdfError.OutOfMemory)
        val encrypted = readerPassword != null
        return PdfPageInfo.Ok(
            pages.toList(),
            encrypted = encrypted,
            canPrint = !encrypted || readerCanPrint,
            canCopy = !encrypted || readerCanCopy,
        )
    }

    override fun renderPage(pdf: File, request: RenderRequest, out: File, password: String?, job: JobControl): PdfRender {
        passwordError(password)?.let {
            out.delete()
            return PdfRender.Err(it)
        }
        return asUnlocked { renderPage(pdf, request, out, job) }
    }

    override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
        plainError()?.let {
            out.delete()
            return PdfRender.Err(it)
        }
        readerCalls += ReaderCall(pdf, request, out)
        beforeRender?.invoke(request)
        fun fail(e: PdfError): PdfRender {
            out.delete()
            return PdfRender.Err(e)
        }
        readerError?.let { return fail(it) }
        if (job.isCancelled()) return fail(PdfError.Cancelled)
        val dims = readerPages.getOrNull(request.page)
            ?: return fail(PdfError.Io(IllegalArgumentException("page ${request.page} out of range")))
        RenderLimits.check(request, dims)?.let { return fail(PdfError.Io(IllegalArgumentException(it))) }
        val argb = readerColor(request.page)
        val pixel = byteArrayOf((argb shr 16).toByte(), (argb shr 8).toByte(), argb.toByte(), 0xFF.toByte())
        val row = ByteArray(request.width * 4) { pixel[it % 4] }
        out.outputStream().buffered().use { s -> repeat(request.height) { s.write(row) } }
        return PdfRender.Ok(request.width, request.height)
    }

    /** Every [pageText] call as (pdf, page, out), in order. */
    val textCalls: MutableList<Triple<File, Int, File>> = CopyOnWriteArrayList()

    /**
     * Canned [pageText] per page index; a page without an entry has no text (`Ok(0)`). Build one
     * with [PageTextFormat.build]. [readerError] also fails [pageText].
     */
    val readerTexts: MutableMap<Int, PageText> = ConcurrentHashMap()

    override fun pageText(pdf: File, page: Int, out: File, password: String?, job: JobControl): PdfPageText {
        passwordError(password)?.let {
            out.delete()
            return PdfPageText.Err(it)
        }
        return asUnlocked { pageText(pdf, page, out, job) }
    }

    /** Every [decryptCopy] call as (pdf, out), in order. */
    val decryptCalls: MutableList<Pair<File, File>> = CopyOnWriteArrayList()

    /** When set, [decryptCopy] fails with it (and `out` is deleted). */
    @Volatile
    var decryptError: PdfError? = null

    /** Called by [decryptCopy] before it writes (for tests that close a document meanwhile). */
    @Volatile
    var beforeDecrypt: () -> Unit = {}

    /**
     * Writes [DECRYPTED_PREFIX] followed by [pdf]'s bytes to [out] (a stand-in for MuPDF's
     * unencrypted copy) and answers `Ok(readerPages.size, size)`.
     */
    override fun decryptCopy(pdf: File, out: File, password: String?, job: JobControl): PdfResult {
        passwordError(password)?.let {
            out.delete()
            return PdfResult.Err(it)
        }
        decryptCalls += pdf to out
        beforeDecrypt()
        val error = decryptError ?: readerError ?: if (job.isCancelled()) PdfError.Cancelled else null
        if (error != null) {
            out.delete()
            return PdfResult.Err(error)
        }
        out.writeBytes(DECRYPTED_PREFIX + pdf.readBytes())
        return PdfResult.Ok(readerPages.size, out.length())
    }

    override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText {
        plainError()?.let {
            out.delete()
            return PdfPageText.Err(it)
        }
        textCalls += Triple(pdf, page, out)
        fun fail(e: PdfError): PdfPageText {
            out.delete()
            return PdfPageText.Err(e)
        }
        readerError?.let { return fail(it) }
        if (job.isCancelled()) return fail(PdfError.Cancelled)
        if (page !in readerPages.indices) return fail(PdfError.Io(IllegalArgumentException("page $page out of range")))
        val t = readerTexts[page] ?: PageText.EMPTY
        PageTextFormat.write(out, t)
        return PdfPageText.Ok(t.length, t.truncated)
    }

    fun setResult(op: Op, result: PdfResult) {
        results[op] = result
    }

    fun callsFor(op: Op): List<Call> = calls.filter { it.op == op }

    override fun imagesToPdf(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl,
        compression: CompressionLevel?,
    ): PdfResult = execute(
        Call(Op.IMAGES_TO_PDF, images.toList(), out, job, pageSize = pageSize, images = images.toList(), compression = compression),
    )

    override fun estimateImage(image: File): PdfEstimate {
        estimateCalls += image
        estimate?.let { return it(image) }
        val n = image.length()
        return PdfEstimate.Ok(
            original = n,
            byLevel = mapOf(
                CompressionLevel.LOW to n / 2,
                CompressionLevel.MEDIUM to n / 4,
                CompressionLevel.HIGH to n / 10,
            ),
        )
    }

    override fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        execute(Call(Op.COMPRESS, listOf(pdf), out, job, level = level, pdf = pdf))

    override fun merge(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        execute(Call(Op.MERGE, pdfs.toList(), out, job, pdfs = pdfs.toList()))

    override fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult =
        execute(Call(Op.APPEND_IMAGES, listOf(pdf) + images, out, job, pdf = pdf, images = images.toList()))

    private fun execute(call: Call): PdfResult {
        calls += call
        val job = call.job
        val total = progressSteps
        for (step in 1..total) {
            beforeStep?.invoke(call.op, step)
            if (job.isCancelled()) return fail(call.out, PdfResult.Err(PdfError.Cancelled))
            job.progress(step, total)
        }
        if (job.isCancelled()) return fail(call.out, PdfResult.Err(PdfError.Cancelled))

        return when (val scripted = results[call.op]) {
            null -> {
                val content = outputContent
                call.out.writeBytes(content)
                PdfResult.Ok(pages = 1, bytes = content.size.toLong())
            }
            is PdfResult.Ok -> {
                call.out.writeBytes(outputContent)
                scripted
            }
            is PdfResult.Err -> fail(call.out, scripted)
        }
    }

    private fun fail(out: File, err: PdfResult.Err): PdfResult.Err {
        out.delete()
        return err
    }

    companion object {
        /** What [decryptCopy] writes before the source's bytes. */
        val DECRYPTED_PREFIX: ByteArray = "%FAKE-DECRYPTED\n".toByteArray()
    }
}
