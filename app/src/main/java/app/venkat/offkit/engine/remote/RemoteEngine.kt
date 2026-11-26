package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.ReaderPassword
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import app.venkat.offkit.engine.ops.DecryptCopyOp
import java.io.IOException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * [PdfEngine] that runs every call in the `:engine` process ([EngineService]), so a file that
 * crashes MuPDF or exhausts its memory kills only that process.
 *
 * Each call binds if needed ([EngineConnector.connect]), sends the request (paths only) and blocks
 * the calling thread until the result arrives or the engine dies. While blocked it delivers
 * progress to `job.progress` on the calling thread, in order and before returning, and polls
 * `job.isCancelled()` about every [pollMs]. On cancel it asks the service to cancel; if no result
 * arrives within [cancelGraceMs] it kills the engine process and returns `Err(Cancelled)`.
 *
 * If the engine dies or disconnects while running the call in the foreground, the call returns
 * `Err(EngineCrashed)` (estimates: `PdfEstimate.Err(EngineCrashed)`). If it cannot be bound within
 * the connector's timeout, or it disappears while this app was frozen or in the background (see
 * [inBackground] and the freeze-gap rule below), the call returns `Err(EngineUnavailable)`, which
 * does not blame the file. Either way a partial `out` is deleted and the next call binds a fresh
 * engine. It never falls back to running MuPDF in this process.
 *
 * It is also the app's [ImagePreparer]: image normalisation (platform codecs) runs in the engine
 * process too. A death during it gives `PreparedImage.Err(EngineCrashed)`, a hard cancel
 * `PreparedImage.Err(Cancelled)`; the raw file is deleted in every case.
 *
 * It is also the app's [PdfPageRenderer] (the in-app reader). Engine death, unavailability,
 * cancel and the watchdog map as for the other calls (`PdfPageInfo.Err` / `PdfRender.Err`), and a
 * render's `out` is deleted on every `Err`. The reply is not trusted: a render `Ok` whose
 * dimensions differ from the request's, or whose `out` is not exactly `width * height * 4` bytes,
 * is deleted and becomes `Err(Io)`; a page-info `Ok` with more than [RenderLimits.MAX_PAGES] pages
 * or a size that is not finite and > 0 becomes `Err(Io)` ([EngineWire.decodePageInfo]). An
 * invalid render request (`RenderLimits.check(request, null)`) is refused without contacting the
 * engine: `Err(Io(IllegalArgumentException))`.
 *
 * **Watchdog** ([limits]): MuPDF calls cannot be interrupted, so while waiting the client also
 * kills the engine and returns `Err(TimedOut)` when a PDF operation goes [EngineLimits.stallMs]
 * without a progress event or result, or runs past [EngineLimits.totalMs] (image estimate or
 * normalisation: [EngineLimits.imageTotalMs]; reader page info and render:
 * [EngineLimits.infoTotalMs] and [EngineLimits.renderTotalMs], with no stall limit); and returns `Err(OutOfMemory)` when the engine's
 * resident memory, sampled about every [EngineLimits.memorySampleMs] through [rssBytes], exceeds
 * [EngineLimits.memoryBytes]. If [rssBytes] cannot read it, the memory check is skipped (logged
 * once). A user cancel takes precedence over the watchdog and still returns `Cancelled`. After a
 * watchdog kill, partial `out` is deleted and the next call binds a new engine, as for a hard cancel.
 *
 * **Clock.** Every deadline uses [nanoTime], by default `System.nanoTime()`. On Android that is
 * `CLOCK_MONOTONIC`, the same clock as `SystemClock.uptimeMillis()`: it stops while the device is
 * in deep sleep (when neither process runs either), unlike `elapsedRealtime()`
 * (`CLOCK_BOOTTIME`). It deliberately is not `uptimeMillis()` itself, because Robolectric fakes
 * that clock and the JVM tests need real time; on a device the two advance identically.
 *
 * **Freeze gaps (FR-1).** `CLOCK_MONOTONIC` keeps running while this process is frozen by the
 * cached-apps freezer or simply not scheduled. The `:engine` process is bound only by this app,
 * so it is cached and frozen with it. The wait loop wakes about every [pollMs]; when two
 * consecutive wake-ups are more than [freezeGapMs] apart, this process may have been frozen, and
 * the gap minus one [pollMs] is not counted toward the stall, total or image limits or the cancel
 * grace (their deadlines are shifted by it). The memory check is not postponed: RSS is sampled on
 * the first wake-up after such a gap, unless the call is in its cancel grace (which ends within
 * [cancelGraceMs] of observed time, by a result or a kill). A gap counts as a thaw if it is at least [EngineLimits.FROZEN_DEATH_GAP_MS], or if it
 * is longer than [freezeGapMs] and [inBackground] (sampled when the call starts and about every
 * [BACKGROUND_SAMPLE_MS] of wake-ups) last said, before the gap, that the app was cached: only a
 * cached process is frozen (T22R-1). An engine death noticed within [THAW_WINDOW_MS] of a thaw is
 * `EngineUnavailable`; after a foreground gap under [EngineLimits.FROZEN_DEATH_GAP_MS] (a GC
 * pause, a busy CPU) it is `EngineCrashed` unless [inBackground] says the app is cached when the
 * death is handled (PI4R-1).
 * Bound: at most [maxFrozenMs] per call is discounted in all; beyond that, gaps count as
 * ordinary time. So even if the engine really did run during every "gap" (it should not: it is
 * frozen with us), a call ends after at most its limit plus [maxFrozenMs] of wall time, and every
 * loop iteration still counts at least [pollMs], so the limits can never be postponed forever.
 *
 * Calls are serialised (one request at a time). A call waiting for an earlier one still polls
 * `job.isCancelled()` about every [pollMs] and returns `Cancelled` if it is set. Do not call on
 * the main thread.
 *
 * @param limits watchdog limits; the app passes the default times and a memory limit from
 *   [EngineLimits.memoryLimitFor]. Tests pass small ones.
 * @param rssBytes resident set size in bytes of an engine pid, or null if unknown.
 * @param nanoTime monotonic clock for every deadline here (polling itself waits in real time).
 * @param freezeGapMs a gap between two wake-ups of the wait loop longer than this means this
 *   process was frozen or not scheduled; it shifts the time limits but alone does not change how
 *   an engine death is reported.
 * @param maxFrozenMs most frozen time discounted from the limits in one call.
 * @param inBackground true if this process is cached (in the background); then an engine death
 *   is reported as `EngineUnavailable`, since the system may have killed the cached engine. Also
 *   sampled while waiting, to recognise a short freeze (see above); it should be cheap.
 * @param log where the one-time "cannot read the engine's memory" warning goes.
 */
class RemoteEngine(
    private val connector: EngineConnector,
    private val pollMs: Long = 100,
    private val cancelGraceMs: Long = 2_000,
    private val deathWaitMs: Long = 1_000,
    private val limits: EngineLimits = EngineLimits(),
    private val rssBytes: (pid: Int) -> Long? = ProcRss::bytes,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    private val freezeGapMs: Long = FREEZE_GAP_MS,
    private val maxFrozenMs: Long = MAX_FROZEN_MS,
    private val inBackground: () -> Boolean = { false },
) : PdfEngine, ImagePreparer, PdfPageRenderer {
    private sealed interface Event {
        data class Progress(val done: Int, val total: Int) : Event
        data class Result(val bundle: Bundle) : Event
        data object Lost : Event
    }

    /** One binding of the engine service. */
    private class Session {
        @Volatile var service: IEngineService? = null
        @Volatile var pid: Int = 0
        @Volatile var lost = false
        @Volatile var events: LinkedBlockingQueue<Event>? = null

        fun markLost() {
            lost = true
            events?.offer(Event.Lost)
        }
    }

    private sealed interface Reply {
        data class Done(val bundle: Bundle) : Reply

        /**
         * No result: the engine died ([PdfError.EngineCrashed]) or was killed for a hard cancel
         * ([PdfError.Cancelled]) or by the watchdog ([PdfError.TimedOut], [PdfError.OutOfMemory]).
         */
        data class Failed(val error: PdfError) : Reply
    }

    private val crashed = Reply.Failed(PdfError.EngineCrashed)
    private val unavailable = Reply.Failed(PdfError.EngineUnavailable)

    @Volatile
    private var rssUnreadableLogged = false

    private val lock = ReentrantLock()
    private var session: Session? = null
    private var nextId = 1L

    override fun imagesToPdf(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl,
        compression: CompressionLevel?,
    ): PdfResult = result(EngineRequest.ImagesToPdf(images.map { it.path }, pageSize, out.path, compression), out, job)

    override fun estimateImage(image: File): PdfEstimate =
        when (val r = call(EngineRequest.Estimate(image.path), JobControl.None)) {
            is Reply.Done -> EngineWire.decodeEstimate(r.bundle)
            is Reply.Failed -> PdfEstimate.Err(r.error)
        }

    override fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        result(EngineRequest.Compress(pdf.path, level, out.path), out, job)

    override fun merge(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        result(EngineRequest.Merge(pdfs.map { it.path }, out.path), out, job)

    override fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult =
        result(EngineRequest.AppendImages(pdf.path, images.map { it.path }, out.path), out, job)

    /** Process id of the engine that last normalised an image successfully (0: none yet). For tests. */
    @Volatile
    var lastImagePreparedByPid: Int = 0
        private set

    override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage = try {
        when (val r = call(EngineRequest.NormalizeImage(raw.path, destDir.path, index), job)) {
            is Reply.Done -> EngineWire.decodePrepared(r.bundle, destDir, index).also {
                if (it is PreparedImage.Ok) lastImagePreparedByPid = EngineWire.preparedPid(r.bundle)
            }
            is Reply.Failed -> PreparedImage.Err(r.error)
        }
    } finally {
        raw.delete()
    }

    /** A refused [password] ([ReaderPassword.check]) as an error, before anything is sent; null if none. */
    private fun passwordError(password: String?): PdfError? =
        password?.let { p -> ReaderPassword.check(p)?.let { PdfError.Io(IllegalArgumentException(it)) } }

    override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo = pageInfo(pdf, null, job)

    override fun pageInfo(pdf: File, password: String?, job: JobControl): PdfPageInfo {
        passwordError(password)?.let { return PdfPageInfo.Err(it) }
        return when (val r = call(EngineRequest.PageInfo(pdf.path, password), job)) {
            is Reply.Done -> EngineWire.decodePageInfo(r.bundle)
            is Reply.Failed -> PdfPageInfo.Err(r.error)
        }
    }

    override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender =
        renderPage(pdf, request, out, null, job)

    override fun renderPage(pdf: File, request: RenderRequest, out: File, password: String?, job: JobControl): PdfRender {
        RenderLimits.check(request, null)?.let {
            out.delete()
            return PdfRender.Err(PdfError.Io(IllegalArgumentException(it)))
        }
        passwordError(password)?.let {
            out.delete()
            return PdfRender.Err(it)
        }
        val result = when (val r = call(EngineRequest.RenderPage(pdf.path, request, out.path, password), job)) {
            is Reply.Done -> when (val d = EngineWire.decodeRender(r.bundle)) {
                is PdfRender.Ok ->
                    if (d.width == request.width && d.height == request.height &&
                        out.length() == d.width.toLong() * d.height.toLong() * 4L
                    ) {
                        d
                    } else {
                        PdfRender.Err(PdfError.Io(IOException("Render reply does not match the request")))
                    }
                is PdfRender.Err -> d
            }
            is Reply.Failed -> PdfRender.Err(r.error)
        }
        if (result is PdfRender.Err) out.delete()
        return result
    }

    /**
     * [PdfPageRenderer.pageText] in the engine process. The reply is untrusted: an `Ok` must report
     * at most [PageTextFormat.MAX_UNITS] units and [out] must exist and be no longer than
     * [PageTextFormat.MAX_FILE_BYTES] (the caller parses it with [PageTextFormat.read]); else `Err(Io)`.
     * [out] is deleted on every `Err`.
     */
    override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText = pageText(pdf, page, out, null, job)

    override fun pageText(pdf: File, page: Int, out: File, password: String?, job: JobControl): PdfPageText {
        if (page < 0) {
            out.delete()
            return PdfPageText.Err(PdfError.Io(IllegalArgumentException("page $page < 0")))
        }
        passwordError(password)?.let {
            out.delete()
            return PdfPageText.Err(it)
        }
        val result = when (val r = call(EngineRequest.PageText(pdf.path, page, out.path, password), job)) {
            is Reply.Done -> when (val d = EngineWire.decodePageText(r.bundle)) {
                is PdfPageText.Ok ->
                    if (d.units in 0..PageTextFormat.MAX_UNITS && out.isFile && out.length() >= PageTextFormat.HEADER_BYTES &&
                        out.length() <= PageTextFormat.MAX_FILE_BYTES
                    ) {
                        d
                    } else {
                        PdfPageText.Err(PdfError.Io(IOException("Text reply does not match its file")))
                    }
                is PdfPageText.Err -> d
            }
            is Reply.Failed -> PdfPageText.Err(r.error)
        }
        if (result is PdfPageText.Err) out.delete()
        return result
    }

    /**
     * [PdfPageRenderer.decryptCopy] in the engine process. The reply is untrusted: an `Ok` must
     * report at least one page and [out] must exist with exactly the reported size; else `Err(Io)`.
     * [out] is deleted on every `Err`. **Output watch** (Z3-1): while the engine writes, [out]'s
     * size is read about every [EngineLimits.decryptOutputSampleMs]; past [decryptOutputCap] the
     * engine is killed, [out] deleted and the call returns `Err(OutOfMemory)`.
     */
    override fun decryptCopy(pdf: File, out: File, password: String?, job: JobControl): PdfResult {
        passwordError(password)?.let {
            out.delete()
            return PdfResult.Err(it)
        }
        val watch = OutputWatch(out, decryptOutputCap(pdf, out))
        val result = when (val r = call(EngineRequest.DecryptCopy(pdf.path, out.path, password), job, watch)) {
            is Reply.Done -> when (val d = EngineWire.decodeResult(r.bundle)) {
                is PdfResult.Ok ->
                    if (d.pages > 0 && out.isFile && out.length() == d.bytes) {
                        if (d.bytes > watch.capBytes) PdfResult.Err(PdfError.OutOfMemory) else d
                    } else {
                        PdfResult.Err(PdfError.Io(IOException("Decrypted copy does not match its reply")))
                    }
                is PdfResult.Err -> d
            }
            is Reply.Failed -> PdfResult.Err(r.error)
        }
        if (result is PdfResult.Err) out.delete()
        return result
    }

    /**
     * Output watch cap of a decrypted copy (Z3-1): [DecryptCopyOp.maxOutputBytes] of the input, but
     * no more than the free space in [out]'s folder minus [DecryptCopyOp.SPACE_MARGIN_BYTES], so the
     * copy never fills the device (a copy that would not fit fails anyway). Free space unknown (0):
     * the size cap alone.
     */
    private fun decryptOutputCap(pdf: File, out: File): Long {
        val cap = DecryptCopyOp.maxOutputBytes(pdf.length(), limits.decryptOutputMarginBytes)
        val free = try {
            out.absoluteFile.parentFile?.usableSpace ?: 0L
        } catch (e: SecurityException) {
            0L
        }
        return if (free > 0) minOf(cap, maxOf(0L, free - DecryptCopyOp.SPACE_MARGIN_BYTES)) else cap
    }

    private fun result(request: EngineRequest, out: File, job: JobControl): PdfResult =
        when (val r = call(request, job)) {
            is Reply.Done -> EngineWire.decodeResult(r.bundle).also { if (it is PdfResult.Err) out.delete() }
            is Reply.Failed -> PdfResult.Err(r.error).also { out.delete() }
        }

    /** The live session, binding a new one if needed; null if binding failed. */
    private fun connected(): Session? {
        session?.let { if (!it.lost) return it else drop(it) }
        val s = Session()
        val service = connector.connect { s.markLost() } ?: return null
        s.service = service
        try {
            s.pid = service.pid()
        } catch (e: RemoteException) {
            s.lost = true
        } catch (e: RuntimeException) {
            s.lost = true
        }
        if (s.lost) {
            connector.disconnect()
            return null
        }
        session = s
        return s
    }

    private fun drop(s: Session) {
        s.events = null
        if (session === s) {
            session = null
            connector.disconnect()
        }
    }

    /**
     * Sends [request] and waits for its reply. If the request could not even be handed to the
     * engine (the binder was already dead, e.g. the engine was killed while idle and its death
     * notification had not arrived yet), it was never started, so it is sent once more on a
     * freshly bound engine.
     */
    private fun call(request: EngineRequest, job: JobControl, watch: OutputWatch? = null): Reply {
        if (!lockOrCancelled(job)) return Reply.Failed(PdfError.Cancelled)
        try {
            return attempt(request, job, watch) ?: attempt(request, job, watch) ?: unavailable
        } finally {
            lock.unlock()
        }
    }

    /**
     * Takes [lock], checking `job.isCancelled()` about every [pollMs] while another call holds it.
     * False (lock not taken) if the job was cancelled or the thread interrupted while waiting.
     */
    private fun lockOrCancelled(job: JobControl): Boolean {
        while (true) {
            if (job.isCancelled()) return false
            try {
                if (lock.tryLock(pollMs, TimeUnit.MILLISECONDS)) return true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * Unbinds the engine if no call is running (the next call binds again); returns false if a
     * call is running. The engine process then goes away once nothing else binds it.
     */
    fun release(): Boolean {
        if (!lock.tryLock()) return false
        try {
            session?.let { drop(it) }
            return true
        } finally {
            lock.unlock()
        }
    }

    /** One send-and-wait; null if the request never reached the engine. Holds [lock]. */
    private fun attempt(request: EngineRequest, job: JobControl, watch: OutputWatch?): Reply? {
        val s = connected() ?: return unavailable
        val service = s.service!!
        val id = nextId++
        val events = LinkedBlockingQueue<Event>()
        s.events = events
        if (s.lost) {
            drop(s)
            return null
        }
        val callback = object : IEngineCallback.Stub() {
            override fun progress(requestId: Long, done: Int, total: Int) {
                if (requestId == id) events.offer(Event.Progress(done, total))
            }

            override fun result(requestId: Long, result: Bundle?) {
                if (requestId == id && result != null) events.offer(Event.Result(result))
            }
        }
        try {
            service.run(id, EngineWire.encodeRequest(request), callback)
        } catch (e: RemoteException) {
            drop(s)
            return null
        }

        // With an output watch the loop wakes at least every decryptOutputSampleMs.
        val waitMs = if (watch != null) minOf(pollMs, limits.decryptOutputSampleMs).coerceAtLeast(1) else pollMs
        val outputSampleNs = TimeUnit.MILLISECONDS.toNanos(limits.decryptOutputSampleMs)
        val pollNs = TimeUnit.MILLISECONDS.toNanos(pollMs)
        val gapNs = TimeUnit.MILLISECONDS.toNanos(freezeGapMs)
        val thawWindowNs = TimeUnit.MILLISECONDS.toNanos(THAW_WINDOW_MS)
        val frozenDeathGapNs = TimeUnit.MILLISECONDS.toNanos(EngineLimits.FROZEN_DEATH_GAP_MS)
        val totalNs = TimeUnit.MILLISECONDS.toNanos(
            when {
                request is EngineRequest.PageInfo -> limits.infoTotalMs
                request is EngineRequest.RenderPage -> limits.renderTotalMs
                request is EngineRequest.PageText -> limits.textTotalMs
                request is EngineRequest.DecryptCopy -> limits.decryptTotalMs
                request.isImageRequest() -> limits.imageTotalMs
                else -> limits.totalMs
            },
        )
        // Reader requests report no progress: only their total limit applies.
        val stallNs = if (request is EngineRequest.PageInfo || request is EngineRequest.RenderPage || request is EngineRequest.PageText ||
            request is EngineRequest.DecryptCopy
        ) {
            Long.MAX_VALUE
        } else {
            TimeUnit.MILLISECONDS.toNanos(limits.stallMs)
        }
        var sentAt = nanoTime()
        var lastActivity = sentAt
        var nextRssSample = sentAt
        var nextOutputSample = sentAt
        var cancelDeadline = 0L
        var lastWake = sentAt
        var discountLeftNs = TimeUnit.MILLISECONDS.toNanos(maxFrozenMs)
        var thawedAt = 0L
        val cachedSampleNs = TimeUnit.MILLISECONDS.toNanos(BACKGROUND_SAMPLE_MS)
        var cached = backgrounded() // last sampled inBackground()
        var nextCachedSample = sentAt + cachedSampleNs
        var interrupted = false

        /**
         * Reads the clock; if the loop went more than [freezeGapMs] since the last read, this
         * process may have been frozen: shift the time deadlines by the gap (minus one poll,
         * within the [maxFrozenMs] budget) and make RSS due on this tick. A gap of at least
         * [EngineLimits.FROZEN_DEATH_GAP_MS], or a gap that began while the app was cached
         * (T22R-1), counts as a thaw for classifying a death; a shorter foreground gap does not
         * (PI4R-1). Then [inBackground] is sampled if due (always right after a gap).
         * Called after the wait and before the limit checks, so a freeze anywhere in the loop (in
         * the wait or in `job.progress`) is caught before it is judged.
         */
        fun wake(): Long {
            val now = nanoTime()
            val gap = now - lastWake
            lastWake = now
            if (gap > gapNs) {
                val discount = minOf(gap - pollNs, discountLeftNs)
                if (discount > 0) {
                    discountLeftNs -= discount
                    sentAt += discount
                    lastActivity += discount
                    if (cancelDeadline != 0L) cancelDeadline += discount
                }
                nextRssSample = now // the engine was not watched during the gap: check it now
                if (cached) thawedAt = now // cached before the gap: it was most likely a freeze
                nextCachedSample = now
            }
            if (gap >= frozenDeathGapNs) thawedAt = now
            if (now - nextCachedSample >= 0) {
                cached = backgrounded()
                nextCachedSample = now + cachedSampleNs
            }
            return now
        }
        try {
            while (true) {
                val event = try {
                    events.poll(waitMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                    null
                }
                // Freeze gap first, so a death noticed right after a thaw is classified correctly.
                val woke = wake()
                when (event) {
                    is Event.Progress -> {
                        lastActivity = woke
                        job.progress(event.done, event.total)
                    }
                    is Event.Result -> {
                        s.events = null
                        return Reply.Done(event.bundle)
                    }
                    Event.Lost -> {
                        drop(s)
                        val afterThaw = thawedAt != 0L && woke - thawedAt < thawWindowNs
                        return if (afterThaw || backgrounded()) unavailable else crashed
                    }
                    null -> Unit
                }
                val now = wake()
                // Output watch first: it also runs during a cancel grace (the save goes on writing).
                if (watch != null && now - nextOutputSample >= 0) {
                    nextOutputSample = now + outputSampleNs
                    if (watch.out.length() > watch.capBytes) {
                        killAndWait(s)
                        watch.out.delete()
                        val cancelled = cancelDeadline != 0L || interrupted || job.isCancelled()
                        return Reply.Failed(if (cancelled) PdfError.Cancelled else PdfError.OutOfMemory)
                    }
                }
                if (cancelDeadline == 0L && (interrupted || job.isCancelled())) {
                    cancelDeadline = now + TimeUnit.MILLISECONDS.toNanos(cancelGraceMs)
                    try {
                        service.cancel(id)
                    } catch (e: RemoteException) {
                        // Death is reported through the session.
                    }
                }
                if (cancelDeadline != 0L) {
                    // A user cancel wins over the watchdog: it ends as Cancelled either way.
                    if (now - cancelDeadline >= 0) {
                        killAndWait(s)
                        return Reply.Failed(PdfError.Cancelled)
                    }
                    continue
                }
                if (now - lastActivity >= stallNs || now - sentAt >= totalNs) {
                    killAndWait(s)
                    return Reply.Failed(PdfError.TimedOut)
                }
                if (limits.memoryBytes != Long.MAX_VALUE && s.pid > 0 && now - nextRssSample >= 0) {
                    nextRssSample = now + TimeUnit.MILLISECONDS.toNanos(limits.memorySampleMs)
                    if (rssOverLimit(s.pid)) {
                        killAndWait(s)
                        return Reply.Failed(PdfError.OutOfMemory)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun backgrounded(): Boolean = try {
        inBackground()
    } catch (e: RuntimeException) {
        false
    }

    /** True if [pid]'s resident memory exceeds the limit. Unreadable: false (fail open), logged once. */
    private fun rssOverLimit(pid: Int): Boolean {
        val rss = try {
            rssBytes(pid)
        } catch (e: RuntimeException) {
            null
        }
        if (rss == null) {
            if (!rssUnreadableLogged) {
                rssUnreadableLogged = true
                log("Cannot read the memory use of engine pid $pid; the engine memory limit is not enforced")
            }
            return false
        }
        return rss > limits.memoryBytes
    }

    /** A file the engine writes during one call, killed past [capBytes] (Z3-1; decrypted copy only). */
    private class OutputWatch(val out: File, val capBytes: Long)

    private fun EngineRequest.isImageRequest() =
        this is EngineRequest.Estimate || this is EngineRequest.NormalizeImage

    /**
     * Kills the stuck engine and waits up to [deathWaitMs] for its death notification, so the next
     * call binds a new process instead of the dying one.
     */
    private fun killAndWait(s: Session) {
        connector.kill(s.pid)
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(deathWaitMs)
        while (!s.lost && nanoTime() - deadline < 0) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        s.lost = true
        drop(s)
    }

    companion object {
        private const val TAG = "OffkitEngine"

        /**
         * A wait-loop gap longer than this (the loop wakes every ~100 ms) is not counted toward the
         * time limits. For classifying a death such a gap is a thaw only if the app was cached
         * before it; otherwise it takes [EngineLimits.FROZEN_DEATH_GAP_MS].
         */
        const val FREEZE_GAP_MS = 2_000L

        /** At most one hour of frozen time is discounted per call (see the class KDoc). */
        const val MAX_FROZEN_MS = 60 * 60_000L

        /**
         * A death noticed this soon after a thaw (see the class KDoc) happened while frozen:
         * `EngineUnavailable`.
         */
        const val THAW_WINDOW_MS = 5_000L

        /**
         * How often the wait loop samples [inBackground] (in production a binder call to the
         * system's `getMyMemoryState`): often enough to know, before a freeze, that the app was
         * cached, as the system normally freezes a cached app only some seconds after it became
         * cached. A freeze sooner than that is judged as before (foreground gap).
         */
        const val BACKGROUND_SAMPLE_MS = 1_000L
    }
}
