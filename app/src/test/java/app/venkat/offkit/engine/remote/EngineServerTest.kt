package app.venkat.offkit.engine.remote

import android.os.Bundle
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.RenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Service-side path validation and request handling, with the fake engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun jobsRoot(): File = File(tmp.root, "cache/jobs").apply { mkdirs() }

    private fun rejected(root: File, path: String): Boolean = try {
        EngineServer.checkPaths(root, listOf(path))
        false
    } catch (e: SecurityException) {
        true
    }

    @Test
    fun checkPathsAcceptsOnlyPathsInsideJobs() {
        val root = jobsRoot()
        val job = File(root, "uuid/in").apply { mkdirs() }
        assertFalse(rejected(root, File(job, "in_000.pdf").path))
        assertFalse(rejected(root, File(root, "uuid/output.pdf").path))

        assertTrue(rejected(root, File(tmp.root, "elsewhere.pdf").path))
        assertTrue(rejected(root, root.path))
        assertTrue(rejected(root, "${root.path}/../shared_prefs/x.xml"))
        assertTrue(rejected(root, "${root.path}-evil/x.pdf"))
        assertTrue(rejected(root, "/etc/passwd"))
        assertTrue(rejected(root, "relative/in.pdf"))

        // A symlink inside jobs that points outside is resolved and rejected.
        val outside = tmp.newFile("secret.pdf")
        val link = File(job, "link.pdf")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(rejected(root, link.path))
    }

    private fun runOn(server: EngineServer, request: EngineRequest): Pair<List<Pair<Int, Int>>, Bundle> {
        val events = LinkedBlockingQueue<Any>()
        server.run(7, EngineWire.encodeRequest(request), object : IEngineCallback.Stub() {
            override fun progress(requestId: Long, done: Int, total: Int) {
                assertEquals(7L, requestId)
                events.put(done to total)
            }

            override fun result(requestId: Long, result: Bundle) {
                assertEquals(7L, requestId)
                events.put(result)
            }
        })
        val progress = mutableListOf<Pair<Int, Int>>()
        while (true) {
            when (val e = events.poll(5, TimeUnit.SECONDS) ?: fail("no result")) {
                is Bundle -> return progress to e
                else -> @Suppress("UNCHECKED_CAST") progress.add(e as Pair<Int, Int>)
            }
        }
    }

    @Test
    fun outsidePathIsRejectedWithoutCallingEngine() {
        val fake = FakePdfEngine()
        val server = EngineServer({ fake }, jobsRoot())
        val (progress, reply) = runOn(
            server,
            EngineRequest.Compress(tmp.newFile("in.pdf").path, app.venkat.offkit.engine.CompressionLevel.LOW, File(jobsRoot(), "j/out.pdf").path),
        )
        val r = EngineWire.decodeResult(reply)
        val cause = ((r as PdfResult.Err).error as PdfError.Io).cause
        assertTrue(cause.message, cause.message!!.startsWith("java.lang.SecurityException"))
        assertTrue(progress.isEmpty())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun insidePathRunsEngineWithProgressThenResult() {
        val root = jobsRoot()
        val dir = File(root, "j/in").apply { mkdirs() }
        val input = File(dir, "in_000.pdf").apply { writeText("%PDF") }
        val out = File(root, "j/output.pdf")
        val fake = FakePdfEngine()
        val server = EngineServer({ fake }, root)
        val (progress, reply) = runOn(server, EngineRequest.Merge(listOf(input.path, input.path), out.path))
        assertEquals(PdfResult.Ok(1, fake.outputContent.size.toLong()), EngineWire.decodeResult(reply))
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertTrue(out.isFile)
    }

    // ------------------------------------------------ client gone (FR-2)

    /** A server whose kill and death links are recorded instead of real. */
    private class Hooks {
        val kills = java.util.concurrent.atomic.AtomicInteger()
        val deaths: MutableList<() -> Unit> = java.util.concurrent.CopyOnWriteArrayList()
        val unlinks = java.util.concurrent.atomic.AtomicInteger()
        fun server(fake: FakePdfEngine, root: File) = EngineServer(
            { fake }, root,
            killProcess = { kills.incrementAndGet() },
            linkDeath = { _, onDeath -> deaths += onDeath; { unlinks.incrementAndGet() } },
        )
    }

    private fun blockingMerge(root: File): Triple<FakePdfEngine, java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch> {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fake = FakePdfEngine().apply {
            beforeStep = { _, step -> if (step == 2) { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        }
        return Triple(fake, started, release)
    }

    private val ignore = object : IEngineCallback.Stub() {
        override fun progress(requestId: Long, done: Int, total: Int) {}
        override fun result(requestId: Long, result: Bundle?) {}
    }

    @Test
    fun clientDeathMidRequestDeletesOutAndKillsTheProcess() {
        val root = jobsRoot()
        val input = File(root, "j/in").apply { mkdirs() }.let { File(it, "in_000.pdf").apply { writeText("%PDF") } }
        val out = File(root, "j/output.pdf")
        val (fake, started, release) = blockingMerge(root)
        val hooks = Hooks()
        val server = hooks.server(fake, root)
        server.run(1, EngineWire.encodeRequest(EngineRequest.Merge(listOf(input.path), out.path)), ignore)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(server.busy)
        out.writeText("partial")
        hooks.deaths.single().invoke() // the client's callback binder died
        assertEquals(1, hooks.kills.get())
        assertFalse(out.exists())
        release.countDown()
    }

    @Test
    fun unbindWhileBusyKillsButIdleUnbindDoesNot() {
        val root = jobsRoot()
        val input = File(root, "j/in").apply { mkdirs() }.let { File(it, "in_000.pdf").apply { writeText("%PDF") } }
        val out = File(root, "j/output.pdf")
        val hooks = Hooks()
        // Idle: a finished request is forgotten (and unlinked) before its result is sent.
        val idle = hooks.server(FakePdfEngine(), root)
        val (_, reply) = runOn(idle, EngineRequest.Merge(listOf(input.path), out.path))
        assertTrue(EngineWire.decodeResult(reply) is PdfResult.Ok)
        assertFalse(idle.busy)
        assertEquals(1, hooks.unlinks.get())
        idle.onUnbound()
        assertEquals(0, hooks.kills.get())
        assertTrue(out.isFile) // a good output is never deleted

        // Busy (one running, one queued): unbind deletes both outputs and kills.
        val (fake, started, release) = blockingMerge(root)
        val busy = hooks.server(fake, root)
        val out2 = File(root, "j/output2.pdf")
        busy.run(1, EngineWire.encodeRequest(EngineRequest.Merge(listOf(input.path), out.path)), ignore)
        busy.run(2, EngineWire.encodeRequest(EngineRequest.Merge(listOf(input.path), out2.path)), ignore)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        out2.writeText("never started")
        busy.onUnbound()
        assertEquals(1, hooks.kills.get())
        assertFalse(out.exists())
        assertFalse(out2.exists())
        release.countDown()
    }

    @Test
    fun everyBindInAProcessGetsTheSameServer() {
        val a = org.robolectric.Robolectric.buildService(EngineService::class.java).create().get()
        val first = a.onBind(android.content.Intent())
        a.onUnbind(android.content.Intent()) // idle: no kill
        a.onDestroy()
        val b = org.robolectric.Robolectric.buildService(EngineService::class.java).create().get()
        assertTrue(first === b.onBind(android.content.Intent()))
        assertTrue(first is EngineServer)
    }

    // ------------------------------------------------ reader

    private val tile = RenderRequest(page = 1, scale = 1f, left = 10, top = 20, width = 30, height = 40)

    private fun ioMessage(e: PdfError): String = (e as PdfError.Io).cause.message!!

    @Test
    fun readerRequestsWithAPathOutsideJobsAreRejectedAndNothingOutsideIsTouched() {
        val root = jobsRoot()
        val fake = FakePdfEngine()
        val server = EngineServer({ fake }, root)
        val outsidePdf = tmp.newFile("in.pdf")
        val info = EngineWire.decodePageInfo(runOn(server, EngineRequest.PageInfo(outsidePdf.path)).second)
        assertTrue(ioMessage((info as PdfPageInfo.Err).error).startsWith("java.lang.SecurityException"))

        val insidePdf = File(root, "j/in.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val outsideOut = tmp.newFile("victim.bin").apply { writeText("keep me") }
        val r1 = EngineWire.decodeRender(runOn(server, EngineRequest.RenderPage(insidePdf.path, tile, outsideOut.path)).second)
        assertTrue(ioMessage((r1 as PdfRender.Err).error).startsWith("java.lang.SecurityException"))
        assertTrue(outsideOut.isFile) // an unchecked out is never deleted

        val insideOut = File(root, "j/tile.rgba")
        val r2 = EngineWire.decodeRender(runOn(server, EngineRequest.RenderPage(outsidePdf.path, tile, insideOut.path)).second)
        assertTrue(ioMessage((r2 as PdfRender.Err).error).startsWith("java.lang.SecurityException"))
        assertTrue(fake.readerCalls.isEmpty())
    }

    @Test
    fun pageInfoAndRenderRunOnTheEngineItself() {
        val root = jobsRoot()
        val pdf = File(root, "j/in.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val out = File(root, "j/tile.rgba")
        val fake = FakePdfEngine()
        val server = EngineServer({ fake }, root) // no renderer given: the engine instance renders
        assertEquals(PdfPageInfo.Ok(fake.readerPages), EngineWire.decodePageInfo(runOn(server, EngineRequest.PageInfo(pdf.path)).second))
        val (progress, reply) = runOn(server, EngineRequest.RenderPage(pdf.path, tile, out.path))
        assertEquals(PdfRender.Ok(30, 40), EngineWire.decodeRender(reply))
        assertTrue(progress.isEmpty())
        val bytes = out.readBytes()
        assertEquals(30L * 40 * 4, bytes.size.toLong())
        val c = fake.readerColor(1)
        assertEquals(listOf((c shr 16).toByte(), (c shr 8).toByte(), c.toByte(), 0xFF.toByte()), bytes.take(4))
        assertEquals(tile, fake.readerCalls.last().request)
    }

    @Test
    fun failedRenderDeletesOutAndRepliesWithTheRenderType() {
        val root = jobsRoot()
        val pdf = File(root, "j/in.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val out = File(root, "j/tile.rgba")

        // The renderer returns an Err but leaves a file behind.
        val sloppy = object : PdfPageRenderer {
            override fun pageInfo(pdf: File, job: JobControl) = PdfPageInfo.Err(PdfError.Encrypted)
            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                out.writeText("partial")
                return PdfRender.Err(PdfError.Corrupt)
            }
        }
        val s1 = EngineServer({ FakePdfEngine() }, root, renderer = { sloppy })
        assertEquals(PdfRender.Err(PdfError.Corrupt), EngineWire.decodeRender(runOn(s1, EngineRequest.RenderPage(pdf.path, tile, out.path)).second))
        assertFalse(out.exists())
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), EngineWire.decodePageInfo(runOn(s1, EngineRequest.PageInfo(pdf.path)).second))

        // The renderer throws after writing.
        val throwing = object : PdfPageRenderer {
            override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo = throw OutOfMemoryError()
            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                out.writeText("partial")
                throw IllegalStateException("boom")
            }
        }
        val s2 = EngineServer({ FakePdfEngine() }, root, renderer = { throwing })
        val r = EngineWire.decodeRender(runOn(s2, EngineRequest.RenderPage(pdf.path, tile, out.path)).second)
        assertEquals("java.lang.IllegalStateException: boom", ioMessage((r as PdfRender.Err).error))
        assertFalse(out.exists())
        assertEquals(PdfPageInfo.Err(PdfError.OutOfMemory), EngineWire.decodePageInfo(runOn(s2, EngineRequest.PageInfo(pdf.path)).second))

        // A page the document does not have (the fake honours RenderLimits with its dims).
        val fake = FakePdfEngine()
        val s3 = EngineServer({ fake }, root)
        out.writeText("stale")
        val past = EngineWire.decodeRender(runOn(s3, EngineRequest.RenderPage(pdf.path, tile.copy(left = 590), out.path)).second)
        assertTrue(ioMessage((past as PdfRender.Err).error).startsWith("java.lang.IllegalArgumentException"))
        assertFalse(out.exists())
    }

    @Test
    fun overLimitRenderRequestIsRejectedBeforeAnythingRuns() {
        val root = jobsRoot()
        val pdf = File(root, "j/in.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val out = File(root, "j/tile.rgba")
        val fake = FakePdfEngine()
        val server = EngineServer({ fake }, root)
        val bundle = EngineWire.encodeRequest(EngineRequest.RenderPage(pdf.path, tile, out.path)).apply { putInt("width", 300_000) }
        val events = LinkedBlockingQueue<Bundle>()
        server.run(3, bundle, object : IEngineCallback.Stub() {
            override fun progress(requestId: Long, done: Int, total: Int) {}
            override fun result(requestId: Long, result: Bundle) { events.put(result) }
        })
        val r = EngineWire.decodeRender(events.poll(5, TimeUnit.SECONDS)!!)
        assertTrue(ioMessage((r as PdfRender.Err).error).startsWith("java.lang.IllegalArgumentException"))
        assertTrue(fake.readerCalls.isEmpty())
    }

    @Test
    fun clientDeathMidRenderDeletesOutAndKillsTheProcess() {
        val root = jobsRoot()
        val pdf = File(root, "j/in.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }
        val out = File(root, "j/tile.rgba")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fake = FakePdfEngine().apply { beforeRender = { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val hooks = Hooks()
        val server = hooks.server(fake, root)
        server.run(1, EngineWire.encodeRequest(EngineRequest.RenderPage(pdf.path, tile, out.path)), ignore)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        out.writeText("partial")
        hooks.deaths.single().invoke()
        assertEquals(1, hooks.kills.get())
        assertFalse(out.exists())
        release.countDown()
    }
}
