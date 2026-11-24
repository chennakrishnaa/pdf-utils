package app.venkat.offkit.compress

import app.venkat.offkit.core.isReadyToSave
import app.venkat.offkit.core.isSaved
import app.venkat.offkit.core.isSavingNow
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import app.venkat.offkit.ui.components.TAG_ACTION_CLOSE
import app.venkat.offkit.ui.components.TAG_JOB_SHARE
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.pdfErrorBody
import app.venkat.offkit.ui.components.pdfErrorTitle
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // A share in an earlier test cached its own data dir; without this the share copy fails.
        CoreTestSupport.clearFileProviderCache()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        resetRecents()
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { CompressScreen(onBack = { backCalls++ }) } } }
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

    /** Clicks [tag], scrolling to it first when it is in the scrolling content (not the fixed bottom bar). */
    private fun click(tag: String) {
        val node = rule.onNodeWithTag(tag)
        if (node.hasScrollableAncestor()) node.performScrollTo()
        node.performClick()
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.hasScrollableAncestor(): Boolean {
        var parent = fetchSemanticsNode().parent
        while (parent != null) {
            if (SemanticsActions.ScrollBy in parent.config) return true
            parent = parent.parent
        }
        return false
    }

    private fun state() = vm.jobState.value

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun runToReady() {
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
    }

    /** Back to the options with the inputs kept (what the error screen's button does). */
    private fun resetToOptions() {
        rule.runOnUiThread { vm.reset() }
        rule.waitForIdle()
    }

    private fun runToFailure(error: PdfError) {
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Err(error))
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()
    }

    private fun saveTo(sink: OutputStream) {
        rule.runOnUiThread { vm.save(dest(sink)) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_compress")
        rule.onNodeWithTag(TAG_COMPRESS_ROOT).assertExists()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noFile_slotAndDisabledPrimary_optionsEditable_defaultMedium() {
        rule.onNodeWithText(str(R.string.screen_compress)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_COMPRESS_FILE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).performScrollTo().assertIsEnabled()
        rule.onNodeWithText(str(R.string.choose_pdf)).assertExists()
        rule.onNodeWithText(str(R.string.choose_pdf_hint)).assertExists()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(str(R.string.choose_pdf_first))
        rule.onNodeWithTag(TAG_COMPRESS_NOTE).performScrollTo().assertTextEquals(str(R.string.compress_note))

        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW))
            .assertIsNotSelected().assertTextEquals(str(R.string.level_low), str(R.string.level_low_desc))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM))
            .assertTextEquals(str(R.string.level_medium), str(R.string.level_medium_desc))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH))
            .assertIsNotSelected().assertTextEquals(str(R.string.level_high), str(R.string.level_high_desc))
        assertEquals(CompressionLevel.MEDIUM, vm.level.value)

        // Options stay editable without a file.
        click(compressLevelTag(CompressionLevel.HIGH))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertIsSelected()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.MEDIUM)).assertIsNotSelected()
        assertEquals(CompressionLevel.HIGH, vm.level.value)
    }

    @Test
    fun slotOpensPdfPicker() {
        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_COMPRESS_CHOOSE)
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
    }

    @Test
    fun pickShowsFileCard_changeReplaces() {
        choose(pdf("first.pdf"))
        rule.onNodeWithTag(TAG_COMPRESS_FILE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("first.pdf").assertIsDisplayed()
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).assertTextEquals(str(R.string.change)).assertIsEnabled()
        rule.onNodeWithText(str(R.string.choose_pdf_hint)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled().assertTextEquals(str(R.string.action_compress))

        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_COMPRESS_CHOOSE)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(rule.activity).nextStartedActivity?.action)

        choose(pdf("second.pdf"))
        rule.onNodeWithText("second.pdf").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("first.pdf").assertDoesNotExist()
        assertEquals("second-compressed.pdf", vm.suggestedFileName())
    }

    @Test
    fun eachSelectedLevelReachesEngine() {
        choose(pdf("a.pdf"))
        runToReady() // default: Medium
        resetToOptions()
        click(compressLevelTag(CompressionLevel.LOW))
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.LOW)).assertIsSelected()
        runToReady()
        resetToOptions()
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
    fun runProcessingResultSaveAndSaved() {
        choose(pdf("report.pdf", size = 10_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 3, bytes = 1_000))
        fake.outputContent = "%PDF-1.7\ncompressed output\n".toByteArray()
        var inputSize = -1L
        val release = CountDownLatch(1)
        fake.beforeStep = { op, step ->
            if (op == FakePdfEngine.Op.COMPRESS && step == 1) inputSize = fake.callsFor(op).last().pdf!!.length()
            if (step == 2) release.await(10, TimeUnit.SECONDS)
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.processing_progress, 1, 3, "report.pdf")).assertExists()
        // The options are gone while processing.
        rule.onNodeWithTag(TAG_COMPRESS_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(compressLevelTag(CompressionLevel.HIGH)).assertDoesNotExist()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
        assertEquals(10_000L, inputSize)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(str(R.string.result_smaller, 90))
        rule.onNodeWithText(str(R.string.result_sizes, formatBytes(10_000), formatBytes(1_000))).assertExists()
        rule.onNodeWithText("report-compressed.pdf").assertExists()
        rule.onNodeWithText(str(R.string.result_not_saved)).assertExists()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_as)).assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_discard))
        assertEquals("report-compressed.pdf", vm.suggestedFileName())

        val sink = ByteArrayOutputStream()
        saveTo(sink)
        assertArrayEquals(fake.outputContent, sink.toByteArray())
        val saved = state() as JobUiState.Done
        rule.onNodeWithText(str(R.string.result_saved_as, saved.savedName ?: saved.suggestedName)).assertExists()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_copy))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(str(R.string.result_smaller, 90))
    }

    @Test
    fun notSmallerShowsNoGainScreen_buttonReturnsToOptions() {
        choose(pdf("tiny.pdf", size = 5_000))
        fake.setResult(FakePdfEngine.Op.COMPRESS, PdfResult.Ok(pages = 1, bytes = 5_000))
        runToReady()
        rule.onNodeWithText(str(R.string.error_no_gain_title)).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(R.string.error_no_gain_body))
        rule.onNodeWithText("tiny.pdf").assertExists()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()

        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_choose_another)).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        rule.onNodeWithTag(TAG_COMPRESS_FILE).assertExists()
        assertEquals("tiny.pdf", vm.file.value?.name)
        assertEquals(0, backCalls)
        assertEquals(1, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun engineErrorShowsErrorScreen_buttonReturnsToOptionsWithFile() {
        choose(pdf("a.pdf"))
        runToFailure(PdfError.Corrupt)
        rule.onNodeWithText(str(pdfErrorTitle(PdfError.Corrupt))).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(pdfErrorBody(PdfError.Corrupt)))
        rule.onNodeWithText("a.pdf").assertExists()
        rule.onNodeWithTag(TAG_COMPRESS_ROOT).assertDoesNotExist()

        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_choose_another)).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        rule.onNodeWithTag(TAG_COMPRESS_FILE).assertExists()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        assertEquals(0, backCalls)

        // Back on the error screen also returns to the options.
        runToFailure(PdfError.Corrupt)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, backCalls)
        assertEquals(2, fake.callsFor(FakePdfEngine.Op.COMPRESS).size)
    }

    @Test
    fun encryptedShowsLockedStrings() {
        choose(pdf("locked.pdf"))
        runToFailure(PdfError.Encrypted)
        rule.onNodeWithText(str(R.string.error_locked_title)).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(R.string.error_locked_body))
        rule.onNodeWithText("locked.pdf").assertExists()
    }

    @Test
    fun cancelReturnsToOptionsWithFileKept() {
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

        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_COMPRESS_FILE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("a.pdf").assertIsDisplayed()
        assertEquals("a.pdf", vm.file.value?.name)
        assertEquals(0, backCalls)
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
    fun resultScreenOffersNoInputs_viewModelStillDropsStaleResult() {
        choose(pdf("a.pdf"))
        runToReady()
        // The level and file cannot be changed from the result screen.
        rule.onNodeWithTag(TAG_COMPRESS_CHOOSE).assertDoesNotExist()
        CompressionLevel.entries.forEach { rule.onNodeWithTag(compressLevelTag(it)).assertDoesNotExist() }

        // ViewModel contract (e.g. a pick delivered late): a new level drops the result.
        rule.runOnUiThread { vm.setLevel(CompressionLevel.HIGH) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        // Nothing is written. (The "result no longer available" error is consumed by the flow host;
        // the options screen has no snackbar host, so it is not shown there.)
        assertEquals(0, sink.size())
        assertEquals(JobUiState.Idle, state())

        // Choosing another PDF drops the result too.
        runToReady()
        assertNull(vm.saveError.value)
        choose(pdf("b.pdf"))
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))

        // Choosing the level that is already selected changes nothing.
        runToReady()
        rule.runOnUiThread { vm.setLevel(CompressionLevel.HIGH) }
        rule.waitForIdle()
        assertTrue(state().isReadyToSave)
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
            assertTrue(state().isSavingNow)
            rule.onNodeWithTag(TAG_JOB_SAVING).assertIsDisplayed().assertIsNotEnabled()
            rule.onNodeWithTag(TAG_JOB_SHARE).assertIsNotEnabled()
            rule.onNodeWithTag(TAG_JOB_RESET).assertIsNotEnabled()
            // Leaving is ignored while saving.
            systemBack()
            rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertDoesNotExist()
            assertEquals(0, backCalls)
            // The ViewModel ignores input changes.
            rule.runOnUiThread {
                vm.setLevel(CompressionLevel.LOW)
                vm.choose(other)
            }
            rule.waitForIdle()
            assertEquals(CompressionLevel.MEDIUM, vm.level.value)
            assertEquals("a.pdf", vm.file.value?.name)
            assertTrue(state().isSavingNow)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    /** RD-4: a fast double tap on Save opens one system dialog; the guard resets when it returns. */
    @Test
    fun doubleTapOnSaveOpensOneDialogUntilItReturns() {
        choose(pdf("a.pdf"))
        runToReady()
        shadowOf(rule.activity).clearNextStartedActivities()
        rule.onNodeWithTag(TAG_JOB_SAVE).performClick()
        rule.onNodeWithTag(TAG_JOB_SAVE).performClick()
        rule.waitForIdle()
        val dialog = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, dialog?.intent?.action)
        // Robolectric records a launch for result in both lists: the first entry is the same launch.
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(rule.activity).nextStartedActivity?.action)
        assertNull("a second save dialog was opened", shadowOf(rule.activity).nextStartedActivity)

        // The dialog is cancelled: Save opens it again.
        rule.runOnUiThread {
            shadowOf(rule.activity).receiveResult(dialog!!.intent, android.app.Activity.RESULT_CANCELED, null)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SAVE).performClick()
        rule.waitForIdle()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(rule.activity).nextStartedActivity?.action)
    }

    @Test
    fun shareButtonRequestsShare() {
        choose(pdf("a.pdf"))
        runToReady()
        shadowOf(rule.activity).clearNextStartedActivities()
        click(TAG_JOB_SHARE)
        CoreTestSupport.idleUntil(rule, message = "shared") { (state() as? JobUiState.Done)?.shared == true }
        rule.waitForIdle()
        assertNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertNull(vm.shareRequest.value)
        rule.onNodeWithText(str(R.string.result_shared_only)).assertExists()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
    }

    @Test
    fun backOnOptionsLeavesWithoutDialog() {
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
    }

    @Test
    fun discardOnUnsavedResultAsks_keepStays_confirmLeaves() {
        choose(pdf("a.pdf"))
        runToReady()
        click(TAG_JOB_RESET)
        rule.onNodeWithText(str(R.string.discard_title)).assertExists()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(0, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()

        // Close and system back ask as well; confirming leaves and drops the result.
        rule.onNodeWithTag(TAG_ACTION_CLOSE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_STAY).performClick()
        rule.waitForIdle()
        systemBack()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun doneAfterSaveLeavesWithoutDialog() {
        choose(pdf("a.pdf"))
        runToReady()
        saveTo(ByteArrayOutputStream())
        click(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertDoesNotExist()
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        choose(pdf("report.pdf"))
        runToReady()
        saveTo(ByteArrayOutputStream())
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("compress", recents.single().tool)
        assertEquals(CompressViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveShowsSnackbarAndKeepsResult() {
        choose(pdf("a.pdf"))
        runToReady()
        val failed = str(R.string.error_save_message, str(R.string.error_save_title), str(R.string.error_save_body))
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(rule, message = "save error shown") {
            rule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithText(failed).assertExists()
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
        // The screen reported it as shown, so it is not shown again.
        assertNull(vm.saveError.value)
    }
}
