package app.venkat.offkit.engine.remote

import android.os.Bundle
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.core.ImageNormalizer
import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.InProcessImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The image-normalisation request of the engine protocol (PI2): the UI process sends the path of
 * the copied raw bytes, the engine process decodes/normalises and replies with the file name.
 * In-JVM server, no Android service, MuPDF never loaded (the engine factory throws).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageNormalizeProtocolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File by lazy { File(tmp.root, "cache/jobs").apply { mkdirs() } }
    private val noEngine: () -> PdfEngine = { throw AssertionError("normalising must not create the PDF engine") }

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun inDir(): File = File(root, "j${System.nanoTime()}/in").apply { mkdirs() }

    private fun raw(dir: File, index: Int, bytes: ByteArray): File =
        ImageNormalizer.rawFile(dir, index).apply { writeBytes(bytes) }

    private fun runOn(server: EngineServer, request: EngineRequest): Bundle {
        val q = LinkedBlockingQueue<Bundle>()
        server.run(9, EngineWire.encodeRequest(request), object : IEngineCallback.Stub() {
            override fun progress(requestId: Long, done: Int, total: Int) = Unit
            override fun result(requestId: Long, result: Bundle) {
                q.put(result)
            }
        })
        return q.poll(5, TimeUnit.SECONDS) ?: fail("no result") as Nothing
    }

    private class RecordingPreparer(val delegate: ImagePreparer = InProcessImagePreparer) : ImagePreparer {
        val calls: MutableList<File> = CopyOnWriteArrayList()
        override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage {
            calls += raw
            return delegate.prepareImage(raw, destDir, index, job)
        }
    }

    @Test
    fun wireRoundTripAndBadIndexRejected() {
        val r = EngineRequest.NormalizeImage("/a/img_001.raw", "/a", 1)
        assertEquals(r, EngineWire.decodeRequest(EngineWire.encodeRequest(r)))
        for (bad in listOf(-1, MAX_IMAGE_INDEX + 1)) {
            val b = EngineWire.encodeRequest(r.copy(index = bad))
            try {
                EngineWire.decodeRequest(b)
                fail("index $bad accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun serverNormalisesInsideJobsAndRepliesWithNameAndPid() {
        val dir = inDir()
        val raw = raw(dir, 3, pngMagic + ByteArray(32))
        val server = EngineServer(noEngine, root, myPid = { 4242 })
        val reply = runOn(server, EngineRequest.NormalizeImage(raw.path, dir.path, 3))
        assertEquals(PreparedImage.Ok(File(dir, "img_003.png")), EngineWire.decodePrepared(reply, dir, 3))
        assertEquals(4242, EngineWire.preparedPid(reply))
        assertTrue(File(dir, "img_003.png").isFile)
        assertFalse(raw.exists())
    }

    @Test
    fun undecodableContentIsUnsupportedImageAndRawDeleted() {
        val dir = inDir()
        val raw = raw(dir, 0, "definitely not an image".toByteArray())
        val reply = runOn(EngineServer(noEngine, root), EngineRequest.NormalizeImage(raw.path, dir.path, 0))
        assertEquals(PreparedImage.Err(PdfError.UnsupportedImage), EngineWire.decodePrepared(reply, dir, 0))
        assertFalse(raw.exists())
        assertTrue("left: ${dir.list()?.toList()}", dir.list().isNullOrEmpty())
    }

    @Test
    fun outOfMemoryInTheDecoderIsOutOfMemory() {
        val dir = inDir()
        val raw = raw(dir, 0, ByteArray(16))
        val oom = object : ImagePreparer {
            override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage =
                throw OutOfMemoryError("decoder")
        }
        val reply = runOn(EngineServer(noEngine, root, preparer = oom), EngineRequest.NormalizeImage(raw.path, dir.path, 0))
        assertEquals(PreparedImage.Err(PdfError.OutOfMemory), EngineWire.decodePrepared(reply, dir, 0))
    }

    @Test
    fun pathsOutsideJobsAreRejectedWithoutDecoding() {
        val dir = inDir()
        val preparer = RecordingPreparer()
        val server = EngineServer(noEngine, root, preparer = preparer)
        val outsideRaw = tmp.newFile("img_000.raw").apply { writeBytes(pngMagic) }
        val outsideDir = tmp.newFolder("elsewhere")
        for (request in listOf(
            EngineRequest.NormalizeImage(outsideRaw.path, dir.path, 0),
            EngineRequest.NormalizeImage(raw(dir, 0, pngMagic).path, outsideDir.path, 0),
            EngineRequest.NormalizeImage(raw(dir, 0, pngMagic).path, "${root.path}/../../x", 0),
        )) {
            val r = EngineWire.decodePrepared(runOn(server, request), File(request.destDir), 0)
            val cause = ((r as PreparedImage.Err).error as PdfError.Io).cause
            assertTrue(cause.message, cause.message!!.startsWith("java.lang.SecurityException"))
        }
        assertTrue(preparer.calls.isEmpty())
        assertTrue(outsideRaw.exists())
        assertTrue(outsideDir.list().isNullOrEmpty())
    }

    @Test
    fun clientRejectsAReplyNamingAnotherFile() {
        val dir = inDir()
        val rogue = object : ImagePreparer {
            override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage =
                PreparedImage.Ok(File(destDir, "in_000.pdf"))
        }
        val engine = RemoteEngine(Connector { EngineServer(noEngine, root, preparer = rogue) })
        val r = engine.prepareImage(raw(dir, 0, pngMagic), dir, 0)
        assertTrue("$r", (r as PreparedImage.Err).error is PdfError.Io)
    }

    @Test
    fun remoteEngineRoundTripRecordsEnginePid() {
        val dir = inDir()
        val raw = raw(dir, 1, pngMagic)
        val engine = RemoteEngine(Connector { EngineServer(noEngine, root, myPid = { 777 }) })
        assertEquals(PreparedImage.Ok(File(dir, "img_001.png")), engine.prepareImage(raw, dir, 1))
        assertEquals(777, engine.lastImagePreparedByPid)
        assertFalse(raw.exists())
    }

    @Test
    fun engineDeathDuringNormalisationIsEngineCrashed() {
        val dir = inDir()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : ImagePreparer {
            override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                return InProcessImagePreparer.prepareImage(raw, destDir, index, job)
            }
        }
        val conn = Connector { EngineServer(noEngine, root, preparer = blocking) }
        val engine = RemoteEngine(conn)
        val raw = raw(dir, 0, pngMagic)
        Thread {
            started.await(5, TimeUnit.SECONDS)
            conn.onLost?.invoke() // the engine process dies mid-decode
        }.start()
        val t0 = System.nanoTime()
        val r = engine.prepareImage(raw, dir, 0)
        release.countDown()
        assertEquals(PreparedImage.Err(PdfError.EngineCrashed), r)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3_000)
        assertFalse("raw left behind", raw.exists())
        // The next call binds a fresh engine and works.
        assertTrue(engine.prepareImage(raw(dir, 1, pngMagic), dir, 1) is PreparedImage.Ok)
        assertEquals(2, conn.connects)
    }

    @Test
    fun fakeEngineNormalisesInProcessForRobolectricTests() {
        val dir = inDir()
        val fake = FakePdfEngine()
        assertEquals(PreparedImage.Ok(File(dir, "img_002.png")), fake.prepareImage(raw(dir, 2, pngMagic), dir, 2))
        fake.prepare = { _, _, _ -> PreparedImage.Err(PdfError.EngineCrashed) }
        val raw = raw(dir, 3, pngMagic)
        assertEquals(PreparedImage.Err(PdfError.EngineCrashed), fake.prepareImage(raw, dir, 3))
        assertFalse(raw.exists())
    }

    private class Connector(val service: () -> IEngineService) : EngineConnector {
        @Volatile var onLost: (() -> Unit)? = null
        @Volatile var connects = 0
        override fun connect(onLost: () -> Unit): IEngineService {
            connects++
            this.onLost = onLost
            return service()
        }
        override fun disconnect() = Unit
        override fun kill(pid: Int) {
            onLost?.invoke()
        }
    }
}
