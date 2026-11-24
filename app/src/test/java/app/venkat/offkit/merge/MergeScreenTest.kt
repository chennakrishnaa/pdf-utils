package app.venkat.offkit.merge

import app.venkat.offkit.core.isReadyToSave
import app.venkat.offkit.core.isSaved
import app.venkat.offkit.core.isSavingNow
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.feature.merge.MergeScreen
import app.venkat.offkit.feature.merge.MergeViewModel
import app.venkat.offkit.feature.merge.TAG_MERGE_ADD
import app.venkat.offkit.feature.merge.TAG_MERGE_NOTE
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_ACTION_CLOSE
import app.venkat.offkit.ui.components.TAG_FILE_LIST_EMPTY
import app.venkat.offkit.ui.components.TAG_JOB_CANCEL
import app.venkat.offkit.ui.components.TAG_JOB_ERROR
import app.venkat.offkit.ui.components.TAG_JOB_PROGRESS
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.TAG_JOB_SAVE
import app.venkat.offkit.ui.components.TAG_JOB_SAVING
import app.venkat.offkit.ui.components.TAG_JOB_SUMMARY
import app.venkat.offkit.ui.components.TAG_LEAVE_CONFIRM
import app.venkat.offkit.ui.components.TAG_LEAVE_STAY
import app.venkat.offkit.ui.components.fileRowDownTag
import app.venkat.offkit.ui.components.fileRowRemoveTag
import app.venkat.offkit.ui.components.fileRowTag
import app.venkat.offkit.ui.components.fileRowUpTag
import app.venkat.offkit.ui.components.pdfErrorBody
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MergeScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var vm: MergeViewModel
    private var backCalls = 0

    /** Contents of the files the engine received, per merge call (read on the engine thread). */
    private val received = CopyOnWriteArrayList<List<String>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        resetRecents()
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { MergeScreen(onBack = { backCalls++ }) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[MergeViewModel::class.java]
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
        val uri = Uri.parse("content://dest.test/${System.nanoTime()}/merged.pdf")
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)
        return uri
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun runToReady() {
        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
    }

    /** Asserts the result was discarded: Idle, run button back, nothing in the job cache, save refused. */
    private fun assertResultDiscarded() {
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, sink.size())
    }

    private fun pdf(name: String, content: String = name): Uri =
        CoreTestSupport.contentUri(app, name, content.toByteArray())

    private fun addAll(vararg uris: Uri) {
        val expected = vm.items.value.size + uris.size
        rule.runOnUiThread { vm.add(uris.toList()) }
        CoreTestSupport.idleUntil(message = "$expected items") { vm.items.value.size == expected }
        rule.waitForIdle()
    }

    private fun recordInputs(block: ((step: Int) -> Unit)? = null) {
        fake.beforeStep = { op, step ->
            if (op == FakePdfEngine.Op.MERGE && step == 1) {
                received += fake.callsFor(FakePdfEngine.Op.MERGE).last().pdfs!!.map { it.readText() }
            }
            block?.invoke(step)
        }
    }

    /** Clicks a node in the scrolling content (rows, their toolbar, the header button). */
    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Selects row [index] so that its move / remove toolbar appears. */
    private fun select(index: Int) = click(fileRowTag(index))

    private fun names() = vm.items.value.map { it.file.name }

    private fun pagesText(pages: Int) = app.resources.getQuantityString(R.plurals.pages_count, pages, pages)

    private fun state() = vm.jobState.value


    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_merge")
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_MERGE_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyListShowsTextNoteAndDisabledRun() {
        rule.onNodeWithText(app.getString(R.string.screen_merge)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_section_count, 0)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_hint)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).performScrollTo()
            .assertTextEquals(app.getString(R.string.merge_empty))
        rule.onNodeWithTag(TAG_MERGE_NOTE).performScrollTo().assertIsDisplayed()
            .assertTextEquals(app.getString(R.string.merge_note))
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .assertTextEquals(app.getString(R.string.add_pdfs))
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled().assertTextEquals(app.getString(R.string.action_merge))
        rule.onNodeWithText(app.resources.getQuantityString(R.plurals.files_count, 0, 0)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_need_two)).assertIsDisplayed()
    }

    @Test
    fun runEnabledOnlyWithTwoOrMore() {
        val a = pdf("a.pdf")
        addAll(a)
        rule.onNodeWithTag(fileRowTag(0)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("a.pdf").assertIsDisplayed()
        rule.onNodeWithTag(TAG_FILE_LIST_EMPTY).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsNotEnabled()
        rule.onNodeWithText(app.resources.getQuantityString(R.plurals.files_count, 1, 1)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_need_two)).assertIsDisplayed()

        // The same file twice is allowed and yields two rows.
        addAll(a)
        rule.onNodeWithTag(fileRowTag(1)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_section_count, 2)).assertIsDisplayed()
        rule.onNodeWithText(app.resources.getQuantityString(R.plurals.files_count, 2, 2)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_order_hint)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.merge_need_two)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsEnabled()
        assertEquals("merged.pdf", vm.suggestedFileName())
    }

    @Test
    fun selectingARowShowsItsToolbar() {
        addAll(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"))
        rule.onNodeWithTag(fileRowTag(1)).performScrollTo().assertIsNotSelected()
        rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()

        select(1)
        rule.onNodeWithTag(fileRowTag(1)).assertIsSelected()
        rule.onNodeWithTag(fileRowTag(0)).assertIsNotSelected()
        rule.onNodeWithTag(fileRowUpTag(1)).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(fileRowDownTag(1)).assertIsEnabled()
        rule.onNodeWithTag(fileRowRemoveTag(1)).assertIsEnabled()
        rule.onNodeWithTag(fileRowUpTag(0)).assertDoesNotExist()

        // Tapping the selected row again deselects it.
        select(1)
        rule.onNodeWithTag(fileRowTag(1)).assertIsNotSelected()
        rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
    }

    @Test
    fun firstRowCannotMoveUpAndLastCannotMoveDown() {
        addAll(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"))
        select(0)
        rule.onNodeWithTag(fileRowUpTag(0)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(fileRowDownTag(0)).assertIsEnabled()
        select(2)
        rule.onNodeWithTag(fileRowUpTag(2)).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(fileRowDownTag(2)).assertIsNotEnabled()
    }

    @Test
    fun selectionFollowsMovedRowAndClearsOnRemove() {
        addAll(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"))
        select(0)
        click(fileRowDownTag(0))
        assertEquals(listOf("b.pdf", "a.pdf", "c.pdf"), names())
        rule.onNodeWithTag(fileRowTag(1)).assertIsSelected()
        rule.onNodeWithTag(fileRowTag(0)).assertIsNotSelected()

        click(fileRowDownTag(1))
        assertEquals(listOf("b.pdf", "c.pdf", "a.pdf"), names())
        rule.onNodeWithTag(fileRowTag(2)).assertIsSelected()

        click(fileRowUpTag(2))
        assertEquals(listOf("b.pdf", "a.pdf", "c.pdf"), names())
        rule.onNodeWithTag(fileRowTag(1)).assertIsSelected()

        click(fileRowRemoveTag(1))
        assertEquals(listOf("b.pdf", "c.pdf"), names())
        rule.onNodeWithTag(fileRowTag(0)).assertIsNotSelected()
        rule.onNodeWithTag(fileRowTag(1)).assertIsNotSelected()
        rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(fileRowRemoveTag(1)).assertDoesNotExist()
    }

    @Test
    fun moveAndRemoveChangeEngineOrder() {
        addAll(pdf("a.pdf", "A"), pdf("b.pdf", "B"), pdf("c.pdf", "C"), pdf("d.pdf", "D"))
        recordInputs()

        select(0)
        click(fileRowDownTag(0)) // B A C D
        select(3)
        click(fileRowUpTag(3)) // B A D C
        select(1)
        click(fileRowRemoveTag(1)) // B D C
        assertEquals(listOf("b.pdf", "d.pdf", "c.pdf"), names())
        listOf("b.pdf", "d.pdf", "c.pdf").forEachIndexed { i, name ->
            rule.onNodeWithTag(fileRowTag(i)).performScrollTo().assertTextContains(name)
        }

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }

        assertEquals(1, fake.callsFor(FakePdfEngine.Op.MERGE).size)
        assertEquals(listOf(listOf("B", "D", "C")), received)
    }

    @Test
    fun progressResultSaveAndSaved() {
        addAll(pdf("a.pdf", "A"), pdf("b.pdf", "B"))
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Ok(pages = 5, bytes = 340L * 1024))
        fake.outputContent = "%PDF-1.7\nmerged output\n".toByteArray()
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertIsDisplayed()
        // The options (and so the inputs) are not on screen while the job runs.
        rule.onNodeWithTag(TAG_MERGE_ADD).assertDoesNotExist()
        rule.onNodeWithTag(fileRowTag(0)).assertDoesNotExist()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state().isReadyToSave }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pagesText(5))
        rule.onNodeWithText("340.0 KB").assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()
        assertEquals(listOf(listOf("A", "B")), received)

        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        val savedName = (state() as JobUiState.Done).let { it.savedName ?: it.suggestedName }
        rule.onNodeWithText(app.getString(R.string.result_saved_as, savedName)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).assertTextEquals(pagesText(5))
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(app.getString(R.string.action_done))
    }

    @Test
    fun engineErrorShowsErrorScreenAndTryAgainReturnsToOptions() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo()
            .assertTextEquals(app.getString(pdfErrorBody(PdfError.Encrypted)))
        rule.onNodeWithTag(TAG_MERGE_ADD).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(app.getString(R.string.job_try_again))
        tap(TAG_JOB_RESET)

        assertEquals(JobUiState.Idle, state())
        assertEquals(0, backCalls)
        rule.onNodeWithTag(TAG_JOB_ERROR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(fileRowTag(1)).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("a.pdf", "b.pdf"), names())
    }

    @Test
    fun cancelReturnsToOptionsWithFilesKept() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        tap(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        tap(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        select(1)
        rule.onNodeWithTag(fileRowUpTag(1)).performScrollTo().assertIsEnabled()
        assertEquals(2, vm.items.value.size)
        assertEquals(0, backCalls)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun changingTheListDiscardsReadyResult() {
        addAll(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"))
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))
        // The result screen hides the list; the ViewModel still discards on any change.
        rule.runOnUiThread { vm.moveDown(0) }
        rule.waitForIdle()
        assertResultDiscarded()

        runToReady()
        rule.runOnUiThread { vm.remove(2) }
        rule.waitForIdle()
        assertResultDiscarded()

        runToReady()
        addAll(pdf("d.pdf"))
        assertResultDiscarded()
        assertEquals(3, vm.items.value.size)
    }

    @Test
    fun inputsLockedWhileSaving() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        val release = CountDownLatch(1)
        val sink = BlockingSink(release)
        try {
            rule.runOnUiThread { vm.save(dest(sink)) }
            CoreTestSupport.idleUntil(message = "copy started") { sink.entered.count == 0L }
            rule.waitForIdle()
            assertTrue(state().isSavingNow)
            rule.onNodeWithTag(TAG_JOB_SAVING).assertIsDisplayed().assertIsNotEnabled()
            rule.onNodeWithTag(TAG_MERGE_ADD).assertDoesNotExist()
            rule.onNodeWithTag(fileRowTag(0)).assertDoesNotExist()
            // Leaving is ignored while saving.
            tap(TAG_ACTION_CLOSE)
            rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
            assertEquals(0, backCalls)
            // The ViewModel ignores changes too.
            rule.runOnUiThread {
                vm.moveDown(0)
                vm.remove(1)
                vm.add(listOf(pdf("c.pdf")))
            }
            rule.waitForIdle()
            assertEquals(listOf("a.pdf", "b.pdf"), names())
            assertTrue(state().isSavingNow)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun backFromOptionsLeavesWithoutDialog() {
        tap(TAG_ACTION_BACK)
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
    }

    @Test
    fun discardOnUnsavedResultAsksThenLeaves() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(app.getString(R.string.action_discard))
        tap(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed()
        tap(TAG_LEAVE_STAY)
        assertEquals(0, backCalls)
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsDisplayed()

        // Close and system back ask too.
        tap(TAG_ACTION_CLOSE)
        tap(TAG_LEAVE_STAY)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed()
        tap(TAG_LEAVE_CONFIRM)
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun doneAfterSaveLeavesWithoutDialog() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(app.getString(R.string.action_done))
        tap(TAG_JOB_RESET)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()
        assertEquals(1, backCalls)
        assertEquals(JobUiState.Idle, state())
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state().isSaved }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("merge", recents.single().tool)
        assertEquals(MergeViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveShowsSnackbarAndKeepsResult() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(message = "save finished") { !state().isSavingNow }
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't save. There may not be enough space. Your result is still here, so try another folder.").assertIsDisplayed()
        assertTrue(state().isReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).assertIsEnabled()
    }
}
