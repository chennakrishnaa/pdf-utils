package app.venkat.paperpixel

import app.venkat.paperpixel.engine.CompressionLevel
import app.venkat.paperpixel.engine.JobControl
import app.venkat.paperpixel.engine.PageSize
import app.venkat.paperpixel.engine.PdfEngine
import app.venkat.paperpixel.engine.PdfError
import app.venkat.paperpixel.engine.PdfEstimate
import app.venkat.paperpixel.engine.PdfResult
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
 * Safe to configure from the test thread while the engine runs on another thread.
 */
class FakePdfEngine : PdfEngine {
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
}
