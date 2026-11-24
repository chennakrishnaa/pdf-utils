package app.venkat.offkit

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.venkat.offkit.core.JobInput
import app.venkat.offkit.core.JobOutcome
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.remote.BoundServiceConnector
import app.venkat.offkit.engine.remote.EngineLimits
import app.venkat.offkit.engine.remote.ProcRss
import app.venkat.offkit.engine.remote.RemoteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * PI2 part B: the real `:engine` process dies (SIGKILL, SIGSEGV, killed while idle, stopped and
 * hard-cancelled) while this (UI/test) process keeps running, the job reports the right error, the
 * job directory is cleaned and the next job runs in a NEW engine process. Times are logged under
 * [TAG] (see the connected-test logcat files).
 */
@RunWith(AndroidJUnit4::class)
class EngineIsolationOnDeviceTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jobs: PdfJobs get() = PdfJobs.get(context)
    private val jobsRoot: File get() = File(context.cacheDir, "jobs")
    private val myPid = Process.myPid()

    companion object {
        private const val TAG = "OffkitIsolation"
        private lateinit var dir: File

        /** Big enough that compressing it takes well over a second on the emulator. */
        private lateinit var bigPdf: File
        private lateinit var smallPdf: File

        @BeforeClass
        @JvmStatic
        fun fixtures() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            dir = File(ctx.cacheDir, "isolation-fixtures").apply { deleteRecursively(); mkdirs() }
            bigPdf = Fixtures.photoPdf(dir, "big.pdf", pages = 16, imageWidth = 2400, imageHeight = 1800)
            smallPdf = Fixtures.textPdf(dir, "small.pdf", listOf("Small"))
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun enginePid(): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:engine" }?.pid
    }

    private fun assertJobsEmpty() {
        val left = jobsRoot.listFiles()?.toList().orEmpty()
        assertTrue("cacheDir/jobs not empty: $left", left.isEmpty())
    }

    /** An ordinary job that must succeed; returns the engine pid that ran it. */
    private fun ordinaryJobSucceeds(): Int {
        val outcome = runBlocking {
            jobs.run(listOf(JobInput.Pdf(Uri.fromFile(smallPdf))), { _, _ -> }) { e, f, o, j -> e.merge(f, o, j) }
        }
        if (outcome !is JobOutcome.Ready) fail("follow-up job: expected Ready, got $outcome")
        jobs.discard((outcome as JobOutcome.Ready).output)
        assertJobsEmpty()
        return enginePid() ?: fail("no :engine process after a successful job") as Nothing
    }

    /** Wraps [job]; runs [onFirst] (on the engine-calling thread) at the first progress report. */
    private class FirstProgress(private val job: JobControl, private val onFirst: () -> Unit) : JobControl {
        private val seen = AtomicInteger()
        override fun progress(done: Int, total: Int) {
            if (seen.getAndIncrement() == 0) onFirst()
            job.progress(done, total)
        }
        override fun isCancelled() = job.isCancelled()
    }

    /**
     * Compresses the big PDF and sends [signal] to the engine at its first progress report.
     * Asserts `Failed(EngineCrashed)` within 5 s of the signal, an empty jobs dir, and that the
     * next job succeeds in a new engine process. Returns the measured ms.
     */
    private fun deathMidJob(signal: Int, label: String): Long {
        ordinaryJobSucceeds() // engine bound and warm
        val killedPid = AtomicInteger(0)
        val killedAt = AtomicLong(0)
        val outcome = runBlocking {
            jobs.run(listOf(JobInput.Pdf(Uri.fromFile(bigPdf))), { _, _ -> }) { e, f, o, j ->
                e.compress(f[0], CompressionLevel.HIGH, o, FirstProgress(j) {
                    val pid = enginePid() ?: error("no :engine process mid-job")
                    killedPid.set(pid)
                    killedAt.set(SystemClock.elapsedRealtime())
                    Process.sendSignal(pid, signal)
                })
            }
        }
        val ms = SystemClock.elapsedRealtime() - killedAt.get()
        Log.i(TAG, "$label: engine pid ${killedPid.get()} -> $outcome after $ms ms")
        assertTrue("$label: the job never reported progress, so it was never killed", killedAt.get() != 0L)
        assertEquals("$label outcome", JobOutcome.Failed(PdfError.EngineCrashed), outcome)
        assertTrue("$label: EngineCrashed after $ms ms", ms < 5_000)
        assertEquals("test (UI) process survived", myPid, Process.myPid())
        assertJobsEmpty()
        val next = ordinaryJobSucceeds()
        assertNotEquals("$label: next job ran in the killed engine", killedPid.get(), next)
        Log.i(TAG, "$label: next job ran in new engine pid $next")
        return ms
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun sigkillMidJob_engineCrashed_cleanedUp_nextJobInNewEngine() {
        deathMidJob(Process.SIGNAL_KILL, "SIGKILL")
    }

    @Test
    fun sigsegvMidJob_engineCrashed_cleanedUp_nextJobInNewEngine() {
        deathMidJob(11, "SIGSEGV")
        // Nothing blocks afterwards: two more ordinary jobs in a row.
        ordinaryJobSucceeds()
        ordinaryJobSucceeds()
    }

    @Test
    fun killWhileIdle_nextJobSucceedsInNewEngine() {
        val before = ordinaryJobSucceeds()
        Process.killProcess(before)
        // Wait only until the system has noticed the death (an idle engine killed by the low-memory
        // killer looks like this when the user starts the next job).
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (enginePid() == before && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        val t0 = SystemClock.elapsedRealtime()
        val after = ordinaryJobSucceeds()
        Log.i(TAG, "idle kill: engine $before killed, next job in $after took ${SystemClock.elapsedRealtime() - t0} ms")
        assertNotEquals(before, after)
    }

    /** Cancels mid-job; [stopEngine] first freezes the engine (SIGSTOP) so it cannot honour it. */
    private fun cancelMidJob(stopEngine: Boolean, label: String) {
        ordinaryJobSucceeds()
        val started = CountDownLatch(1)
        val engineResult = AtomicReference<PdfResult?>(null)
        val returnedAt = AtomicLong(0)
        val stoppedPid = AtomicInteger(0)
        val cancelledAt = AtomicLong(0)
        runBlocking {
            val running = async(Dispatchers.Default) {
                jobs.run(listOf(JobInput.Pdf(Uri.fromFile(bigPdf))), { _, _ -> }) { e, f, o, j ->
                    e.compress(f[0], CompressionLevel.HIGH, o, FirstProgress(j) {
                        if (stopEngine) {
                            val pid = enginePid() ?: error("no :engine process mid-job")
                            stoppedPid.set(pid)
                            Process.sendSignal(pid, 19) // SIGSTOP
                        }
                        started.countDown()
                    }).also {
                        engineResult.set(it)
                        returnedAt.set(SystemClock.elapsedRealtime())
                    }
                }
            }
            assertTrue("$label: job never reported progress", started.await(30, TimeUnit.SECONDS))
            cancelledAt.set(SystemClock.elapsedRealtime())
            running.cancel()
            running.join()
        }
        val ms = returnedAt.get() - cancelledAt.get()
        Log.i(TAG, "$label: engine returned ${engineResult.get()} $ms ms after cancel")
        assertEquals("$label engine result", PdfResult.Err(PdfError.Cancelled), engineResult.get())
        assertTrue("$label: Cancelled after $ms ms", ms < 4_000)
        assertJobsEmpty()
        val next = ordinaryJobSucceeds()
        if (stopEngine) assertNotEquals("$label: stopped engine still serving", stoppedPid.get(), next)
    }

    @Test
    fun cancelMidJob_engineHonoursIt() {
        cancelMidJob(stopEngine = false, label = "cancel")
    }

    @Test
    fun cancelMidJob_stoppedEngineIsKilledWithin4s() {
        cancelMidJob(stopEngine = true, label = "cancel+SIGSTOP")
    }

    @Test
    fun engineIsNoMoreImportantThanTheForegroundUi() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val values = AtomicReference<Pair<Int, Int>?>(null)
            val outcome = runBlocking {
                jobs.run(listOf(JobInput.Pdf(Uri.fromFile(bigPdf))), { _, _ -> }) { e, f, o, j ->
                    e.compress(f[0], CompressionLevel.LOW, o, FirstProgress(j) {
                        val engine = enginePid() ?: error("no :engine process mid-job")
                        fun adj(pid: Int) = File("/proc/$pid/oom_score_adj").readText().trim().toInt()
                        values.set(adj(myPid) to adj(engine))
                    })
                }
            }
            if (outcome is JobOutcome.Ready) jobs.discard(outcome.output)
            val (ui, engine) = values.get() ?: fail("no progress, no oom_score_adj read") as Nothing
            Log.i(TAG, "oom_score_adj: UI pid $myPid = $ui, engine = $engine")
            assertTrue("engine oom_score_adj $engine < UI $ui: the engine would outlive the UI", engine >= ui)
        }
    }

    // ---------------------------------------------------------------- watchdog (PI3)

    /**
     * Compresses the big PDF with its own [RemoteEngine] (same `:engine` service as the app's)
     * using [limits], in a job directory under `cacheDir/jobs` that is deleted afterwards.
     * [onFirstProgress] runs at the first progress report. Returns the result.
     */
    private fun compressWith(limits: EngineLimits, onFirstProgress: () -> Unit = {}): PdfResult {
        val jobDir = File(jobsRoot, "pi3-${System.nanoTime()}").apply { File(this, "in").mkdirs() }
        try {
            val input = bigPdf.copyTo(File(jobDir, "in/in_000.pdf"))
            val out = File(jobDir, "output.pdf")
            val engine = RemoteEngine(BoundServiceConnector.forService(context), limits = limits)
            val r = engine.compress(input, CompressionLevel.HIGH, out, FirstProgress(JobControl.None, onFirstProgress))
            if (r !is PdfResult.Ok) assertTrue("partial output left after $r", !out.exists())
            return r
        } finally {
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun stoppedEngineWithoutCancel_timedOutByStallLimit_nextJobInNewEngine() {
        ordinaryJobSucceeds()
        val stoppedPid = AtomicInteger(0)
        val stoppedAt = AtomicLong(0)
        val r = compressWith(EngineLimits(stallMs = 3_000)) {
            val pid = enginePid() ?: error("no :engine process mid-job")
            stoppedPid.set(pid)
            stoppedAt.set(SystemClock.elapsedRealtime())
            Process.sendSignal(pid, 19) // SIGSTOP: no progress, no result, no death
        }
        val ms = SystemClock.elapsedRealtime() - stoppedAt.get()
        Log.i(TAG, "stall watchdog: engine ${stoppedPid.get()} stopped -> $r after $ms ms")
        assertTrue("the job never reported progress", stoppedAt.get() != 0L)
        assertEquals(PdfResult.Err(PdfError.TimedOut), r)
        assertTrue("TimedOut after $ms ms", ms in 2_900..5_000)
        assertJobsEmpty()
        val next = ordinaryJobSucceeds()
        assertNotEquals("stopped engine still serving", stoppedPid.get(), next)
        Log.i(TAG, "stall watchdog: next job ran in new engine pid $next")
    }

    @Test
    fun memoryLimitBelowEngineRss_outOfMemory_nextJobWithNormalLimitSucceeds() {
        val before = ordinaryJobSucceeds()
        val rss = ProcRss.bytes(before)
        Log.i(TAG, "memory watchdog: idle engine $before RSS ${rss?.div(1024 * 1024)} MiB")
        assertTrue("cannot read /proc/$before/statm", rss != null && rss > 1024 * 1024)
        val t0 = SystemClock.elapsedRealtime()
        val r = compressWith(EngineLimits(memoryBytes = 1024 * 1024))
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "memory watchdog: 1 MiB limit -> $r after $ms ms")
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), r)
        assertJobsEmpty()
        val appLimits = (context.applicationContext as PdfUtilApp).engineLimits()
        Log.i(TAG, "memory watchdog: production limit ${appLimits.memoryBytes / (1024 * 1024)} MiB")
        assertTrue(appLimits.memoryBytes in 64L * 1024 * 1024..EngineLimits.MEMORY_CEILING_BYTES)
        val ok = compressWith(appLimits)
        assertTrue("next job with the production limit: $ok", ok is PdfResult.Ok)
        val after = enginePid() ?: fail("no :engine process") as Nothing
        assertNotEquals("killed engine still serving", before, after)
        assertJobsEmpty()
    }

    /** Not an assertion of a limit: logs the engine's real memory use for judging the default. */
    @Test
    fun engineRssDuringAnOrdinaryCompressIsLogged() {
        ordinaryJobSucceeds()
        val peakSampled = AtomicLong(0)
        val enginePidSeen = AtomicInteger(0)
        val outcome = runBlocking {
            jobs.run(listOf(JobInput.Pdf(Uri.fromFile(bigPdf))), { _, _ -> }) { e, f, o, j ->
                e.compress(f[0], CompressionLevel.HIGH, o, object : JobControl {
                    override fun progress(done: Int, total: Int) {
                        val pid = enginePid() ?: return
                        enginePidSeen.set(pid)
                        ProcRss.bytes(pid)?.let { if (it > peakSampled.get()) peakSampled.set(it) }
                        j.progress(done, total)
                    }
                    override fun isCancelled() = j.isCancelled()
                })
            }
        }
        if (outcome is JobOutcome.Ready) jobs.discard(outcome.output) else fail("compress: $outcome")
        val pid = enginePidSeen.get()
        val end = ProcRss.bytes(pid)
        val hwm = File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.trim()
        val mib = 1024 * 1024
        Log.i(
            TAG,
            "engine RSS: ordinary HIGH compress of ${bigPdf.length() / 1024} KiB, 16 photo pages: " +
                "end ${end?.div(mib)} MiB, peak sampled at progress ${peakSampled.get() / mib} MiB, $hwm",
        )
        assertTrue(end != null)
    }

    @Test
    fun applicationEngineIsTheRemoteOne() {
        assertTrue((context.applicationContext as PdfUtilApp).engine is RemoteEngine)
    }
}
