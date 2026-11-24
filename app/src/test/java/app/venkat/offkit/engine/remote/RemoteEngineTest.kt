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

    // ------------------------------------------------ BoundServiceConnector (real connector logic)

    @Test
    fun connectTimeoutReturnsEngineCrashedAndUnbinds() {
        val unbinds = AtomicInteger()
        val conn = BoundServiceConnector(bind = { true }, unbind = { unbinds.incrementAndGet() }, timeoutMs = 200)
        val (_, input, out) = jobDir()
        val t0 = System.nanoTime()
        val r = RemoteEngine(conn).merge(listOf(input), out, JobControl.None)
        assertEquals(PdfResult.Err(PdfError.EngineCrashed), r)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        assertEquals(1, unbinds.get())
    }

    @Test
    fun bindFailureReturnsEngineCrashed() {
        val conn = BoundServiceConnector(bind = { false }, unbind = {}, timeoutMs = 200)
        assertEquals(PdfEstimate.Err(PdfError.EngineCrashed), RemoteEngine(conn).estimateImage(File(root, "x")))
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
