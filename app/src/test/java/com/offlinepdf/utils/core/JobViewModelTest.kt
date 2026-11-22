package com.offlinepdf.utils.core

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.offlinepdf.utils.FakePdfEngine
import com.offlinepdf.utils.PdfUtilApp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.ui.components.JobUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JobViewModelTest {
    /** Minimal feature ViewModel: merges the given PDFs. */
    class TestVm(app: Application, jobs: PdfJobs) : JobViewModel(app, jobs) {
        fun merge(uris: List<Uri>) = startJob(
            inputs = uris.map { JobInput.Pdf(it) },
            summarize = { "pages=${it.pages} bytes=${it.bytes}" },
        ) { engine, files, out, job -> engine.merge(files, out, job) }

        fun clear() = onCleared()

        override fun suggestedFileName() = "merged.pdf"
    }

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var engineDispatcher: ExecutorCoroutineDispatcher
    private lateinit var vm: TestVm

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        vm = TestVm(app, PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main))
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
        engineDispatcher.close()
    }

    private fun input() = CoreTestSupport.contentUri(app, "a.pdf", "%PDF".toByteArray())

    private fun state() = vm.jobState.value

    private fun dest(sink: OutputStream): Uri {
        val uri = Uri.parse("content://dest.test/${System.nanoTime()}.pdf")
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)
        return uri
    }

    private fun runToReady() {
        vm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
    }

    @Test
    fun idleRunningReadyToSaveSaved() {
        assertEquals(JobUiState.Idle, state())
        val atStep2 = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 2) {
                atStep2.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            vm.merge(listOf(input()))
            assertEquals(JobUiState.Running(0, 0), state())
            CoreTestSupport.idleUntil(message = "engine at step 2") { atStep2.count == 0L }
            CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
            assertTrue(vm.isRunning)
            vm.merge(listOf(input())) // ignored while running
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        val size = fake.outputContent.size.toLong()
        assertEquals(JobUiState.ReadyToSave("pages=1 bytes=$size"), state())
        assertEquals(PdfResult.Ok(1, size), vm.lastResult)
        assertEquals(1, fake.calls.size)
        assertEquals("merged.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        vm.save(dest(sink))
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        assertEquals(JobUiState.Saved("pages=1 bytes=$size"), state())
        assertArrayEquals(fake.outputContent, sink.toByteArray())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        assertNull(vm.saveError.value)
    }

    @Test
    fun engineErrorGivesFailedWithResourceString() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))
        vm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        assertEquals(JobUiState.Failed(app.getString(R.string.error_encrypted)), state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        assertNull(vm.lastResult)

        // A new start from a terminal state is allowed.
        fake.results.clear()
        runToReady()
    }

    @Test
    fun cancelReturnsToIdleAndCleansUp() {
        val atStep2 = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step ->
            if (step == 2) {
                atStep2.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            vm.merge(listOf(input()))
            CoreTestSupport.idleUntil(message = "engine at step 2") { atStep2.count == 0L }
            vm.cancel()
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "job finished") { !vm.isRunning }
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun saveNullStaysReadyToSave() {
        runToReady()
        val ready = state()
        vm.save(null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(ready, state())
        assertFalse(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun failedSaveKeepsOutputAndReportsOnce() {
        runToReady()
        val ready = state()
        vm.save(
            dest(
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk full")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
                },
            ),
        )
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        assertEquals(app.getString(R.string.core_save_failed), vm.saveError.value)
        assertEquals(ready, state())
        vm.clearSaveError()
        assertNull(vm.saveError.value)

        val sink = ByteArrayOutputStream()
        vm.save(dest(sink))
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        assertArrayEquals(fake.outputContent, sink.toByteArray())
    }

    @Test
    fun resetDiscardsPendingOutput() {
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))
        vm.reset()
        assertEquals(JobUiState.Idle, state())
        assertNull(vm.lastResult)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun scriptedOkReachesSummarizeAndLastResult() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Ok(pages = 3, bytes = 123_456))
        runToReady()
        assertEquals(JobUiState.ReadyToSave("pages=3 bytes=123456"), state())
        assertEquals(PdfResult.Ok(3, 123_456), vm.lastResult)
    }

    /** How feature ViewModel tests will run: default constructor, process-wide PdfJobs. */
    class DefaultVm(app: Application) : JobViewModel(app) {
        fun merge(uris: List<Uri>) = startJob(uris.map { JobInput.Pdf(it) }, { "ok" }) { e, f, o, j -> e.merge(f, o, j) }
    }

    @Test
    fun defaultConstructorUsesSharedPdfJobs() {
        val defaultVm = DefaultVm(app)
        assertEquals("document.pdf", defaultVm.suggestedFileName())
        defaultVm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "ReadyToSave") { defaultVm.jobState.value == JobUiState.ReadyToSave("ok") }
        defaultVm.reset()
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun onClearedDiscardsPendingOutput() {
        runToReady()
        vm.clear()
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    /** An output stream whose first write blocks until [release] is counted down. */
    private class BlockingSink(val release: CountDownLatch) : OutputStream() {
        val entered = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            bytes.write(b, off, len)
        }
    }

    @Test
    fun savingStateIgnoresResetStartCancelAndSave() {
        runToReady()
        val ready = state() as JobUiState.ReadyToSave
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            vm.save(dest(sink))
            assertEquals(JobUiState.Saving(ready.summary), state())
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            vm.reset()
            vm.cancel()
            vm.merge(listOf(input()))
            vm.save(dest(ByteArrayOutputStream()))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(JobUiState.Saving(ready.summary), state())
            assertEquals(1, fake.calls.size)
            assertFalse(vm.isRunning)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        assertEquals(JobUiState.Saved(ready.summary), state())
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun failedSaveGoesBackToReadyToSaveFromSaving() {
        runToReady()
        val ready = state()
        val seen = mutableListOf<JobUiState>()
        vm.save(dest(object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }))
        seen += state()
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        assertTrue(seen.single() is JobUiState.Saving)
        assertEquals(ready, state())
        assertFalse(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun clearingDuringSaveStillCompletesTheFile() {
        runToReady()
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            vm.save(dest(sink))
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            vm.clear()
            assertFalse("output must survive while it is being saved", CoreTestSupport.jobsEmpty(app))
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun clearingDuringFailingSaveDiscardsOutput() {
        runToReady()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            vm.save(dest(object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw IOException("disk full")
                }
            }))
            CoreTestSupport.idleUntil(message = "copy started") { entered.count == 0L }
            vm.clear()
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "cache cleaned") { CoreTestSupport.jobsEmpty(app) }
    }

    @Test
    fun saveWithoutPendingOutputDeletesDestinationAndExplains() {
        val created = java.io.File(app.filesDir, "created.pdf").apply { writeBytes(ByteArray(0)) }
        vm.save(Uri.fromFile(created))
        assertEquals(app.getString(R.string.core_result_unavailable), vm.saveError.value)
        assertEquals(JobUiState.Idle, state())
        CoreTestSupport.idleUntil(message = "destination deleted") { !created.exists() }
    }
}
