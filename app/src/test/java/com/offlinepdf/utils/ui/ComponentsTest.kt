package com.offlinepdf.utils.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import com.offlinepdf.utils.MainActivity
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.ui.components.FileList
import com.offlinepdf.utils.ui.components.FileRow
import com.offlinepdf.utils.ui.components.JobPanel
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.TAG_ACTION_BACK
import com.offlinepdf.utils.ui.components.TAG_LEAVE_CONFIRM
import com.offlinepdf.utils.ui.components.TAG_LEAVE_STAY
import com.offlinepdf.utils.ui.components.ToolScaffold
import com.offlinepdf.utils.ui.components.hasUnsavedWork
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.pdfErrorMessage
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComponentsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { content() } } }
        rule.waitForIdle()
    }

    private val rows = listOf(
        FileRow("a", "first.pdf", "1 page"),
        FileRow("b", "second.pdf"),
        FileRow("c", "third.pdf", "3 pages"),
    )

    // ---- FileList ----

    @Test
    fun fileListRendersRowsInOrderInsideScrollingParent() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FileList(rows, onMoveUp = {}, onMoveDown = {}, onRemove = {}, emptyText = "empty")
            }
        }
        rows.forEachIndexed { i, row ->
            rule.onNode(hasTestTag("row_$i")).assertIsDisplayed()
            rule.onNode(hasText(row.title) and hasAncestorTag("row_$i"), useUnmergedTree = true).assertExists()
        }
        rule.onNode(hasText("1 page"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("row_3").assertDoesNotExist()
        rule.onNodeWithText("empty").assertDoesNotExist()
    }

    @Test
    fun fileListCallbacksFireWithIndexAndEdgesDisabled() {
        val ups = mutableListOf<Int>()
        val downs = mutableListOf<Int>()
        val removes = mutableListOf<Int>()
        show {
            FileList(
                rows,
                onMoveUp = { ups += it },
                onMoveDown = { downs += it },
                onRemove = { removes += it },
                emptyText = "empty",
            )
        }
        rule.onNodeWithTag("row_0_up").assertIsNotEnabled()
        rule.onNodeWithTag("row_1_up").assertIsEnabled()
        rule.onNodeWithTag("row_2_up").assertIsEnabled()
        rule.onNodeWithTag("row_0_down").assertIsEnabled()
        rule.onNodeWithTag("row_1_down").assertIsEnabled()
        rule.onNodeWithTag("row_2_down").assertIsNotEnabled()

        rule.onNodeWithTag("row_2_up").performClick()
        rule.onNodeWithTag("row_1_up").performClick()
        rule.onNodeWithTag("row_0_down").performClick()
        rule.onNodeWithTag("row_1_down").performClick()
        rule.onNodeWithTag("row_1_remove").performClick()
        rule.onNodeWithTag("row_2_remove").performClick()
        // Disabled buttons do not fire.
        rule.onNodeWithTag("row_0_up").performClick()
        rule.onNodeWithTag("row_2_down").performClick()

        assertEquals(listOf(2, 1), ups)
        assertEquals(listOf(0, 1), downs)
        assertEquals(listOf(1, 2), removes)
    }

    @Test
    fun fileListNullCallbacksHideControls() {
        show { FileList(rows, onMoveUp = null, onMoveDown = null, onRemove = {}, emptyText = "empty") }
        rule.onAllNodesWithTag("row_1_up").assertCountEquals(0)
        rule.onAllNodesWithTag("row_1_down").assertCountEquals(0)
        rule.onNodeWithTag("row_1_remove").assertIsDisplayed()
    }

    @Test
    fun fileListEmptyShowsEmptyText() {
        show { FileList(emptyList(), onMoveUp = null, onMoveDown = null, onRemove = null, emptyText = "Nothing yet") }
        rule.onNodeWithText("Nothing yet").assertIsDisplayed()
        rule.onNodeWithTag("row_0").assertDoesNotExist()
    }

    // ---- JobPanel ----

    private class Calls {
        var run = 0
        var cancel = 0
        var save = 0
        var reset = 0
    }

    private fun showPanel(state: JobUiState, runEnabled: Boolean = true): Calls {
        val c = Calls()
        show {
            JobPanel(
                state = state,
                runLabel = "Go",
                runEnabled = runEnabled,
                onRun = { c.run++ },
                onCancel = { c.cancel++ },
                onSave = { c.save++ },
                onReset = { c.reset++ },
            )
        }
        return c
    }

    private fun assertAbsent(vararg tags: String) = tags.forEach { rule.onNodeWithTag(it).assertDoesNotExist() }

    @Test
    fun jobPanelIdle() {
        val c = showPanel(JobUiState.Idle)
        rule.onNodeWithTag("job_run").assertIsEnabled().assertTextEquals("Go").performClick()
        assertAbsent("job_cancel", "job_save", "job_reset", "job_error")
        assertEquals(1, c.run)
    }

    @Test
    fun jobPanelIdleDisabled() {
        val c = showPanel(JobUiState.Idle, runEnabled = false)
        rule.onNodeWithTag("job_run").assertIsNotEnabled().performClick()
        assertEquals(0, c.run)
    }

    @Test
    fun jobPanelRunning() {
        val c = showPanel(JobUiState.Running(done = 2, total = 5))
        rule.onNodeWithTag("job_progress").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.job_progress, 2, 5)).assertIsDisplayed()
        rule.onNodeWithTag("job_cancel").performClick()
        assertAbsent("job_run", "job_save", "job_reset", "job_error")
        assertEquals(1, c.cancel)
    }

    @Test
    fun jobPanelRunningIndeterminate() {
        showPanel(JobUiState.Running(done = 0, total = 0))
        rule.onNodeWithTag("job_progress").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.job_working)).assertIsDisplayed()
        rule.onNodeWithTag("job_cancel").assertIsDisplayed()
    }

    @Test
    fun jobPanelReadyToSave() {
        val c = showPanel(JobUiState.ReadyToSave("3 pages, 1.2 MB"))
        rule.onNodeWithText("3 pages, 1.2 MB").assertIsDisplayed()
        rule.onNodeWithTag("job_save").performClick()
        rule.onNodeWithTag("job_reset").assertTextEquals(rule.activity.getString(R.string.job_discard)).performClick()
        assertAbsent("job_run", "job_cancel", "job_error")
        assertEquals(1, c.save)
        assertEquals(1, c.reset)
    }

    @Test
    fun jobPanelSaving() {
        showPanel(JobUiState.Saving("3 pages, 1.2 MB"))
        rule.onNodeWithText("3 pages, 1.2 MB").assertIsDisplayed()
        rule.onNodeWithTag("job_saving").assertTextEquals(rule.activity.getString(R.string.core_saving))
        assertAbsent("job_run", "job_cancel", "job_save", "job_reset", "job_error")
    }

    @Test
    fun jobPanelSaved() {
        val c = showPanel(JobUiState.Saved("Saved merged.pdf"))
        rule.onNodeWithText("Saved merged.pdf").assertIsDisplayed()
        rule.onNodeWithTag("job_reset").assertTextEquals(rule.activity.getString(R.string.job_start_over)).performClick()
        assertAbsent("job_run", "job_cancel", "job_save", "job_error")
        assertEquals(1, c.reset)
    }

    @Test
    fun jobPanelFailed() {
        val c = showPanel(JobUiState.Failed("Boom"))
        rule.onNodeWithTag("job_error").assertTextEquals("Boom")
        rule.onNodeWithTag("job_reset").assertTextEquals(rule.activity.getString(R.string.job_try_again)).performClick()
        assertAbsent("job_run", "job_cancel", "job_save")
        assertEquals(1, c.reset)
    }

    // ---- hasUnsavedWork / ToolScaffold ----

    @Test
    fun hasUnsavedWorkTruthTable() {
        assertEquals(false, JobUiState.Idle.hasUnsavedWork())
        assertEquals(true, JobUiState.Running(0, 0).hasUnsavedWork())
        assertEquals(true, JobUiState.ReadyToSave("s").hasUnsavedWork())
        assertEquals(true, JobUiState.Saving("s").hasUnsavedWork())
        assertEquals(false, JobUiState.Saved("s").hasUnsavedWork())
        assertEquals(false, JobUiState.Failed("m").hasUnsavedWork())
    }

    private fun showScaffold(confirmLeave: Boolean): IntArray {
        val backs = intArrayOf(0)
        show {
            ToolScaffold(title = "Tool", onBack = { backs[0]++ }, confirmLeave = confirmLeave) {
                androidx.compose.material3.Text("body", modifier = Modifier.testTag("body"))
            }
        }
        return backs
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun toolScaffoldWithoutConfirmCallsOnBackDirectly() {
        val backs = showScaffold(confirmLeave = false)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        assertEquals(1, backs[0])
        assertAbsent(TAG_LEAVE_STAY, TAG_LEAVE_CONFIRM)
    }

    @Test
    fun toolScaffoldConfirmViaBackArrow() {
        val backs = showScaffold(confirmLeave = true)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.core_leave_title)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.core_leave_text)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertTextEquals(rule.activity.getString(R.string.core_leave_stay)).performClick()
        assertAbsent(TAG_LEAVE_STAY, TAG_LEAVE_CONFIRM)
        assertEquals(0, backs[0])
        rule.onNodeWithTag("body").assertIsDisplayed()

        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertTextEquals(rule.activity.getString(R.string.core_leave_confirm)).performClick()
        assertEquals(1, backs[0])
        assertAbsent(TAG_LEAVE_STAY, TAG_LEAVE_CONFIRM)
    }

    @Test
    fun toolScaffoldConfirmViaSystemBack() {
        val backs = showScaffold(confirmLeave = true)
        systemBack()
        rule.onNodeWithTag(TAG_LEAVE_STAY).performClick()
        assertEquals(0, backs[0])
        assertTrue(!rule.activity.isFinishing)
        systemBack()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).performClick()
        assertEquals(1, backs[0])
    }

    // ---- pure helpers ----

    @Test
    fun pdfErrorMessageIsDistinctAndNonEmptyForEveryCase() {
        val all = listOf(
            PdfError.Encrypted,
            PdfError.Corrupt,
            PdfError.UnsupportedImage,
            PdfError.OutOfMemory,
            PdfError.Cancelled,
            PdfError.NotImplemented,
            PdfError.Io(IOException("disk")),
        )
        val texts = all.map { rule.activity.getString(pdfErrorMessage(it)) }
        texts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(all.size, texts.toSet().size)
    }

    @Test
    fun formatBytesValues() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("5.0 MB", formatBytes(5L * 1024 * 1024))
    }
}

private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
