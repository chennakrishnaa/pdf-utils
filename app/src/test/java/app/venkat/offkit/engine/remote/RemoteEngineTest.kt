package app.venkat.offkit.engine.remote

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.RenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RemoteEngine] against an in-JVM engine service ([EngineServer] with [FakePdfEngine], called
 * through its local AIDL stub). Binding, death and killing are injected; no Android service is
 * bound and MuPDF is never loaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { File(tmp.root, "cache/jobs").apply { mkdirs() } }

    private class Job : JobControl {
        val steps: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()
        val threads: MutableList<Thread> = CopyOnWriteArrayList()
        val cancelled = AtomicBoolean(false)
        override fun progress(done: Int, total: Int) {
            threads += Thread.currentThread()
            steps += done to total
        }
        override fun isCancelled() = cancelled.get()
    }

    /** Connector handing out a fresh service per connect; records disconnects and kills. */
    private class FakeConnector(val service: () -> IEngineService?) : EngineConnector {
        val connects = AtomicInteger()
        val disconnects = AtomicInteger()
        val killed: MutableList<Int> = CopyOnWriteArrayList()
        @Volatile var onLost: (() -> Unit)? = null
        override fun connect(onLost: () -> Unit): IEngineService? {
            connects.incrementAndGet()
            this.onLost = onLost
            return service()
        }
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun kill(pid: Int) {
            killed += pid
            onLost?.invoke() // the process dies: its binder death arrives
        }
    }

    private fun jobDir(): Triple<File, File, File> {
        val dir = File(root, "j${System.nanoTime()}").apply { File(this, "in").mkdirs() }
        val input = File(dir, "in/in_000.pdf").apply { writeText("%PDF") }
        return Triple(dir, input, File(dir, "output.pdf"))
    }

    private fun server(fake: FakePdfEngine) = EngineServer({ fake }, root, myPid = { 4242 })

    @Test
    fun resultAndAllProgressInOrderOnCallingThread() {
        val fake = FakePdfEngine().apply { progressSteps = 200 }
        val engine = RemoteEngine(FakeConnector { server(fake) })
        val (_, input, out) = jobDir()
        val job = Job()
        val r = engine.compress(input, CompressionLevel.HIGH, out, job)
        assertEquals(PdfResult.Ok(1, fake.outputContent.size.toLong()), r)
        assertEquals((1..200).map { it to 200 }, job.steps)
        assertTrue(job.threads.all { it === Thread.currentThread() })
        assertTrue(out.isFile)
        assertEquals(FakePdfEngine.Op.COMPRESS, fake.calls.single().op)
        assertEquals(CompressionLevel.HIGH, fake.calls.single().level)
    }

    @Test
    fun everyOperationAndEstimateRoundTrip() {
        val fake = FakePdfEngine()
        val conn = FakeConnector { server(fake) }
        val engine = RemoteEngine(conn)
        val (_, input, out) = jobDir()
        assertTrue(engine.imagesToPdf(listOf(input), PageSize.A4, out, JobControl.None, CompressionLevel.LOW) is PdfResult.Ok)
        assertTrue(engine.merge(listOf(input, input), out, JobControl.None) is PdfResult.Ok)
        fake.setResult(FakePdfEngine.Op.APPEND_IMAGES, PdfResult.Err(PdfError.Encrypted))
        assertEquals(PdfResult.Err(PdfError.Encrypted), engine.appendImages(input, listOf(input), out, JobControl.None))
        val e = engine.estimateImage(input) as PdfEstimate.Ok
        assertEquals(input.length(), e.original)
        assertEquals(PageSize.A4, fake.calls[0].pageSize)
        assertEquals(CompressionLevel.LOW, fake.calls[0].compression)
        assertEquals(1, conn.connects.get()) // stays bound across calls
    }

    @Test
    fun pathOutsideJobsIsRejectedThroughTheClient() {
        val fake = FakePdfEngine()
        val engine = RemoteEngine(FakeConnector { server(fake) })
        val r = engine.merge(listOf(tmp.newFile("x.pdf")), File(root, "j/output.pdf"), JobControl.None)
        assertTrue(((r as PdfResult.Err).error as PdfError.Io).cause.message!!.contains("SecurityException"))
        assertTrue(fake.calls.isEmpty())
        val e = engine.estimateImage(tmp.newFile("x.jpg"))
        assertTrue("$e", ((e as PdfEstimate.Err).error as PdfError.Io).cause.message!!.contains("SecurityException"))
        assertTrue(fake.estimateCalls.isEmpty())
    }

    @Test
    fun deathMidCallReturnsEngineCrashedPromptlyDeletesOutAndNextCallWorks() {
        val fake = FakePdfEngine()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        fake.beforeStep = { _, step -> if (step == 2) { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val conn = FakeConnector { server(fake) }
        val engine = RemoteEngine(conn)
        val (_, input, out) = jobDir()
        out.writeText("partial")
        val job = Job()
        Thread {
            started.await(5, TimeUnit.SECONDS)
            conn.onLost!!.invoke()
        }.start()
        val t0 = System.nanoTime()
        val r = engine.merge(listOf(input), out, job)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(PdfResult.Err(PdfError.EngineCrashed), r)
        assertTrue("took $ms ms", ms < 1_000)
        assertFalse(out.exists())
        assertEquals(listOf(1 to 3), job.steps)
        assertEquals(1, conn.disconnects.get())
        release.countDown()

        // Next call rebinds and works.
        val fake2 = FakePdfEngine()
        val conn2Engine = RemoteEngine(FakeConnector { server(fake2) })
        assertTrue(conn2Engine.merge(listOf(input), out, JobControl.None) is PdfResult.Ok)
        fake.beforeStep = null
        val again = engine.merge(listOf(input), out, Job())
        assertTrue("$again", again is PdfResult.Ok)
        assertEquals(2, conn.connects.get())
    }

    @Test
    fun estimateDuringDeathIsEngineCrashed() {
        val conn = FakeConnector {
            object : IEngineService.Stub() {
                override fun pid() = 4242
                override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {}
                override fun cancel(requestId: Long) {}
            }
        }
        val engine = RemoteEngine(conn)
        Thread { Thread.sleep(200); conn.onLost!!.invoke() }.start()
        assertEquals(PdfEstimate.Err(PdfError.EngineCrashed), engine.estimateImage(File(root, "x.jpg")))
    }

    @Test
    fun cancelHonouredByServiceReturnsCancelledWithoutKill() {
        val fake = FakePdfEngine().apply { progressSteps = 1000 }
        val job = Job()
        fake.beforeStep = { _, step -> if (step == 5) job.cancelled.set(true); Thread.sleep(5) }
        val conn = FakeConnector { server(fake) }
        val (_, input, out) = jobDir()
        val r = RemoteEngine(conn).merge(listOf(input), out, job)
        assertEquals(PdfResult.Err(PdfError.Cancelled), r)
        assertTrue(conn.killed.isEmpty())
    }

    @Test
    fun cancelIgnoredByServiceKillsEngineAndReturnsCancelled() {
        val cancels = AtomicInteger()
        val conn = FakeConnector {
            object : IEngineService.Stub() {
                override fun pid() = 4242
                override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
                    callback!!.progress(requestId, 1, 10) // then hangs forever
                }
                override fun cancel(requestId: Long) { cancels.incrementAndGet() }
            }
        }
        val engine = RemoteEngine(conn, cancelGraceMs = 300)
        val (_, _, out) = jobDir()
        out.writeText("partial")
        val job = Job()
        Thread { Thread.sleep(150); job.cancelled.set(true) }.start()
        val t0 = System.nanoTime()
        val r = engine.compress(File(root, "a.pdf"), CompressionLevel.LOW, out, job)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(PdfResult.Err(PdfError.Cancelled), r)
        assertEquals(listOf(4242), conn.killed)
        assertEquals(1, cancels.get())
        assertEquals(listOf(1 to 10), job.steps)
        assertFalse(out.exists())
        assertTrue("took $ms ms", ms in 400..2_000)
        assertEquals(1, conn.disconnects.get())
    }

    // ------------------------------------------------ watchdog (PI3)

    /** Engine stub that accepts every request, reports one progress event, then never answers. */
    private fun hangingService(cancels: AtomicInteger = AtomicInteger()) = object : IEngineService.Stub() {
        override fun pid() = 4242
        override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
            callback!!.progress(requestId, 1, 10)
        }
        override fun cancel(requestId: Long) { cancels.incrementAndGet() }
    }

    /** First connect gets [first], later ones a working [EngineServer] over [fake]. */
    private fun firstThenWorking(first: IEngineService, fake: FakePdfEngine = FakePdfEngine()): FakeConnector {
        val n = AtomicInteger()
        return FakeConnector { if (n.getAndIncrement() == 0) first else server(fake) }
    }

    private fun elapsedMs(t0: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)

    @Test
    fun stallKillsEngineReturnsTimedOutDeletesOutAndNextCallWorks() {
        val conn = firstThenWorking(hangingService())
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 200))
        val (_, input, out) = jobDir()
        out.writeText("partial")
        val job = Job()
        val t0 = System.nanoTime()
        val r = engine.compress(input, CompressionLevel.LOW, out, job)
        val ms = elapsedMs(t0)
        assertEquals(PdfResult.Err(PdfError.TimedOut), r)
        assertEquals(listOf(4242), conn.killed)
        assertEquals(listOf(1 to 10), job.steps)
        assertFalse(out.exists())
        assertTrue("took $ms ms", ms in 200..2_000)
        assertEquals(1, conn.disconnects.get())
        assertTrue(engine.compress(input, CompressionLevel.LOW, out, Job()) is PdfResult.Ok)
        assertEquals(2, conn.connects.get())
    }

    @Test
    fun progressRestartsTheStallClock() {
        val fake = FakePdfEngine().apply { progressSteps = 12 }
        fake.beforeStep = { _, _ -> Thread.sleep(50) } // ~600 ms in all, progress every 50 ms
        val conn = FakeConnector { server(fake) }
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        val r = RemoteEngine(conn, limits = EngineLimits(stallMs = 200)).merge(listOf(input), out, Job())
        assertTrue("$r", r is PdfResult.Ok)
        assertTrue("finished too fast to prove anything", elapsedMs(t0) > 400)
        assertTrue(conn.killed.isEmpty())
    }

    @Test
    fun totalLimitStopsAJobThatKeepsReportingProgress() {
        val stop = AtomicBoolean(false)
        val chatty = object : IEngineService.Stub() {
            override fun pid() = 4242
            override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
                Thread {
                    var i = 0
                    while (!stop.get()) { callback!!.progress(requestId, ++i, 1_000_000); Thread.sleep(20) }
                }.start()
            }
            override fun cancel(requestId: Long) {}
        }
        val conn = FakeConnector { chatty }
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        val r = RemoteEngine(conn, limits = EngineLimits(stallMs = 200, totalMs = 500)).merge(listOf(input), out, Job())
        val ms = elapsedMs(t0)
        stop.set(true)
        assertEquals(PdfResult.Err(PdfError.TimedOut), r)
        assertTrue("took $ms ms", ms in 500..2_500)
        assertEquals(listOf(4242), conn.killed)
    }

    @Test
    fun estimateAndNormaliseUseTheShortImageLimit() {
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 60_000, totalMs = 60_000, imageTotalMs = 200))
        var t0 = System.nanoTime()
        assertEquals(PdfEstimate.Err(PdfError.TimedOut), engine.estimateImage(File(root, "x.jpg")))
        assertTrue(elapsedMs(t0) < 2_000)
        val dir = File(root, "jimg/in").apply { mkdirs() }
        val raw = File(dir, "raw_000").apply { writeText("x") }
        t0 = System.nanoTime()
        assertEquals(
            app.venkat.offkit.core.PreparedImage.Err(PdfError.TimedOut),
            engine.prepareImage(raw, dir, 0, JobControl.None),
        )
        assertTrue(elapsedMs(t0) < 2_000)
        assertFalse(raw.exists())
        assertEquals(listOf(4242, 4242), conn.killed)
    }

    @Test
    fun memoryOverLimitKillsEngineAndReturnsOutOfMemory() {
        val conn = firstThenWorking(hangingService())
        val sampled: MutableList<Int> = CopyOnWriteArrayList()
        val engine = RemoteEngine(
            conn,
            limits = EngineLimits(memoryBytes = 1_000, memorySampleMs = 50),
            rssBytes = { pid -> sampled += pid; 2_000L },
        )
        val (_, input, out) = jobDir()
        out.writeText("partial")
        val t0 = System.nanoTime()
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(input, CompressionLevel.LOW, out, Job()))
        assertTrue(elapsedMs(t0) < 2_000)
        assertEquals(listOf(4242), conn.killed)
        assertEquals(4242, sampled.first())
        assertFalse(out.exists())
        // Under the limit, an ordinary job completes; RSS is sampled while it runs.
        val fake = FakePdfEngine().apply { progressSteps = 5; beforeStep = { _, _ -> Thread.sleep(30) } }
        val ok = RemoteEngine(FakeConnector { server(fake) }, limits = EngineLimits(memoryBytes = 1_000, memorySampleMs = 10), rssBytes = { 999L })
        assertTrue(ok.compress(input, CompressionLevel.LOW, out, Job()) is PdfResult.Ok)
    }

    @Test
    fun unreadableRssFailsOpenAndLogsOnce() {
        val fake = FakePdfEngine().apply { progressSteps = 5; beforeStep = { _, _ -> Thread.sleep(30) } }
        val conn = FakeConnector { server(fake) }
        val logs: MutableList<String> = CopyOnWriteArrayList()
        val engine = RemoteEngine(
            conn,
            limits = EngineLimits(memoryBytes = 1, memorySampleMs = 10),
            rssBytes = { null },
            log = { logs += it },
        )
        val (_, input, out) = jobDir()
        assertTrue(engine.compress(input, CompressionLevel.LOW, out, Job()) is PdfResult.Ok)
        assertTrue(engine.merge(listOf(input), out, Job()) is PdfResult.Ok)
        assertTrue(conn.killed.isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun cancelDuringAStalledJobIsCancelledNotTimedOut() {
        val cancels = AtomicInteger()
        val conn = FakeConnector { hangingService(cancels) }
        val engine = RemoteEngine(conn, cancelGraceMs = 500, limits = EngineLimits(stallMs = 300))
        val (_, input, out) = jobDir()
        val job = Job()
        Thread { Thread.sleep(100); job.cancelled.set(true) }.start()
        assertEquals(PdfResult.Err(PdfError.Cancelled), engine.compress(input, CompressionLevel.LOW, out, job))
        assertEquals(1, cancels.get())
        assertEquals(listOf(4242), conn.killed)
    }

    @Test
    fun memoryLimitIsTheSmallerOfCeilingAndFortyPercentOfRam() {
        val gib = 1024L * 1024 * 1024
        assertEquals(EngineLimits.MEMORY_CEILING_BYTES, EngineLimits.memoryLimitFor(8 * gib))
        assertEquals(2 * gib / 100 * 40, EngineLimits.memoryLimitFor(2 * gib))
        assertEquals(EngineLimits.MEMORY_CEILING_BYTES, EngineLimits.memoryLimitFor(0))
        assertEquals(1536L * 1024 * 1024, EngineLimits.MEMORY_CEILING_BYTES)
        assertEquals(3L * 4096, ProcRss.parseStatm("100 3 2 1 0 5 0\n", 4096))
        assertEquals(null, ProcRss.parseStatm("garbage", 4096))
        assertEquals(null, ProcRss.bytes(Int.MAX_VALUE))
    }

    // ------------------------------------------------ frozen app (FR-1), causes (FR-3), lock (FR-9)

    /** Real monotonic time plus a jump the test controls: a jump looks like a frozen process. */
    private class JumpClock {
        val offset = java.util.concurrent.atomic.AtomicLong()
        fun now() = System.nanoTime() + offset.get()
        fun jumpMinutes(m: Long) { offset.addAndGet(TimeUnit.MINUTES.toNanos(m)) }
        fun jumpMs(ms: Long) { offset.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)) }
    }

    /** Compress on a hanging engine whose death arrives right after [jump] moves the clock. */
    private fun deathAfterGap(clock: () -> Long, jump: () -> Unit, background: Boolean = false): PdfResult {
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, nanoTime = clock, inBackground = { background })
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) {
                jump()
                conn.onLost!!.invoke()
            }
            override fun isCancelled() = false
        }
        val (_, input, out) = jobDir()
        return engine.compress(input, CompressionLevel.LOW, out, job)
    }

    @Test
    fun foregroundDeathAfterAShortSchedulingGapIsEngineCrashed() {
        // PI4R-1: a 3 s pause (GC, loaded CPU) in the foreground is not a freeze; the crash is the file's.
        val clock = JumpClock()
        assertEquals(PdfResult.Err(PdfError.EngineCrashed), deathAfterGap(clock::now, { clock.jumpMs(3_000) }))
        // The same short gap while the app is cached: the system may have killed the engine.
        assertEquals(
            PdfResult.Err(PdfError.EngineUnavailable),
            deathAfterGap(clock::now, { clock.jumpMs(3_000) }, background = true),
        )
        // A long freeze (app was frozen, now foreground again): still EngineUnavailable.
        assertEquals(PdfResult.Err(PdfError.EngineUnavailable), deathAfterGap(clock::now, { clock.jumpMinutes(10) }))
    }

    @Test
    fun frozenDeathGapBoundaryIsExact() {
        // A manual clock: only the jump moves it, so the gap is exactly the jump.
        val t = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val threshold = TimeUnit.MILLISECONDS.toNanos(EngineLimits.FROZEN_DEATH_GAP_MS)
        assertEquals(
            PdfResult.Err(PdfError.EngineCrashed),
            deathAfterGap(t::get, { t.addAndGet(threshold - TimeUnit.MILLISECONDS.toNanos(1)) }),
        )
        assertEquals(PdfResult.Err(PdfError.EngineUnavailable), deathAfterGap(t::get, { t.addAndGet(threshold) }))
    }

    @Test
    fun rssIsSampledOnTheFirstTickAfterAGapAndTheCapStillKills() {
        val clock = JumpClock()
        val samples = AtomicInteger()
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(
            conn,
            // Next regular sample only after 60 s; the stall limit would end the call after 3 s.
            limits = EngineLimits(stallMs = 3_000, memoryBytes = 1_000, memorySampleMs = 60_000),
            // The first sample is under the cap and is followed by a 30 s freeze, shorter than the
            // sample interval (T22R-5: the jump is driven by the sample itself, not by a timer).
            rssBytes = {
                if (samples.incrementAndGet() == 1) 10L.also { clock.jumpMs(30_000) } else 2_000L
            },
            nanoTime = clock::now,
        )
        val (_, input, out) = jobDir()
        // Without the sample right after the gap the next one would be due 30 s later, so the call
        // would end TimedOut by the 3 s stall limit (a real stall of over 2 s is itself a gap,
        // which samples too), never OutOfMemory. So no wall-time assertion is needed.
        assertEquals(PdfResult.Err(PdfError.OutOfMemory), engine.compress(input, CompressionLevel.LOW, out, Job()))
        assertEquals(2, samples.get())
        assertEquals(listOf(4242), conn.killed)
    }

    /** Hand-moved clock and "app is cached" flag for [deathAfterSteps]. */
    private class Hand {
        val t = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val cached = AtomicBoolean(false)
        fun ms(n: Long) { t.addAndGet(TimeUnit.MILLISECONDS.toNanos(n)) }
    }

    /**
     * Compress on an engine that reports one progress event per step and never answers. Each
     * step runs in its progress callback (on the waiting thread, so between two wake-ups) and may
     * move the clock or the cached flag; the engine dies right after the last step. Only the
     * steps move the clock, so every gap is exact.
     */
    private fun deathAfterSteps(vararg steps: Hand.() -> Unit, startCached: Boolean = false): PdfResult {
        val hand = Hand().apply { cached.set(startCached) }
        val conn = FakeConnector {
            object : IEngineService.Stub() {
                override fun pid() = 4242
                override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
                    for (i in 1..steps.size) callback!!.progress(requestId, i, steps.size)
                }
                override fun cancel(requestId: Long) {}
            }
        }
        val engine = RemoteEngine(conn, nanoTime = hand.t::get, inBackground = hand.cached::get)
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) {
                steps[done - 1](hand)
                if (done == total) conn.onLost!!.invoke()
            }
            override fun isCancelled() = false
        }
        val (_, input, out) = jobDir()
        return engine.compress(input, CompressionLevel.LOW, out, job)
    }

    private val crashed = PdfResult.Err(PdfError.EngineCrashed)
    private val unavailable = PdfResult.Err(PdfError.EngineUnavailable)

    @Test
    fun shortGapThatBeganWhileCachedIsAThaw() {
        // T22R-1: seen cached, then a 3 s freeze; the user came back, so at death it is foreground.
        assertEquals(unavailable, deathAfterSteps({ cached.set(true); ms(1_000) }, { cached.set(false); ms(3_000) }))
        // Cached already when the call started (sampled then), foreground after a 3 s freeze.
        assertEquals(unavailable, deathAfterSteps({ cached.set(false); ms(3_000) }, startCached = true))
    }

    @Test
    fun shortGapAfterTheUserReturnedIsNotAThaw() {
        // Seen cached, then seen foreground again (the user came back), then a 3 s GC pause.
        assertEquals(
            crashed,
            deathAfterSteps({ cached.set(true); ms(1_000) }, { cached.set(false); ms(1_000) }, { ms(3_000) }),
        )
        // Seen cached, then a gap of exactly the freeze gap: not longer than it, so not a thaw.
        assertEquals(crashed, deathAfterSteps({ cached.set(true); ms(1_000) }, { cached.set(false); ms(RemoteEngine.FREEZE_GAP_MS) }))
    }

    @Test
    fun deathLongAfterAThawIsEngineCrashed() {
        val second: Hand.() -> Unit = { ms(1_000) }
        // Thaw by a short gap that began while cached, then 6 s in the foreground before the death.
        assertEquals(crashed, deathAfterSteps({ cached.set(true); ms(1_000) }, { cached.set(false); ms(3_000) }, *Array(6) { second }))
        // Control: 4 s after it is still within the thaw window.
        assertEquals(unavailable, deathAfterSteps({ cached.set(true); ms(1_000) }, { cached.set(false); ms(3_000) }, *Array(4) { second }))
        // The same after a 10-minute gap (T22R-6).
        assertEquals(crashed, deathAfterSteps({ ms(600_000) }, *Array(6) { second }))
        assertEquals(unavailable, deathAfterSteps({ ms(600_000) }, *Array(4) { second }))
    }

    @Test
    fun severalShortForegroundGapsNeverAddUpToAThaw() {
        // Five 3 s pauses (15 s in all) in the foreground, the death right after the last.
        val pause: Hand.() -> Unit = { ms(3_000) }
        assertEquals(crashed, deathAfterSteps(*Array(5) { pause }))
        // Nor does a 9.9 s pause right after one (each gap is judged alone).
        assertEquals(crashed, deathAfterSteps({ ms(9_900) }, { ms(9_900) }))
    }

    @Test
    fun tenMinuteFreezeBetweenIterationsIsNotCountedTowardTheLimits() {
        val clock = JumpClock()
        val fake = FakePdfEngine().apply { progressSteps = 6; beforeStep = { _, _ -> Thread.sleep(50) } }
        val conn = FakeConnector { server(fake) }
        val (_, input, out) = jobDir()
        val job = object : JobControl {
            val n = AtomicInteger()
            override fun progress(done: Int, total: Int) { if (n.incrementAndGet() == 3) clock.jumpMinutes(10) }
            override fun isCancelled() = false
        }
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 1_000, totalMs = 2_000), nanoTime = clock::now)
        assertTrue(engine.merge(listOf(input), out, job) is PdfResult.Ok)
        assertTrue(conn.killed.isEmpty())

        // A jump while the client waits in poll (another thread) is not counted either.
        val fake2 = FakePdfEngine().apply { progressSteps = 6; beforeStep = { _, step -> Thread.sleep(50); if (step == 3) clock.jumpMinutes(10) } }
        val engine2 = RemoteEngine(FakeConnector { server(fake2) }, limits = EngineLimits(stallMs = 1_000, totalMs = 2_000), nanoTime = clock::now)
        assertTrue(engine2.merge(listOf(input), out, Job()) is PdfResult.Ok)
    }

    @Test
    fun genuineStallAfterAFreezeIsStillTimedOut() {
        val clock = JumpClock()
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 500), nanoTime = clock::now)
        Thread { Thread.sleep(200); clock.jumpMinutes(10) }.start()
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        assertEquals(PdfResult.Err(PdfError.TimedOut), engine.compress(input, CompressionLevel.LOW, out, Job()))
        val ms = elapsedMs(t0)
        // Not at the jump (~200 ms): only after 500 ms of observed time without progress.
        assertTrue("took $ms ms", ms in 450..2_500)
        assertEquals(listOf(4242), conn.killed)
    }

    @Test
    fun repeatedFreezesCannotPostponeTheLimitsBeyondTheDiscountBudget() {
        val clock = JumpClock()
        val stop = AtomicBoolean(false)
        Thread { while (!stop.get()) { Thread.sleep(150); clock.jumpMinutes(10) } }.start()
        val conn = FakeConnector { hangingService() }
        // Budget 25 min: the third 10-minute jump exhausts it and the rest counts, so the 60 s
        // stall limit fires within a few jumps although no jump is fully counted before.
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 60_000, totalMs = 120_000), nanoTime = clock::now, maxFrozenMs = 25 * 60_000L)
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        val r = engine.compress(input, CompressionLevel.LOW, out, Job())
        val ms = elapsedMs(t0)
        stop.set(true)
        assertEquals(PdfResult.Err(PdfError.TimedOut), r)
        assertTrue("took $ms ms", ms in 300..3_000)
    }

    @Test
    fun deathNoticedRightAfterAFreezeIsEngineUnavailable() {
        val clock = JumpClock()
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, nanoTime = clock::now)
        val (_, input, out) = jobDir()
        out.writeText("partial")
        val job = object : JobControl {
            override fun progress(done: Int, total: Int) {
                clock.jumpMinutes(10) // frozen in the background ...
                conn.onLost!!.invoke() // ... and the system killed the cached engine meanwhile
            }
            override fun isCancelled() = false
        }
        assertEquals(PdfResult.Err(PdfError.EngineUnavailable), engine.compress(input, CompressionLevel.LOW, out, job))
        assertFalse(out.exists())
        // Death reported a few polls after the thaw: still EngineUnavailable.
        val engine2 = RemoteEngine(conn, nanoTime = clock::now)
        val job2 = object : JobControl {
            override fun progress(done: Int, total: Int) {
                clock.jumpMinutes(10)
                Thread { Thread.sleep(300); conn.onLost!!.invoke() }.start()
            }
            override fun isCancelled() = false
        }
        assertEquals(PdfResult.Err(PdfError.EngineUnavailable), engine2.compress(input, CompressionLevel.LOW, out, job2))
    }

    @Test
    fun deathWhileTheAppIsCachedIsEngineUnavailableInTheForegroundEngineCrashed() {
        for ((background, expected) in listOf(true to PdfError.EngineUnavailable, false to PdfError.EngineCrashed)) {
            val conn = FakeConnector { hangingService() }
            val engine = RemoteEngine(conn, inBackground = { background })
            val job = object : JobControl {
                override fun progress(done: Int, total: Int) { conn.onLost!!.invoke() }
                override fun isCancelled() = false
            }
            val (_, input, out) = jobDir()
            assertEquals(PdfResult.Err(expected), engine.compress(input, CompressionLevel.LOW, out, job))
        }
    }

    @Test
    fun callWaitingForTheLockSeesItsCancel() {
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, cancelGraceMs = 200, limits = EngineLimits(stallMs = 60_000))
        val (_, input, out) = jobDir()
        val first = Job()
        val firstStarted = CountDownLatch(1)
        val firstResult = java.util.concurrent.atomic.AtomicReference<PdfResult>()
        val t = Thread {
            firstResult.set(engine.compress(input, CompressionLevel.LOW, out, object : JobControl {
                override fun progress(done: Int, total: Int) { firstStarted.countDown() }
                override fun isCancelled() = first.isCancelled()
            }))
        }.apply { start() }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = Job()
        Thread { Thread.sleep(200); second.cancelled.set(true) }.start()
        val t0 = System.nanoTime()
        val dir = File(root, "jlock/in").apply { mkdirs() }
        val raw = File(dir, "raw_000").apply { writeText("x") }
        assertEquals(
            app.venkat.offkit.core.PreparedImage.Err(PdfError.Cancelled),
            engine.prepareImage(raw, dir, 0, second),
        )
        val ms = elapsedMs(t0)
        assertTrue("took $ms ms", ms in 150..1_500)
        assertTrue("first call must still be running", t.isAlive)
        first.cancelled.set(true)
        t.join(5_000)
        assertEquals(PdfResult.Err(PdfError.Cancelled), firstResult.get())
    }

    // ------------------------------------------------ BoundServiceConnector (real connector logic)

    @Test
    fun connectTimeoutReturnsEngineUnavailableAndUnbinds() {
        val unbinds = AtomicInteger()
        val conn = BoundServiceConnector(bind = { true }, unbind = { unbinds.incrementAndGet() }, timeoutMs = 200)
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        val r = RemoteEngine(conn).merge(listOf(input), out, JobControl.None)
        assertEquals(PdfResult.Err(PdfError.EngineUnavailable), r)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        assertEquals(1, unbinds.get())
    }

    @Test
    fun disconnectUnlinksTheDeathRecipient() {
        val linked: MutableList<android.os.IBinder.DeathRecipient> = CopyOnWriteArrayList()
        val unlinked: MutableList<android.os.IBinder.DeathRecipient> = CopyOnWriteArrayList()
        val service = object : IEngineService.Stub() {
            override fun pid() = 4242
            override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {}
            override fun cancel(requestId: Long) {}
            override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) { linked += recipient }
            override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int): Boolean {
                unlinked += recipient
                return true
            }
        }
        val name = ComponentName("app.venkat.offkit", EngineService::class.java.name)
        val conn = BoundServiceConnector(bind = { c -> c.onServiceConnected(name, service); true }, unbind = {})
        assertTrue(conn.connect {} != null)
        assertEquals(1, linked.size)
        conn.disconnect()
        assertEquals(linked, unlinked)
        conn.disconnect() // twice: no second unlink
        assertEquals(1, unlinked.size)
    }

    @Test
    fun bindFailureReturnsEngineUnavailable() {
        val conn = BoundServiceConnector(bind = { false }, unbind = {}, timeoutMs = 200)
        assertEquals(PdfEstimate.Err(PdfError.EngineUnavailable), RemoteEngine(conn).estimateImage(File(root, "x")))
    }

    @Test
    fun serviceDisconnectMidCallThroughRealConnectorThenRebinds() {
        val fake = FakePdfEngine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step -> if (step == 1) { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val connections: MutableList<ServiceConnection> = CopyOnWriteArrayList()
        val unbound: MutableList<ServiceConnection> = CopyOnWriteArrayList()
        val name = ComponentName("app.venkat.offkit", EngineService::class.java.name)
        val conn = BoundServiceConnector(
            bind = { c -> connections += c; c.onServiceConnected(name, server(fake)); true },
            unbind = { unbound += it },
        )
        val engine = RemoteEngine(conn)
        val (_, input, out) = jobDir()
        Thread {
            started.await(5, TimeUnit.SECONDS)
            connections.last().onServiceDisconnected(name)
        }.start()
        assertEquals(PdfResult.Err(PdfError.EngineCrashed), engine.merge(listOf(input), out, JobControl.None))
        assertEquals(listOf(connections[0]), unbound.toList())
        release.countDown()
        fake.beforeStep = null
        assertTrue(engine.merge(listOf(input), out, JobControl.None) is PdfResult.Ok)
        assertEquals(2, connections.size)
        // A late callback of the old binding does not break the new session.
        connections[0].onServiceDisconnected(name)
        assertTrue(engine.merge(listOf(input), out, JobControl.None) is PdfResult.Ok)
        assertEquals(2, connections.size)
    }

    // ------------------------------------------------ reader

    private val tile = RenderRequest(page = 2, scale = 2f, left = 100, top = 200, width = 64, height = 32)

    private fun isIo(r: PdfRender) = assertTrue("$r", (r as PdfRender.Err).error is PdfError.Io)

    @Test
    fun pageInfoAndRenderRoundTrip() {
        val fake = FakePdfEngine().apply { readerPages = listOf(PageDims(612f, 792f), PageDims(792f, 612f), PageDims(595f, 842f)) }
        val conn = FakeConnector { server(fake) }
        val engine = RemoteEngine(conn)
        val (_, input, _) = jobDir()
        val out = File(input.parentFile!!.parentFile, "tile.rgba")
        assertEquals(PdfPageInfo.Ok(fake.readerPages), engine.pageInfo(input))
        assertEquals(PdfRender.Ok(64, 32), engine.renderPage(input, tile, out))
        assertEquals(64L * 32 * 4, out.length())
        assertEquals(listOf(null, tile), fake.readerCalls.map { it.request })
        fake.readerError = PdfError.Encrypted
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), engine.pageInfo(input))
        assertEquals(PdfRender.Err(PdfError.Encrypted), engine.renderPage(input, tile, out))
        assertFalse(out.exists())
        assertEquals(1, conn.connects.get())
    }

    @Test
    fun invalidRenderRequestIsRefusedWithoutContactingTheEngine() {
        val conn = FakeConnector { error("must not connect") }
        val out = File(root, "j/tile.rgba").apply { parentFile!!.mkdirs(); writeText("stale") }
        val r = RemoteEngine(conn).renderPage(File(root, "j/in.pdf"), tile.copy(scale = Float.NaN), out)
        assertTrue(((r as PdfRender.Err).error as PdfError.Io).cause is IllegalArgumentException)
        assertFalse(out.exists())
        assertEquals(0, conn.connects.get())
    }

    /** Renderer that writes [bytes] bytes and claims `Ok(width + dw, height + dh)`. */
    private fun rogue(bytes: (RenderRequest) -> Long, dw: Int = 0, dh: Int = 0) = object : PdfPageRenderer {
        override fun pageInfo(pdf: File, job: JobControl) = PdfPageInfo.Ok(listOf(PageDims(1f, 1f)))
        override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
            out.writeBytes(ByteArray(bytes(request).toInt()))
            return PdfRender.Ok(request.width + dw, request.height + dh)
        }
    }

    @Test
    fun renderOkWithWrongLengthOrDimensionsIsRejectedAndDeleted() {
        val (_, input, _) = jobDir()
        val out = File(input.parentFile!!.parentFile, "tile.rgba")
        val exact = { r: RenderRequest -> r.width.toLong() * r.height * 4 }
        val cases = listOf(
            rogue({ exact(it) - 1 }),
            rogue({ exact(it) + 1 }),
            rogue({ 0 }),
            rogue(exact, dw = 1),
            rogue(exact, dh = -1),
            // Claims a smaller tile and writes a file that matches the claim, not the request.
            rogue({ (it.width - 1).toLong() * it.height * 4 }, dw = -1),
        )
        for (renderer in cases) {
            val engine = RemoteEngine(FakeConnector { EngineServer({ FakePdfEngine() }, root, myPid = { 4242 }, renderer = { renderer }) })
            isIo(engine.renderPage(input, tile, out))
            assertFalse(out.exists())
        }
        val honest = RemoteEngine(FakeConnector { EngineServer({ FakePdfEngine() }, root, myPid = { 4242 }, renderer = { rogue(exact) }) })
        assertEquals(PdfRender.Ok(64, 32), honest.renderPage(input, tile, out))
    }

    @Test
    fun pageInfoReplyWithBadSizesIsIo() {
        val (_, input, _) = jobDir()
        for (pages in listOf(listOf(PageDims(Float.NaN, 1f)), listOf(PageDims(1f, 0f)), listOf(PageDims(1f, Float.POSITIVE_INFINITY)))) {
            val r = object : PdfPageRenderer {
                override fun pageInfo(pdf: File, job: JobControl) = PdfPageInfo.Ok(pages)
                override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl) = error("unused")
            }
            val engine = RemoteEngine(FakeConnector { EngineServer({ FakePdfEngine() }, root, renderer = { r }) })
            val info = engine.pageInfo(input)
            assertTrue("$info", (info as PdfPageInfo.Err).error is PdfError.Io)
        }
    }

    @Test
    fun renderTotalLimitKillsTheEngineReturnsTimedOutAndDeletesOut() {
        val conn = firstThenWorking(hangingService())
        // A short stall limit must not apply to reader requests: only renderTotalMs does.
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 100, totalMs = 60_000, imageTotalMs = 60_000, renderTotalMs = 500))
        val (_, input, _) = jobDir()
        val out = File(input.parentFile!!.parentFile, "tile.rgba").apply { writeText("partial") }
        val t0 = System.nanoTime()
        val r = engine.renderPage(input, tile, out)
        val ms = elapsedMs(t0)
        assertEquals(PdfRender.Err(PdfError.TimedOut), r)
        assertTrue("took $ms ms", ms in 500..2_500)
        assertEquals(listOf(4242), conn.killed)
        assertFalse(out.exists())
        assertEquals(PdfRender.Ok(64, 32), engine.renderPage(input, tile, out)) // a fresh engine
        assertEquals(2, conn.connects.get())
    }

    @Test
    fun infoTotalLimitKillsTheEngineAndReturnsTimedOut() {
        val conn = FakeConnector { hangingService() }
        val engine = RemoteEngine(conn, limits = EngineLimits(stallMs = 100, renderTotalMs = 60_000, infoTotalMs = 400))
        val t0 = System.nanoTime()
        assertEquals(PdfPageInfo.Err(PdfError.TimedOut), engine.pageInfo(File(root, "x.pdf")))
        val ms = elapsedMs(t0)
        assertTrue("took $ms ms", ms in 400..2_500)
        assertEquals(listOf(4242), conn.killed)
    }

    @Test
    fun deathAndCancelDuringRenderDeleteOut() {
        val fake = FakePdfEngine()
        val conn = FakeConnector { server(fake) }
        val engine = RemoteEngine(conn, cancelGraceMs = 200)
        val (_, input, _) = jobDir()
        val out = File(input.parentFile!!.parentFile, "tile.rgba")
        val dead = CountDownLatch(1) // the "dead" engine must not write after the client gave up
        fake.beforeRender = { out.writeText("partial"); conn.onLost!!.invoke(); dead.await(10, TimeUnit.SECONDS) }
        assertEquals(PdfRender.Err(PdfError.EngineCrashed), engine.renderPage(input, tile, out))
        assertFalse(out.exists())
        fake.readerError = PdfError.Cancelled // the dead request ends without writing
        dead.countDown()
        Thread.sleep(100)
        fake.readerError = null

        // A cancel the engine never answers: killed after the grace, Cancelled, out deleted.
        val release = CountDownLatch(1)
        fake.beforeRender = { out.writeText("partial"); release.await(10, TimeUnit.SECONDS) }
        val job = Job().apply { cancelled.set(false) }
        Thread { Thread.sleep(100); job.cancelled.set(true) }.start()
        assertEquals(PdfRender.Err(PdfError.Cancelled), engine.renderPage(input, tile, out, job))
        release.countDown()
        assertFalse(out.exists())
        assertEquals(listOf(4242), conn.killed)
    }
}
