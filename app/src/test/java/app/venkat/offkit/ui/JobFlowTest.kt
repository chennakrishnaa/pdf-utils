package app.venkat.offkit.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.OffkitJobFlow
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_ACTION_CLOSE
import app.venkat.offkit.ui.components.TAG_JOB_CANCEL
import app.venkat.offkit.ui.components.TAG_JOB_ERROR
import app.venkat.offkit.ui.components.TAG_JOB_OPEN
import app.venkat.offkit.ui.components.TAG_JOB_PROGRESS
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.TAG_JOB_SAVE
import app.venkat.offkit.ui.components.TAG_JOB_SAVING
import app.venkat.offkit.ui.components.TAG_JOB_SHARE
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_JOB_SNACKBAR_OVERLAY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.pdfErrorBody
import app.venkat.offkit.ui.components.pdfErrorTitle
import app.venkat.offkit.ui.components.sharePdfIntent
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JobFlowTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf<JobUiState>(JobUiState.Idle)
    private var saveError by mutableStateOf<String?>(null)

    private val allErrors: List<PdfError> = listOf(
        PdfError.Encrypted, PdfError.Corrupt, PdfError.UnsupportedImage, PdfError.OutOfMemory,
        PdfError.ImageTooLarge, PdfError.Cancelled, PdfError.NotImplemented, PdfError.EngineCrashed,
        PdfError.EngineUnavailable, PdfError.TimedOut, PdfError.Io(IOException("disk")),
    )

    private val unsaved = JobUiState.Done(pages = 3, bytes = 360, originalBytes = 1000, suggestedName = "out.pdf")

    private fun str(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    private fun flow(initial: JobUiState, tool: OffkitTool = OffkitTool.COMPRESS, noGain: Boolean = false, inputName: String? = "in.pdf") {
        state = initial
        show {
            OffkitJobFlow(
                tool = tool,
                title = "Tool",
                state = state,
                inputName = inputName,
                inputBytes = 2048L,
                saveError = saveError,
                onSaveErrorShown = { calls += "errorShown"; saveError = null },
                shareRequest = null,
                onShareHandled = { calls += "shareHandled" },
                onCancel = { calls += "cancel" },
                onSave = { calls += "save" },
                onShare = { calls += "share" },
                onReset = { calls += "reset" },
                onLeave = { calls += "leave" },
                onOpen = { calls += "open $it" },
                errorActionLabel = "Try again",
                noGainAsError = noGain,
            ) { Text("OPTIONS", Modifier.testTag("options")) }
        }
    }

    private fun moveTo(s: JobUiState) {
        rule.runOnUiThread { state = s }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun assertAbsent(vararg tags: String) = tags.forEach { rule.onNodeWithTag(it).assertDoesNotExist() }

    private fun progressInfo(): ProgressBarRangeInfo? =
        rule.onNodeWithTag(TAG_JOB_PROGRESS).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)

    // ---- Screen per state ----

    @Test
    fun eachStateShowsItsScreen() {
        flow(JobUiState.Idle)
        rule.onNodeWithTag("options").assertExists()
        assertAbsent(TAG_JOB_PROGRESS, TAG_JOB_SUMMARY, TAG_JOB_ERROR)

        moveTo(JobUiState.Running(1, 4))
        rule.onNodeWithTag(TAG_JOB_PROGRESS).assertExists()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertExists()
        assertAbsent("options", TAG_JOB_SUMMARY, TAG_JOB_ERROR)

        moveTo(unsaved)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertExists()
        rule.onNodeWithTag(TAG_ACTION_CLOSE).assertExists()
        assertAbsent("options", TAG_JOB_PROGRESS, TAG_JOB_ERROR)

        moveTo(JobUiState.Failed(PdfError.Corrupt))
        rule.onNodeWithTag(TAG_JOB_ERROR).assertExists()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertExists()
        assertAbsent("options", TAG_JOB_PROGRESS, TAG_JOB_SUMMARY)
    }

    // ---- Processing ----

    @Test
    fun determinateProgressShowsPageFileAndPercent() {
        flow(JobUiState.Running(9, 24))
        rule.onNodeWithText(str(R.string.processing_compress)).assertExists()
        rule.onNodeWithText(str(R.string.processing_progress, 9, 24, "in.pdf")).assertExists()
        rule.onNodeWithText(str(R.string.job_percent, 37)).assertExists()
        rule.onNodeWithText(str(R.string.processing_keep_open)).assertExists()
        val info = progressInfo()
        assertNotNull(info)
        assertEquals(9f / 24f, info!!.current, 0.001f)
        assertFalse(info == ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun determinateProgressWithoutFileNameUsesPlainCount() {
        flow(JobUiState.Running(2, 5), inputName = null)
        rule.onNodeWithText(str(R.string.job_progress, 2, 5)).assertExists()
    }

    @Test
    fun unknownTotalIsIndeterminateWithoutPercent() {
        flow(JobUiState.Running(0, 0))
        assertEquals(ProgressBarRangeInfo.Indeterminate, progressInfo())
        rule.onNodeWithText(str(R.string.job_working)).assertExists()
        rule.onNodeWithText(str(R.string.job_percent, 0)).assertDoesNotExist()
    }

    @Test
    fun processingHeadlinePerTool() {
        flow(JobUiState.Running(0, 0), tool = OffkitTool.ADD_PAGES)
        rule.onNodeWithText(str(R.string.processing_add_pages)).assertExists()
        flow(JobUiState.Running(0, 0), tool = OffkitTool.MERGE)
        rule.onNodeWithText(str(R.string.processing_merge)).assertExists()
        flow(JobUiState.Running(0, 0), tool = OffkitTool.IMAGES)
        rule.onNodeWithText(str(R.string.processing_images)).assertExists()
    }

    @Test
    fun cancelButtonAndSystemBackCallOnCancel() {
        flow(JobUiState.Running(1, 4))
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertTextEquals(str(R.string.cancel)).performClick()
        back()
        assertEquals(listOf("cancel", "cancel"), calls)
        assertAbsent(TAG_LEAVE_STAY, TAG_LEAVE_CONFIRM)
    }

    // ---- Result ----

    @Test
    fun unsavedResultLabels() {
        flow(unsaved)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(str(R.string.result_smaller, 64))
        rule.onNodeWithText(str(R.string.result_sizes, formatBytes(1000), formatBytes(360))).assertExists()
        rule.onNodeWithText("out.pdf").assertExists()
        rule.onNodeWithText(str(R.string.result_suggested_name)).assertExists()
        rule.onNodeWithText(str(R.string.result_not_saved)).assertExists()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_as)).assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_SHARE).assertTextEquals(str(R.string.action_share))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_discard))
        assertAbsent(TAG_JOB_OPEN)
    }

    @Test
    fun resultWithoutOriginalShowsPageCount() {
        flow(unsaved.copy(originalBytes = null))
        val pages = rule.activity.resources.getQuantityString(R.plurals.pages_count, 3, 3)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pages)
        rule.onNodeWithText(formatBytes(360)).assertExists()
    }

    @Test
    fun savedResultLabels() {
        flow(unsaved.copy(savedName = "Saved copy.pdf", savedUri = Uri.parse("content://x/1")))
        rule.onNodeWithText(str(R.string.result_saved_as, "Saved copy.pdf")).assertExists()
        rule.onNodeWithTag(TAG_JOB_OPEN).assertTextEquals(str(R.string.action_open))
        rule.onNodeWithTag(TAG_JOB_SAVE).assertTextEquals(str(R.string.action_save_copy))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_done))
        rule.onNodeWithText(str(R.string.result_not_saved)).assertDoesNotExist()
    }

    /** RD-7: without a reported name the row says "Saved", never the suggested name. */
    @Test
    fun savedWithoutNameDoesNotGuessIt() {
        flow(unsaved.copy(savedName = null, savedUri = Uri.parse("content://x/1")))
        rule.onNodeWithText(str(R.string.result_saved)).assertExists()
        rule.onNodeWithText(str(R.string.result_saved_as, unsaved.suggestedName)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_OPEN).assertExists()
    }

    @Test
    fun discardOnUnsavedAsksKeepStaysConfirmLeaves() {
        flow(unsaved)
        rule.onNodeWithTag(TAG_JOB_RESET).performClick()
        rule.onNodeWithText(str(R.string.discard_title)).assertExists()
        rule.onNodeWithText(str(R.string.discard_body)).assertExists()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertTextEquals(str(R.string.keep)).performClick()
        assertAbsent(TAG_LEAVE_STAY, TAG_LEAVE_CONFIRM)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertExists()
        assertTrue(calls.isEmpty())

        rule.onNodeWithTag(TAG_ACTION_CLOSE).performClick()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertTextEquals(str(R.string.action_discard)).performClick()
        assertEquals(listOf("reset", "leave"), calls)
    }

    @Test
    fun systemBackOnUnsavedAsks() {
        flow(unsaved)
        back()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertExists()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun doneAfterSaveLeavesWithoutDialog() {
        flow(unsaved.copy(savedName = "a.pdf", savedUri = Uri.parse("content://x/1")))
        rule.onNodeWithTag(TAG_JOB_RESET).performClick()
        assertAbsent(TAG_LEAVE_CONFIRM)
        assertEquals(listOf("reset", "leave"), calls)

        calls.clear()
        back()
        assertAbsent(TAG_LEAVE_CONFIRM)
        assertEquals(listOf("reset", "leave"), calls)
    }

    /** RD-1: tapping Share on an unsaved result changes nothing; leaving still asks. */
    @Test
    fun shareTapOnUnsavedResultStillAsksBeforeLeaving() {
        flow(unsaved)
        rule.onNodeWithTag(TAG_JOB_SHARE).performClick()
        assertEquals(listOf("share"), calls)
        rule.onNodeWithText(str(R.string.result_not_saved)).assertExists()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_discard))
        back()
        rule.onNodeWithText(str(R.string.discard_title)).assertExists()
        assertEquals(listOf("share"), calls)
    }

    @Test
    fun savingDisablesPrimaryAndIgnoresLeaving() {
        flow(unsaved.copy(saving = true))
        rule.onNodeWithTag(TAG_JOB_SAVING).assertTextEquals(str(R.string.core_saving)).assertIsNotEnabled()
        assertAbsent(TAG_JOB_SAVE)
        rule.onNodeWithTag(TAG_JOB_SHARE).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_JOB_RESET).assertIsNotEnabled()
        back()
        rule.onNodeWithTag(TAG_ACTION_CLOSE).performClick()
        assertAbsent(TAG_LEAVE_CONFIRM)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun shareCallsOnShare() {
        flow(unsaved)
        rule.onNodeWithTag(TAG_JOB_SHARE).performClick()
        assertEquals(listOf("share"), calls)
    }

    @Test
    fun saveErrorShowsSnackbarAndKeepsResult() {
        flow(unsaved)
        rule.runOnUiThread { saveError = "Couldn't save" }
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't save").assertExists()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertExists()
        assertEquals(listOf("errorShown"), calls)
    }

    @Test
    fun saveErrorWhileOptionsShowIsShownBeforeBeingCleared() {
        flow(JobUiState.Idle)
        rule.runOnUiThread { saveError = "Result unavailable" }
        rule.waitForIdle()
        rule.onNodeWithTag("options").assertExists()
        rule.onNodeWithText("Result unavailable").assertIsDisplayed()
        rule.onNode(hasText("Result unavailable") and hasAnyAncestor(hasTestTag(TAG_JOB_SNACKBAR_OVERLAY))).assertExists()
        assertEquals(listOf("errorShown"), calls)
    }

    @Test
    fun nonPositiveOriginalCountsAsUnknown() {
        val pages = rule.activity.resources.getQuantityString(R.plurals.pages_count, 3, 3)
        for (original in listOf(0L, -1L)) {
            flow(unsaved.copy(bytes = 360, originalBytes = original), noGain = true)
            rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pages)
            rule.onNodeWithText(formatBytes(360)).assertExists()
            rule.onNodeWithText(str(R.string.error_no_gain_title)).assertDoesNotExist()
        }
    }

    // ---- Error ----

    @Test
    fun everyPdfErrorHasTitleAndBody() {
        flow(JobUiState.Failed(PdfError.Corrupt))
        for (error in allErrors) {
            moveTo(JobUiState.Failed(error))
            val title = str(pdfErrorTitle(error))
            val body = str(pdfErrorBody(error))
            assertTrue("$error title", title.isNotBlank())
            assertTrue("$error body", body.isNotBlank())
            rule.onNodeWithText(title).assertExists()
            rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(body)
        }
    }

    @Test
    fun encryptedUsesLockedStringsAndShowsFile() {
        flow(JobUiState.Failed(PdfError.Encrypted))
        rule.onNodeWithText(str(R.string.error_locked_title)).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(R.string.error_locked_body))
        rule.onNodeWithText("in.pdf").assertExists()
        rule.onNodeWithText(formatBytes(2048)).assertExists()
    }

    @Test
    fun errorPrimaryAndBackCallOnReset() {
        flow(JobUiState.Failed(PdfError.Corrupt))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals("Try again").performClick()
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        back()
        assertEquals(listOf("reset", "reset", "reset"), calls)
    }

    @Test
    fun noGainRendersAsErrorAndResets() {
        val noGain = unsaved.copy(bytes = 1000, originalBytes = 1000)
        flow(noGain, noGain = true)
        rule.onNodeWithText(str(R.string.error_no_gain_title)).assertExists()
        rule.onNodeWithTag(TAG_JOB_ERROR).assertTextEquals(str(R.string.error_no_gain_body))
        rule.onNodeWithText("in.pdf").assertExists()
        assertAbsent(TAG_JOB_SUMMARY)
        back()
        assertAbsent(TAG_LEAVE_CONFIRM)
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_choose_another)).performClick()
        assertEquals(listOf("reset", "reset"), calls)
    }

    @Test
    fun noGainIsAResultWhenNotRequested() {
        flow(unsaved.copy(bytes = 1000, originalBytes = 1000))
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertExists()
        rule.onNodeWithText(str(R.string.error_no_gain_title)).assertDoesNotExist()
    }

    // ---- Intents ----

    @Test
    fun sharePdfIntentGrantsReadOnTheStream() {
        val uri = Uri.parse("content://app.venkat.offkit.share/shared/x/out.pdf")
        val chooser = sharePdfIntent(uri, "out.pdf")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun openIsDisabledWhileSavingAgain() {
        val uri = Uri.parse("content://docs/1")
        flow(unsaved.copy(savedName = "a.pdf", savedUri = uri, saving = true))
        rule.onNodeWithTag(TAG_JOB_OPEN).performScrollTo().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("Open ignored while saving: $calls", calls.none { it.startsWith("open") })
        moveTo(unsaved.copy(savedName = "a.pdf", savedUri = uri))
        rule.onNodeWithTag(TAG_JOB_OPEN).assertIsEnabled()
    }

    @Test
    fun openHandsTheSavedUriToTheReaderWithNoIntent() {
        val uri = Uri.parse("content://docs/1")
        flow(unsaved.copy(savedName = "a.pdf", savedUri = uri))
        val shadow = shadowOf(rule.activity)
        while (shadow.nextStartedActivity != null) Unit
        rule.onNodeWithTag(TAG_JOB_OPEN).performScrollTo().performClick()
        rule.waitForIdle()
        // Z2-4: a share still being prepared is dropped first, so no share sheet pops up on the way back.
        assertEquals(listOf("shareHandled", "open $uri"), calls)
        // In-app reader only: no ACTION_VIEW ("Open with" dialog) leaves the app.
        assertNull(shadow.nextStartedActivity)
    }
}
