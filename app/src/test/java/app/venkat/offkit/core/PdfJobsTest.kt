package app.venkat.offkit.core

import org.junit.Assert.assertThrows
import androidx.core.content.FileProvider
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfJobsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fake: FakePdfEngine
    private lateinit var engineDispatcher: ExecutorCoroutineDispatcher
    private lateinit var jobs: PdfJobs

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CoreTestSupport.clearFileProviderCache()
        fake = FakePdfEngine()
        (context as PdfUtilApp).engine = fake
        engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        // Unconfined "main" so the test thread can block in runBlocking without deadlocking.
        jobs = PdfJobs(context, engineDispatcher, Dispatchers.IO, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        engineDispatcher.close()
        PdfJobs.resetForTesting()
    }

    private fun mergeJob(inputs: List<JobInput>, onProgress: (Int, Int) -> Unit = { _, _ -> }) = runBlocking {
        jobs.run(inputs, onProgress) { engine, files, out, job -> engine.merge(files, out, job) }
    }

    @Test
    fun inputsReachEngineInOrderWithContents() {
        val a = "%PDF-a".toByteArray()
        val b = "%PDF-b".toByteArray()
        val inputs = listOf(
            JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", a)),
            JobInput.Image(CoreTestSupport.contentUri(context, "p.jpg", jpeg)),
            JobInput.Pdf(CoreTestSupport.contentUri(context, "b.pdf", b)),
        )
        val seen = mutableListOf<ByteArray>()
        val outcome = runBlocking {
            jobs.run(inputs, { _, _ -> }) { engine, files, out, job ->
                files.forEach { seen += it.readBytes() }
                engine.merge(files, out, job)
            }
        }
        assertTrue(outcome is JobOutcome.Ready)
        assertEquals(3, seen.size)
        assertArrayEquals(a, seen[0])
        assertArrayEquals(jpeg, seen[1])
        assertArrayEquals(b, seen[2])
        val pdfs = fake.callsFor(FakePdfEngine.Op.MERGE).single().pdfs!!
        assertEquals(listOf("in_000.pdf", "img_001.jpg", "in_002.pdf"), pdfs.map { it.name })
        assertTrue(pdfs.all { it.canonicalPath.startsWith(File(context.cacheDir, "jobs").canonicalPath) })
    }

    @Test
    fun progressIsForwardedAndOkLeavesOnlyOutput() {
        val progress = CopyOnWriteArrayList<Pair<Int, Int>>()
        val outcome = mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", "x".toByteArray())))) { d, t ->
            progress += d to t
        }
        assertTrue(progress.isNotEmpty())
        assertEquals(3 to 3, progress.last())
        assertEquals(progress.sortedBy { it.first }, progress.toList())

        val output = (outcome as JobOutcome.Ready).output
        assertEquals(1, output.pages)
        assertEquals(fake.outputContent.size.toLong(), output.bytes)
        assertEquals(listOf(output.file.canonicalFile), CoreTestSupport.jobFiles(context).map { it.canonicalFile })
        assertArrayEquals(fake.outputContent, output.file.readBytes())
    }

    @Test
    fun saveWritesDestinationAndKeepsOutput() {
        val output = (mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))) as JobOutcome.Ready).output
        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/out.pdf")
        shadowOf(context.contentResolver).registerOutputStream(dest, sink)
        assertTrue(runBlocking { jobs.save(output, dest) }.isSuccess)
        assertArrayEquals(fake.outputContent, sink.toByteArray())
        // The output survives a successful save (the caller discards it).
        assertTrue(output.file.isFile)
    }

    @Test
    fun failedSaveKeepsOutputForRetry() {
        val output = (mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))) as JobOutcome.Ready).output
        val broken = Uri.parse("content://dest.test/broken.pdf")
        shadowOf(context.contentResolver).registerOutputStream(
            broken,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
            },
        )
        assertTrue(runBlocking { jobs.save(output, broken) }.isFailure)
        assertTrue(output.file.isFile)

        val sink = ByteArrayOutputStream()
        val good = Uri.parse("content://dest.test/good.pdf")
        shadowOf(context.contentResolver).registerOutputStream(good, sink)
        assertTrue(runBlocking { jobs.save(output, good) }.isSuccess)
        assertArrayEquals(fake.outputContent, sink.toByteArray())
        // The output survives a successful save (the caller discards it).
        assertTrue(output.file.isFile)
    }

    /** A PdfJobs whose destination streams come from [opener] (to inject write/close failures on real files). */
    private fun jobsWithOpener(opener: (Uri) -> OutputStream?) =
        PdfJobs(context, engineDispatcher, Dispatchers.IO, Dispatchers.Unconfined) { _, uri -> opener(uri) }

    private fun readyOutput(j: PdfJobs) = runBlocking {
        j.run(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3)))), { _, _ -> }) { engine, files, out, job ->
            engine.merge(files, out, job)
        }
    }.let { (it as JobOutcome.Ready).output }

    @Test
    fun failureMidWriteDeletesDestinationKeepsOutputAndRetryWorks() {
        val broken = tmp.newFile("broken.pdf")
        val good = tmp.root.resolve("good.pdf")
        val j = jobsWithOpener { uri ->
            val f = File(uri.path!!)
            if (f == broken) {
                val real = java.io.FileOutputStream(f)
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk full")
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        real.write(b, off, maxOf(1, len / 2)) // part of the data reaches the file
                        throw IOException("disk full")
                    }
                    override fun close() = real.close()
                }
            } else {
                java.io.FileOutputStream(f)
            }
        }
        val output = readyOutput(j)
        val result = runBlocking { j.save(output, Uri.fromFile(broken)) }
        assertTrue(result.isFailure)
        assertFalse("truncated destination must be deleted", broken.exists())
        assertTrue(output.file.isFile)

        assertTrue(runBlocking { j.save(output, Uri.fromFile(good)) }.isSuccess)
        assertArrayEquals(fake.outputContent, good.readBytes())
        assertTrue(output.file.isFile)
    }

    @Test
    fun closeFailureCountsAsFailedSave() {
        val dest = tmp.newFile("close.pdf")
        val j = jobsWithOpener { uri ->
            object : java.io.FileOutputStream(File(uri.path!!)) {
                override fun close() {
                    super.close()
                    throw IOException("close failed")
                }
            }
        }
        val output = readyOutput(j)
        assertTrue(runBlocking { j.save(output, Uri.fromFile(dest)) }.isFailure)
        assertFalse(dest.exists())
        assertTrue(output.file.isFile)
    }

    @Test
    fun cannotOpenDestinationIsFailure() {
        val dest = tmp.newFile("null.pdf")
        val j = jobsWithOpener { null }
        val output = readyOutput(j)
        assertTrue(runBlocking { j.save(output, Uri.fromFile(dest)) }.isFailure)
        assertFalse(dest.exists())
        assertTrue(output.file.isFile)
    }

    @Test
    fun failureBeforeOpeningKeepsNonEmptyExistingDestination() {
        val existing = tmp.newFile("existing.pdf").apply { writeBytes(ByteArray(7)) }
        val j = jobsWithOpener { null }
        val output = readyOutput(j)
        assertTrue(runBlocking { j.save(output, Uri.fromFile(existing)) }.isFailure)
        assertEquals(7L, existing.length())

        // Output file gone before the save: nothing was written, the existing document stays.
        output.file.delete()
        assertTrue(runBlocking { jobs.save(output, Uri.fromFile(existing)) }.isFailure)
        assertEquals(7L, existing.length())
    }

    @Test
    fun deleteDestinationNeverThrows() {
        jobs.deleteDestination(Uri.parse("content://dest.test/whatever.pdf"))
        jobs.deleteDestination(Uri.fromFile(tmp.root.resolve("missing.pdf")))
        val f = tmp.newFile("x.pdf")
        jobs.deleteDestination(Uri.fromFile(f))
        assertFalse(f.exists())
    }

    @Test
    fun discardEmptiesCache() {
        val output = (mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))) as JobOutcome.Ready).output
        jobs.discard(output)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun engineErrorIsReturnedAndCacheEmptied() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))
        val outcome = mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3)))))
        assertEquals(JobOutcome.Failed(PdfError.Encrypted), outcome)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun engineCancelledWhileCallerActiveIsFailedCancelled() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Cancelled))
        val outcome = mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3)))))
        assertEquals(JobOutcome.Failed(PdfError.Cancelled), outcome)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun cancellingMidRunStopsEngineCleansUpAndRethrows() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 2) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val input = JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3)))
        runBlocking {
            val deferred = CoroutineScope(Dispatchers.Default).async {
                jobs.run(listOf(input), { _, _ -> }) { engine, files, out, job -> engine.merge(files, out, job) }
            }
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                assertFalse(CoreTestSupport.jobsEmpty(context))
                deferred.cancel()
            } finally {
                release.countDown()
            }
            try {
                deferred.await()
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // expected: structured concurrency is honoured
            }
        }
        assertTrue(fake.calls.single().job.isCancelled())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun unreadableInputIsIoAndCacheEmptied() {
        val missing = Uri.fromFile(tmp.root.resolve("missing.pdf"))
        val outcome = mergeJob(
            listOf(
                JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))),
                JobInput.Pdf(missing),
            ),
        )
        assertTrue(outcome is JobOutcome.Failed && outcome.error is PdfError.Io)
        assertTrue(fake.calls.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun undecodableImageIsUnsupportedImage() {
        val junk = CoreTestSupport.contentUri(context, "x.heic", ByteArray(512) { 0x42 })
        val outcome = runBlocking {
            jobs.run(listOf(JobInput.Image(junk)), { _, _ -> }) { engine, files, out, job ->
                engine.imagesToPdf(files, app.venkat.offkit.engine.PageSize.A4, out, job)
            }
        }
        assertEquals(JobOutcome.Failed(PdfError.UnsupportedImage), outcome)
        assertTrue(fake.calls.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    /** PI2: image normalisation runs in the engine process; its death or OOM is the job's error. */
    @Test
    fun imagePreparationErrorsFailTheJobAndEmptyTheCache() {
        val img = CoreTestSupport.contentUri(context, "x.webp", ByteArray(512) { 0x42 })
        for (error in listOf(PdfError.EngineCrashed, PdfError.OutOfMemory, PdfError.UnsupportedImage)) {
            fake.prepare = { _, _, _ -> app.venkat.offkit.core.PreparedImage.Err(error) }
            val outcome = runBlocking {
                jobs.run(listOf(JobInput.Image(img)), { _, _ -> }) { engine, files, out, job ->
                    engine.imagesToPdf(files, app.venkat.offkit.engine.PageSize.A4, out, job)
                }
            }
            assertEquals(JobOutcome.Failed(error), outcome)
            assertEquals(PdfEstimate.Err(error), runBlocking { jobs.estimateImage(img) })
            assertTrue(fake.calls.isEmpty())
            assertTrue(CoreTestSupport.jobsEmpty(context))
        }
        // Only the raw bytes reach the preparer.
        assertTrue(fake.prepareCalls.all { it.name.matches(Regex("img_\\d{3}\\.raw")) })
    }

    @Test
    fun exceptionsInBlockAreMapped() {
        val input = listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))
        val io = runBlocking { jobs.run(input, { _, _ -> }) { _, _, _, _ -> throw IllegalStateException("boom") } }
        assertTrue(io is JobOutcome.Failed && io.error is PdfError.Io)
        val oom = runBlocking { jobs.run(input, { _, _ -> }) { _, _, _, _ -> throw OutOfMemoryError() } }
        assertEquals(JobOutcome.Failed(PdfError.OutOfMemory), oom)
        val okWithoutFile = runBlocking { jobs.run(input, { _, _ -> }) { _, _, _, _ -> PdfResult.Ok(1, 1) } }
        assertTrue(okWithoutFile is JobOutcome.Failed && okWithoutFile.error is PdfError.Io)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun estimateImagePassesImageBytesToEngineAndCleansUp() {
        val seen = CopyOnWriteArrayList<ByteArray>()
        val expected = PdfEstimate.Ok(original = 7, byLevel = mapOf(CompressionLevel.LOW to 5L, CompressionLevel.MEDIUM to 4L, CompressionLevel.HIGH to 3L))
        fake.estimate = { file ->
            assertTrue(file.canonicalPath.startsWith(File(context.cacheDir, "jobs").canonicalPath))
            seen += file.readBytes()
            expected
        }
        val result = runBlocking { jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg)) }
        assertEquals(expected, result)
        assertEquals(1, fake.estimateCalls.size)
        assertArrayEquals(jpeg, seen.single())
        assertTrue(fake.calls.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun estimateImageRunsOnTheEngineDispatcher() {
        val threads = CopyOnWriteArrayList<Thread>()
        val engineThread = runBlocking { kotlinx.coroutines.withContext(engineDispatcher) { Thread.currentThread() } }
        fake.estimate = { threads += Thread.currentThread(); PdfEstimate.Ok(1, emptyMap()) }
        runBlocking { jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg)) }
        assertSame(engineThread, threads.single())
    }

    @Test
    fun estimateImageEngineErrorIsReturnedAndCacheEmptied() {
        fake.estimate = { PdfEstimate.Err(PdfError.OutOfMemory) }
        val result = runBlocking { jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg)) }
        assertEquals(PdfEstimate.Err(PdfError.OutOfMemory), result)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun estimateImageUnreadableOrUndecodableIsErrAndCacheEmptied() {
        val missing = Uri.fromFile(tmp.root.resolve("missing.jpg"))
        val io = runBlocking { jobs.estimateImage(missing) }
        assertTrue(io is PdfEstimate.Err && io.error is PdfError.Io)
        assertTrue(CoreTestSupport.jobsEmpty(context))

        val junk = CoreTestSupport.contentUri(context, "x.heic", ByteArray(512) { 0x42 })
        assertEquals(PdfEstimate.Err(PdfError.UnsupportedImage), runBlocking { jobs.estimateImage(junk) })
        assertTrue(fake.estimateCalls.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun estimateImageEngineExceptionIsIo() {
        fake.estimate = { throw IllegalStateException("boom") }
        val result = runBlocking { jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg)) }
        assertTrue(result is PdfEstimate.Err && result.error is PdfError.Io)
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun cancellingEstimateWhileEngineBusyCleansUpAndRethrows() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.estimate = {
            reached.countDown()
            release.await(5, TimeUnit.SECONDS)
            PdfEstimate.Ok(1, emptyMap())
        }
        runBlocking {
            val deferred = CoroutineScope(Dispatchers.Default).async {
                jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg))
            }
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                assertFalse(CoreTestSupport.jobsEmpty(context))
                deferred.cancel()
            } finally {
                release.countDown()
            }
            try {
                deferred.await()
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // expected
            }
        }
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun cancellingEstimateWhileWaitingForEngineThreadSkipsEngineAndCleansUp() {
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Occupy the engine thread so the estimate has to wait for it.
        val blocker = CoroutineScope(Dispatchers.Default).async(engineDispatcher) {
            busy.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        runBlocking {
            assertTrue(busy.await(5, TimeUnit.SECONDS))
            val deferred = CoroutineScope(Dispatchers.Default).async {
                jobs.estimateImage(CoreTestSupport.contentUri(context, "p.jpg", jpeg))
            }
            try {
                CoreTestSupport.idleUntil(message = "image copied") { !CoreTestSupport.jobsEmpty(context) }
                deferred.cancel()
            } finally {
                release.countDown()
            }
            try {
                deferred.await()
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // expected
            }
            blocker.await()
        }
        assertTrue(fake.estimateCalls.isEmpty())
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun wipeAllClearsLeftovers() {
        val leftover = File(context.cacheDir, "jobs/old-job/in").apply { mkdirs() }
        File(leftover, "x.pdf").writeBytes(ByteArray(5))
        jobs.wipeAll()
        assertTrue(CoreTestSupport.jobsEmpty(context))
    }

    @Test
    fun singletonWipesLeftoversOnCreationAndUsesOneEngineThread() {
        PdfJobs.resetForTesting()
        File(context.cacheDir, "jobs/stale").apply { mkdirs() }.resolve("y.pdf").writeBytes(ByteArray(5))
        val shared = PdfJobs.get(context)
        assertTrue(CoreTestSupport.jobsEmpty(context))
        assertSame(shared, PdfJobs.get(context))

        val threads = CopyOnWriteArrayList<Thread>()
        val input = listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))
        repeat(2) {
            // The shared instance delivers progress on Dispatchers.Main: run off the main thread
            // and pump the main looper instead of blocking it.
            val deferred = CoroutineScope(Dispatchers.Default).async {
                shared.run(input, { _, _ -> }) { engine, files, out, job ->
                    threads += Thread.currentThread()
                    engine.merge(files, out, job)
                }
            }
            CoreTestSupport.idleUntil { deferred.isCompleted }
            shared.discard((runBlocking { deferred.await() } as JobOutcome.Ready).output)
        }
        assertSame(threads[0], threads[1])
        assertEquals("pdf-engine", threads[0].name)
        assertNotSame(Thread.currentThread(), threads[0])
    }

    @Test
    fun shareProviderIsNotExportedAndGrantsUriPermissions() {
        val info = context.packageManager.getProviderInfo(ComponentName(context, FileProvider::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(PdfJobs.shareAuthority(context), info.authority)
    }

    @Test
    fun shareProviderCannotMapJobFiles() {
        val jobFile = File(jobs.jobsRoot, "x/output.pdf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, PdfJobs.shareAuthority(context), jobFile)
        }
        val other = File(context.cacheDir, "other.pdf").apply { writeBytes(ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, PdfJobs.shareAuthority(context), other)
        }
    }

    @Test
    fun prepareShareCopiesUnderSharedAndKeepsOutput() {
        val output = (mergeJob(listOf(JobInput.Pdf(CoreTestSupport.contentUri(context, "a.pdf", ByteArray(3))))) as JobOutcome.Ready).output
        val request = runBlocking { jobs.prepareShare(output, "my/report") }
        assertEquals("my_report.pdf", request.name)
        assertEquals(PdfJobs.shareAuthority(context), request.uri.authority)
        val copy = jobs.sharedRoot.walk().single { it.isFile }
        assertEquals("my_report.pdf", copy.name)
        assertEquals(jobs.sharedRoot, copy.parentFile!!.parentFile)
        assertArrayEquals(fake.outputContent, copy.readBytes())
        assertTrue(output.file.isFile)
        runBlocking { jobs.wipeShared() }
        assertTrue(jobs.sharedRoot.listFiles().isNullOrEmpty())
    }
}
