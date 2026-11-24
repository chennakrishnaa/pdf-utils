package app.venkat.offkit.core

import app.venkat.offkit.ui.components.isSaving
import app.venkat.offkit.ui.components.hasUnsavedWork
import java.io.File
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.ui.components.JobUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        ) { engine, files, out, job -> engine.merge(files, out, job) }

        fun clear() = onCleared()

        fun invalidate() = invalidateResult()

        override fun suggestedFileName() = "merged.pdf"

        override val toolId = "merge"
    }

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var engineDispatcher: ExecutorCoroutineDispatcher
    private lateinit var vm: TestVm

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CoreTestSupport.clearFileProviderCache()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        vm = TestVm(app, PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main))
        RecentFilesStore.resetForTesting()
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
        engineDispatcher.close()
        RecentFilesStore.resetForTesting()
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
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
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
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        val size = fake.outputContent.size.toLong()
        assertEquals(JobUiState.Done(1, size, null, "merged.pdf"), state())
        assertEquals(PdfResult.Ok(1, size), vm.lastResult)
        assertEquals(1, fake.calls.size)
        assertEquals("merged.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        vm.save(dest(sink))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        val saved = state() as JobUiState.Done
        assertEquals(1, saved.pages)
        assertEquals(size, saved.bytes)
        assertFalse(saved.saving)
        assertNotNull(saved.savedUri)
        assertArrayEquals(fake.outputContent, sink.toByteArray())
        // The output survives a successful save.
        assertFalse(CoreTestSupport.jobsEmpty(app))
        assertNull(vm.saveError.value)
    }

    @Test
    fun engineErrorGivesFailedWithResourceString() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))
        vm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        assertEquals(JobUiState.Failed(PdfError.Encrypted), state())
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
        assertEquals("Couldn't save. There may not be enough space. Your result is still here, so try another folder.", vm.saveError.value)
        assertEquals(ready, state())
        vm.clearSaveError()
        assertNull(vm.saveError.value)

        val sink = ByteArrayOutputStream()
        vm.save(dest(sink))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
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
        assertEquals(JobUiState.Done(3, 123_456, null, "merged.pdf"), state())
        assertEquals(PdfResult.Ok(3, 123_456), vm.lastResult)
    }

    /** How feature ViewModel tests will run: default constructor, process-wide PdfJobs. */
    class DefaultVm(app: Application) : JobViewModel(app) {
        fun merge(uris: List<Uri>) = startJob(uris.map { JobInput.Pdf(it) }) { e, f, o, j -> e.merge(f, o, j) }
    }

    @Test
    fun defaultConstructorUsesSharedPdfJobs() {
        val defaultVm = DefaultVm(app)
        assertEquals("document.pdf", defaultVm.suggestedFileName())
        defaultVm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "ReadyToSave") { defaultVm.jobState.value.isReadyToSave }
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
        val ready = state() as JobUiState.Done
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            vm.save(dest(sink))
            assertEquals(ready.copy(saving = true), state())
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            vm.reset()
            vm.cancel()
            vm.merge(listOf(input()))
            vm.save(dest(ByteArrayOutputStream()))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(ready.copy(saving = true), state())
            assertEquals(1, fake.calls.size)
            assertFalse(vm.isRunning)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertTrue(state().isSaved)
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
        assertFalse(CoreTestSupport.jobsEmpty(app))
    }

    /** Lets the main looper and the IO dispatcher run a few times (for best-effort background work). */
    private fun settle() = repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }

    @Test
    fun destinationArrivingWhileSavingIsDeletedOnlyIfEmpty() {
        runToReady()
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        val emptyExtra = File(app.filesDir, "extra-empty.pdf").apply { writeBytes(ByteArray(0)) }
        val existing = File(app.filesDir, "extra-existing.pdf").apply { writeBytes(ByteArray(9)) }
        try {
            vm.save(dest(sink))
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            vm.save(Uri.fromFile(emptyExtra))
            vm.save(Uri.fromFile(existing))
            CoreTestSupport.idleUntil(message = "empty extra deleted") { !emptyExtra.exists() }
            settle()
            assertEquals(9L, existing.length())
            assertTrue(state().isSaving)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
        assertNull(vm.saveError.value)
    }

    @Test
    fun sameDestinationArrivingAgainWhileSavingIsNotDeleted() {
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        vm = TestVm(app, PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main) { _, _ -> sink })
        runToReady()
        val target = File(app.filesDir, "target.pdf").apply { writeBytes(ByteArray(0)) }
        try {
            vm.save(Uri.fromFile(target))
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            // Still empty on disk (the sink holds the bytes): a re-delivery must not delete it.
            vm.save(Uri.fromFile(target))
            settle()
            assertTrue(target.exists())
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertTrue(target.exists())
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
        assertTrue(seen.single().isSavingNow)
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
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
        assertTrue("a cleared ViewModel discards the output once the save ends", CoreTestSupport.jobsEmpty(app))
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

    @Test
    fun invalidateResultFromReadyToSave() {
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))
        vm.invalidate()
        assertEquals(JobUiState.Idle, state())
        assertNull(vm.lastResult)
        assertNull(vm.saveError.value)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun invalidateResultFromReadyToSaveWithSaveError() {
        runToReady()
        vm.save(dest(object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }))
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        vm.invalidate()
        assertEquals(JobUiState.Idle, state())
        assertNull(vm.saveError.value)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun invalidateResultFromSaved() {
        runToReady()
        vm.save(dest(ByteArrayOutputStream()))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        vm.invalidate()
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun invalidateResultFromFailed() {
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))
        vm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        vm.invalidate()
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun invalidateResultIsNoOpWhileRunningAndSaving() {
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
            vm.invalidate()
            assertTrue(state() is JobUiState.Running)
            assertTrue(vm.isRunning)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        fake.beforeStep = null

        val saveRelease = CountDownLatch(1)
        val sink = BlockingSink(saveRelease)
        try {
            vm.save(dest(sink))
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            vm.invalidate()
            assertTrue(state().isSavingNow)
        } finally {
            saveRelease.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun successfulSaveIsRecordedInRecents() {
        runToReady()
        val target = java.io.File(app.filesDir, "result.pdf").apply { writeBytes(ByteArray(0)) }
        val before = System.currentTimeMillis()
        vm.save(Uri.fromFile(target))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        val recent = RecentFilesStore.get(app).recents.value.single()
        assertEquals(Uri.fromFile(target), recent.uri)
        assertEquals("result.pdf", recent.name)
        assertEquals(fake.outputContent.size.toLong(), recent.sizeBytes)
        assertEquals("merge", recent.tool)
        assertTrue(recent.savedAtMillis >= before)
        assertArrayEquals(fake.outputContent, target.readBytes())
    }

    @Test
    fun failedSaveIsNotRecorded() {
        runToReady()
        vm.save(dest(object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }))
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        assertTrue(RecentFilesStore.get(app).recents.value.isEmpty())
    }

    @Test
    fun saveWithoutPendingOutputKeepsNonEmptyDestination() {
        val existing = java.io.File(app.filesDir, "existing.pdf").apply { writeBytes(ByteArray(9)) }
        vm.save(Uri.fromFile(existing))
        assertEquals(app.getString(R.string.core_result_unavailable), vm.saveError.value)
        repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(9L, existing.length())
    }

    private fun sharedRoot() = File(app.cacheDir, "shared")

    private fun sharedFiles() = sharedRoot().walk().filter { it.isFile }.toList()

    private fun fileDest(name: String): Uri = Uri.fromFile(File(app.filesDir, "${System.nanoTime()}/$name").apply { parentFile!!.mkdirs() })

    @Test
    fun outputSurvivesSaveAndSecondSaveUpdatesSavedName() {
        runToReady()
        assertTrue(state().hasUnsavedWork())
        val first = fileDest("first.pdf")
        vm.save(first)
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        val saved1 = state() as JobUiState.Done
        assertEquals(first, saved1.savedUri)
        assertEquals(UriIo.describe(app, first).name, saved1.savedName)
        assertFalse(state().hasUnsavedWork())
        assertFalse(CoreTestSupport.jobsEmpty(app))

        val second = fileDest("second.pdf")
        vm.save(second)
        CoreTestSupport.idleUntil(message = "Saved again") { (state() as JobUiState.Done).savedUri == second && !state().isSaving }
        val saved2 = state() as JobUiState.Done
        assertEquals(UriIo.describe(app, second).name, saved2.savedName)
        assertTrue(saved2.savedName != saved1.savedName)
        assertArrayEquals(fake.outputContent, File(second.path!!).readBytes())
        assertEquals(2, RecentFilesStore.get(app).recents.value.size)
    }

    @Test
    fun shareCopiesUnderSharedWithSuggestedNameAndShareAuthority() {
        runToReady()
        vm.share()
        CoreTestSupport.idleUntil(timeoutMs = CoreTestSupport.SHARE_TIMEOUT_MS, message = "share request") { vm.shareRequest.value != null }
        val request = vm.shareRequest.value!!
        assertEquals("merged.pdf", request.name)
        assertEquals("content", request.uri.scheme)
        assertEquals(app.packageName + ".share", request.uri.authority)
        val copy = sharedFiles().single()
        assertEquals("merged.pdf", copy.name)
        assertEquals(sharedRoot(), copy.parentFile!!.parentFile)
        assertArrayEquals(fake.outputContent, copy.readBytes())
        assertTrue(state().hasUnsavedWork())

        vm.onShareHandled(launched = true)
        assertNull(vm.shareRequest.value)
        assertTrue((state() as JobUiState.Done).shared)
        assertFalse(state().hasUnsavedWork())
        // Not wiped right after sharing: the receiving app may still be reading it.
        assertEquals(1, sharedFiles().size)
    }

    @Test
    fun shareNotLaunchedDoesNotMarkShared() {
        runToReady()
        vm.share()
        CoreTestSupport.idleUntil(timeoutMs = CoreTestSupport.SHARE_TIMEOUT_MS, message = "share request") { vm.shareRequest.value != null }
        vm.onShareHandled(launched = false)
        assertFalse((state() as JobUiState.Done).shared)
        assertTrue(state().hasUnsavedWork())
    }

    @Test
    fun sharedCopiesAreWipedByTheNextJob() {
        runToReady()
        vm.share()
        CoreTestSupport.idleUntil(timeoutMs = CoreTestSupport.SHARE_TIMEOUT_MS, message = "share request") { vm.shareRequest.value != null }
        vm.onShareHandled(launched = true)
        assertEquals(1, sharedFiles().size)
        vm.merge(listOf(input()))
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        assertTrue(sharedFiles().isEmpty())
    }

    private fun shareOnce(): ShareRequest {
        vm.share()
        CoreTestSupport.idleUntil(timeoutMs = CoreTestSupport.SHARE_TIMEOUT_MS, message = "share request") { vm.shareRequest.value != null }
        val request = vm.shareRequest.value!!
        vm.onShareHandled(launched = true)
        return request
    }

    @Test
    fun repeatedShareReusesOneCopyAndRecreatesItIfDeleted() {
        runToReady()
        val first = shareOnce()
        val second = shareOnce()
        assertEquals(first.uri, second.uri)
        assertEquals(1, sharedRoot().listFiles()!!.size)
        assertEquals(1, sharedFiles().size)

        sharedFiles().single().delete()
        val third = shareOnce()
        assertTrue(third.uri != first.uri)
        assertArrayEquals(fake.outputContent, sharedFiles().single().readBytes())
    }

    @Test
    fun newResultGetsItsOwnShareCopy() {
        runToReady()
        val first = shareOnce()
        vm.reset()
        runToReady()
        val second = shareOnce()
        assertTrue(second.uri != first.uri)
        assertArrayEquals(fake.outputContent, sharedFiles().single().readBytes())
    }

    @Test
    fun failedShareCopySetsErrorAndKeepsState() {
        runToReady()
        val before = state()
        File(app.cacheDir, "jobs").walk().filter { it.name == "output.pdf" }.forEach { it.delete() }
        vm.share()
        CoreTestSupport.idleUntil(message = "share error") { vm.saveError.value != null }
        assertEquals(app.getString(R.string.result_share_failed), vm.saveError.value)
        assertNull(vm.shareRequest.value)
        assertEquals(before, state())
        assertTrue(sharedFiles().isEmpty())
    }

    @Test
    fun outputDeletedOnResetAfterSave() {
        runToReady()
        vm.save(fileDest("a.pdf"))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertFalse(CoreTestSupport.jobsEmpty(app))
        vm.reset()
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun outputDeletedOnClearedAfterSave() {
        runToReady()
        vm.save(fileDest("a.pdf"))
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        assertFalse(CoreTestSupport.jobsEmpty(app))
        vm.clear()
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }
}
