package app.venkat.offkit.engine.remote

import android.os.Bundle
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
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
}
