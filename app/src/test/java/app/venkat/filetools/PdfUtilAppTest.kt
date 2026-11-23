package app.venkat.filetools

import androidx.test.core.app.ApplicationProvider
import app.venkat.filetools.engine.CompressionLevel
import app.venkat.filetools.engine.JobControl
import app.venkat.filetools.engine.PageSize
import app.venkat.filetools.engine.PdfError
import app.venkat.filetools.engine.PdfResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Robolectric: must never read the default engine (that would load MuPDF). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfUtilAppTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingJob : JobControl {
        val steps = mutableListOf<Pair<Int, Int>>()
        val cancelled = AtomicBoolean(false)
        override fun progress(done: Int, total: Int) {
            synchronized(steps) { steps += done to total }
        }
        override fun isCancelled() = cancelled.get()
    }

    private fun app(): PdfUtilApp = ApplicationProvider.getApplicationContext()

    @Test
    fun applicationIsPdfUtilAppAndEngineIsReplaceable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(context is PdfUtilApp)
        val app = context as PdfUtilApp
        val fake = FakePdfEngine()
        app.engine = fake
        assertSame(fake, app.engine)
    }

    @Test
    fun fakeRecordsCallsWritesOutputAndReportsProgress() {
        val fake = FakePdfEngine()
        app().engine = fake
        val engine = app().engine
        val img = tmp.newFile("a.jpg")
        val pdf = tmp.newFile("a.pdf")

        fun check(out: File, job: RecordingJob, result: PdfResult) {
            assertEquals(PdfResult.Ok(1, fake.outputContent.size.toLong()), result)
            assertArrayEquals(fake.outputContent, out.readBytes())
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), job.steps)
        }

        val o1 = tmp.root.resolve("o1.pdf"); val j1 = RecordingJob()
        check(o1, j1, engine.imagesToPdf(listOf(img), PageSize.LETTER, o1, j1))
        val o2 = tmp.root.resolve("o2.pdf"); val j2 = RecordingJob()
        check(o2, j2, engine.compress(pdf, CompressionLevel.HIGH, o2, j2))
        val o3 = tmp.root.resolve("o3.pdf"); val j3 = RecordingJob()
        check(o3, j3, engine.merge(listOf(pdf, pdf), o3, j3))
        val o4 = tmp.root.resolve("o4.pdf"); val j4 = RecordingJob()
        check(o4, j4, engine.appendImages(pdf, listOf(img), o4, j4))

        assertEquals(
            listOf(
                FakePdfEngine.Op.IMAGES_TO_PDF,
                FakePdfEngine.Op.COMPRESS,
                FakePdfEngine.Op.MERGE,
                FakePdfEngine.Op.APPEND_IMAGES,
            ),
            fake.calls.map { it.op },
        )
        assertEquals(PageSize.LETTER, fake.calls[0].pageSize)
        assertEquals(listOf(img), fake.calls[0].images)
        assertEquals(CompressionLevel.HIGH, fake.calls[1].level)
        assertEquals(pdf, fake.calls[1].pdf)
        assertEquals(listOf(pdf, pdf), fake.calls[2].pdfs)
        assertEquals(listOf(pdf, img), fake.calls[3].inputs)
        assertEquals(o4, fake.calls[3].out)
    }

    @Test
    fun scriptedErrorIsReturnedAndOutputRemoved() {
        val fake = FakePdfEngine()
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))
        val out = tmp.newFile("out.pdf")
        assertEquals(PdfResult.Err(PdfError.Encrypted), fake.merge(listOf(out), out))
        assertFalse(out.exists())
    }

    @Test
    fun cancellationMidRunReturnsCancelled() {
        val fake = FakePdfEngine()
        app().engine = fake
        val reachedStep2 = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 2) {
                reachedStep2.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val job = RecordingJob()
        val out = tmp.root.resolve("cancel.pdf")
        val input = tmp.newFile("in.pdf")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<PdfResult> {
                app().engine.compress(input, CompressionLevel.LOW, out, job)
            }
            assertTrue(reachedStep2.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1 to 3), synchronized(job.steps) { job.steps.toList() })
            job.cancelled.set(true)
            release.countDown()
            assertEquals(PdfResult.Err(PdfError.Cancelled), future.get(5, TimeUnit.SECONDS))
            assertFalse(out.exists())
        } finally {
            executor.shutdownNow()
        }
    }
}
