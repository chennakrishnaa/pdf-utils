package app.venkat.offkit.compress

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.feature.compress.CompressScreen
import app.venkat.offkit.feature.compress.CompressViewModel
import app.venkat.offkit.feature.compress.TAG_COMPRESS_CHOOSE
import app.venkat.offkit.feature.compress.TAG_COMPRESS_NOTE
import app.venkat.offkit.feature.compress.TAG_COMPRESS_FILE
import app.venkat.offkit.feature.compress.TAG_COMPRESS_ROOT
import app.venkat.offkit.feature.compress.TAG_COMPRESS_SAVE_ERROR
import app.venkat.offkit.feature.compress.compressLevelTag
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_JOB_CANCEL
import app.venkat.offkit.ui.components.TAG_JOB_ERROR
import app.venkat.offkit.ui.components.TAG_JOB_PROGRESS
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.TAG_JOB_SAVE
import app.venkat.offkit.ui.components.TAG_JOB_SAVING
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.fileRowTag
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric + [FakePdfEngine]. The original size is the length of the input bytes copied for the job
 * (preferred over the provider size, which Robolectric content Uris do not report anyway), which
 * the test controls through the content registered for the Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompressScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: CompressViewModel
    private var backCalls = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        resetRecents()
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { CompressScreen(onBack = { backCalls++ }) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[CompressViewModel::class.java]
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
        resetRecents()
    }

    private fun resetRecents() {
        RecentFilesStore.resetForTesting()
        app.getSharedPreferences("recent_files", Context.MODE_PRIVATE).edit().clear().commit()
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

    private object FailingSink : OutputStream() {
        override fun write(b: Int) = throw IOException("disk full")
        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
    }

    private fun dest(sink: OutputStream): Uri {
        val uri = Uri.parse("content://dest.test/${System.nanoTime()}/out.pdf")
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)
        return uri
    }

    private fun pdf(name: String, size: Int = 10_000): Uri =
        CoreTestSupport.contentUri(app, name, ByteArray(size) { (it % 251).toByte() })

    private fun choose(uri: Uri) {
        rule.runOnUiThread { vm.choose(uri) }
        CoreTestSupport.idleUntil(message = "chosen $uri") { vm.file.value?.uri == uri }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun state() = vm.jobState.value

    private fun runToReady() {
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
    }

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_compress")
        rule.onNodeWithTag(TAG_COMPRESS_ROOT).assertExists()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runDisabledUntilChosen_chooseAgainReplaces_defaultMedium() {
        rule.onNodeWithTag(TAG_COMPRESS_NOTE).performScrollTo().assertTextEquals(app.getString(R.string.compress_note))
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW)).assertIsNotSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertIsNotSelected()
        assertEquals(CompressionLevel.MEDIUM, vm.level.value)

        rule.onNodeWithTag(TAG_COMPRESS_FILE).performScrollTo().assertTextEquals(app.getString(R.string.compress_empty))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW))
            .assertTextEquals(app.getString(R.string.compress_level_low), "Up to 2000 px images, best quality")
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM))
            .assertTextEquals(app.getString(R.string.compress_level_medium), "Up to 1500 px, good quality")
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH))
            .assertTextEquals(app.getString(R.string.compress_level_high), "Up to 1000 px, smallest file")

        choose(pdf("first.pdf"))
        rule.onNodeWithText("first.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()

        choose(pdf("second.pdf"))
        rule.onNodeWithText("second.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("first.pdf").assertDoesNotExist()
        rule.onNodeWithTag(fileRowTag(1)).assertDoesNotExist()
        assertEquals("second-compressed.pdf", vm.suggestedFileName())
    }

    @Test
    fun eachSelectedLevelReachesEngine() {
        choose(pdf("a.pdf"))
        runToReady() // default: Medium
        click(TAG_JOB_RESET) // "Discard" brings back the run button
        click(compressLevelTag(CompressionLevel.LOW))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW)).assertIsSelected()
        runToReady()
        click(TAG_JOB_RESET)
        click(compressLevelTag(CompressionLevel.HIGH))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).assertIsNotSelected()
        runToReady()

        val calls = fake.callsFor(FakePdfEngine.Op.COMPRESS)
        assertEquals(
            listOf(CompressionLevel.MEDIUM, CompressionLevel.LOW, CompressionLevel.HIGH),
            calls.map { it.level },
        )
    }

    @Test
    fun progressSummarySaveAndSaved() {
        choose(pdf("report.pdf", size = 10_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 3, bytes = 1_000))
        fake.outputContent = "%PDF-1.7\ncompressed output\n".toByteArray()
        val received = CountDownLatch(1)
        var inputSize = -1L
        val release = CountDownLatch(1)
        fake.beforeStep = { op, step ->
            if (op == FakePdfEngine.Op.COMPRESS && step == 1) {
                inputSize = fake.callsFor(op).last().pdf!!.length()
                received.countDown()
            }
            if (step == 2) release.await(10, TimeUnit.SECONDS)
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).performScrollTo().assertIsNotEnabled()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
        assertEquals(10_000L, inputSize)
        val summary = "9.8 KB → 1000 B (90% smaller)"
        assertEquals(summary, app.getString(R.string.compress_summary, "9.8 KB", "1000 B", 90))
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()
        assertEquals("report-compressed.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/report-compressed.pdf")
        shadowOf(app.contentResolver).registerOutputStream(dest, sink)
        rule.runOnUiThread { vm.save(dest) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        rule.onNodeWithText(app.getString(R.string.job_saved_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
    }

    @Test
    fun notSmallerShowsAlreadySmallMessage_andCanStillSave() {
        choose(pdf("tiny.pdf", size = 5_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 1, bytes = 5_000))
        runToReady()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo()
            .assertTextEquals(app.getString(R.string.compress_summary_no_gain))
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun unknownOriginalSizeShowsJustNewSize() {
        // An empty input copy gives no usable original size.
        choose(pdf("empty.pdf", size = 0))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 1, bytes = 2_048))
        runToReady()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo()
            .assertTextEquals(app.getString(R.string.compress_summary_size_only, "2.0 KB"))
    }

    @Test
    fun encryptedAndCorruptShowMessages() {
        choose(pdf("a.pdf"))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Err(PdfError.Encrypted))
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo().assertTextEquals(app.getString(R.string.error_encrypted))

        rule.runOnUiThread { vm.reset() }
        rule.waitForIdle()
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Err(PdfError.Corrupt))
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo().assertTextEquals(app.getString(R.string.error_corrupt))
        assertEquals(2, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun cancelReturnsToIdle() {
        choose(pdf("a.pdf"))
        val release = CountDownLatch(1)
        fake.beforeStep = { _, step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        click(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        assertEquals("a.pdf", vm.file.value?.name)
        assertTrue(CoreTestSupport.jobsEmpty(app))
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun suggestedFileNameLogic() {
        assertEquals("report-compressed.pdf", CompressViewModel.compressedFileName("report.pdf"))
        assertEquals("scan-compressed.pdf", CompressViewModel.compressedFileName("scan"))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(""))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName("   "))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(null))
        assertEquals("Report-compressed.pdf", CompressViewModel.compressedFileName("Report.PDF"))
        assertEquals("a_b-compressed.pdf", CompressViewModel.compressedFileName("a/b.pdf"))
        assertEquals("compressed.pdf", CompressViewModel.compressedFileName(".pdf"))
        // Without a chosen file the ViewModel suggests the fallback.
        assertEquals("compressed.pdf", vm.suggestedFileName())
    }

    @Test
    fun changingFileOrLevelDiscardsReadyResult() {
        choose(pdf("a.pdf"))
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))

        // Changing the level drops the result.
        click(compressLevelTag(CompressionLevel.HIGH))
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, sink.size())
        assertEquals(app.getString(R.string.core_result_unavailable), vm.saveError.value)

        // Choosing another PDF drops the result too (and the stale save error).
        runToReady()
        assertNull(vm.saveError.value)
        choose(pdf("b.pdf"))
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed()

        // Choosing the level that is already selected changes nothing.
        runToReady()
        click(compressLevelTag(CompressionLevel.HIGH))
        assertTrue(state() is JobUiState.ReadyToSave)
    }

    @Test
    fun inputsLockedWhileSaving() {
        choose(pdf("a.pdf"))
        runToReady()
        val other = pdf("other.pdf")
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            rule.runOnUiThread { vm.save(dest(sink)) }
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            rule.waitForIdle()
            assertTrue(state() is JobUiState.Saving)
            rule.onNodeWithTag(TAG_JOB_SAVING).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsNotEnabled()
            CompressionLevel.entries.forEach { rule.onNodeWithTag(compressLevelTag(it)).assertIsNotEnabled() }
            // The ViewModel ignores changes too.
            rule.runOnUiThread {
                vm.setLevel(CompressionLevel.LOW)
                vm.choose(other)
            }
            rule.waitForIdle()
            assertEquals(CompressionLevel.MEDIUM, vm.level.value)
            assertEquals("a.pdf", vm.file.value?.name)
            assertTrue(state() is JobUiState.Saving)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun backWithResultAsksBeforeLeaving() {
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()

        choose(pdf("a.pdf"))
        runToReady()
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
        assertTrue(state() is JobUiState.ReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()

        // System back asks as well; confirming leaves.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(2, backCalls)
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        choose(pdf("report.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("compress", recents.single().tool)
        assertEquals(CompressViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveErrorStaysUntilNextAction() {
        choose(pdf("a.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        rule.waitForIdle()
        val failed = app.getString(R.string.core_save_failed)
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        assertTrue(state() is JobUiState.ReadyToSave)

        // A save after the result is gone explains it, in Idle, and the message stays.
        click(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).assertDoesNotExist()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).performScrollTo()
            .assertTextEquals(app.getString(R.string.core_result_unavailable))
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).assertExists()

        // The next action (a new run) clears it.
        runToReady()
        rule.onNodeWithTag(TAG_COMPRESS_SAVE_ERROR).assertDoesNotExist()
    }
}
