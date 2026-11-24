package app.venkat.offkit.engine.remote

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
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
}
