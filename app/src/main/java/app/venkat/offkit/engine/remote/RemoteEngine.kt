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
import app.venkat.offkit.engine.PdfResult
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
 * **Watchdog** ([limits]): MuPDF calls cannot be interrupted, so while waiting the client also
 * kills the engine and returns `Err(TimedOut)` when a PDF operation goes [EngineLimits.stallMs]
 * without a progress event or result, or runs past [EngineLimits.totalMs] (image estimate or
 * normalisation: [EngineLimits.imageTotalMs]); and returns `Err(OutOfMemory)` when the engine's
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
 * the first wake-up after such a gap. An engine death noticed within [THAW_WINDOW_MS] of a gap of
 * at least [EngineLimits.FROZEN_DEATH_GAP_MS] is `EngineUnavailable`; after a shorter gap (a GC
 * pause, a busy CPU) it is `EngineCrashed` unless [inBackground] says the app is cached (PI4R-1).
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
 *   is reported as `EngineUnavailable`, since the system may have killed the cached engine.
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
) : PdfEngine, ImagePreparer {
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
    private fun call(request: EngineRequest, job: JobControl): Reply {
        if (!lockOrCancelled(job)) return Reply.Failed(PdfError.Cancelled)
        try {
            return attempt(request, job) ?: attempt(request, job) ?: unavailable
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
    private fun attempt(request: EngineRequest, job: JobControl): Reply? {
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

        val pollNs = TimeUnit.MILLISECONDS.toNanos(pollMs)
        val gapNs = TimeUnit.MILLISECONDS.toNanos(freezeGapMs)
        val thawWindowNs = TimeUnit.MILLISECONDS.toNanos(THAW_WINDOW_MS)
        val frozenDeathGapNs = TimeUnit.MILLISECONDS.toNanos(EngineLimits.FROZEN_DEATH_GAP_MS)
        val totalNs = TimeUnit.MILLISECONDS.toNanos(if (request.isImageRequest()) limits.imageTotalMs else limits.totalMs)
        val stallNs = TimeUnit.MILLISECONDS.toNanos(limits.stallMs)
        var sentAt = nanoTime()
        var lastActivity = sentAt
        var nextRssSample = sentAt
        var cancelDeadline = 0L
        var lastWake = sentAt
        var discountLeftNs = TimeUnit.MILLISECONDS.toNanos(maxFrozenMs)
        var thawedAt = 0L
        var interrupted = false

        /**
         * Reads the clock; if the loop went more than [freezeGapMs] since the last read, this
         * process may have been frozen: shift the time deadlines by the gap (minus one poll,
         * within the [maxFrozenMs] budget) and sample RSS on this tick. Only a gap of at least
         * [EngineLimits.FROZEN_DEATH_GAP_MS] counts as a thaw for classifying a death (PI4R-1).
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
            }
            if (gap >= frozenDeathGapNs) thawedAt = now
            return now
        }
        try {
            while (true) {
                val event = try {
                    events.poll(pollMs, TimeUnit.MILLISECONDS)
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
         * time limits. Classifying a death needs a longer gap: [EngineLimits.FROZEN_DEATH_GAP_MS].
         */
        const val FREEZE_GAP_MS = 2_000L

        /** At most one hour of frozen time is discounted per call (see the class KDoc). */
        const val MAX_FROZEN_MS = 60 * 60_000L

        /**
         * A death noticed this soon after a gap of at least [EngineLimits.FROZEN_DEATH_GAP_MS]
         * happened while frozen: `EngineUnavailable`.
         */
        const val THAW_WINDOW_MS = 5_000L
    }
}
