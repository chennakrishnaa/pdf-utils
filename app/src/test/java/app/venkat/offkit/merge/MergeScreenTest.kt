package app.venkat.offkit.merge

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.venkat.offkit.feature.merge.TAG_MERGE_SAVE_ERROR
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
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
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.PdfUtilsTheme
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
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { MergeScreen(onBack = { backCalls++ }) } } }
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

    private fun back() {
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
    }

    private fun runToReady() {
        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
    }

    /** Asserts the result was discarded: Idle, run button back, nothing in the job cache, save refused. */
    private fun assertResultDiscarded() {
        assertEquals(JobUiState.Idle, state())
        assertTrue(CoreTestSupport.jobsEmpty(app))
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SAVE).assertDoesNotExist()
        val sink = ByteArrayOutputStream()
        rule.runOnUiThread { vm.save(dest(sink)) }
        rule.waitForIdle()
        assertEquals(JobUiState.Idle, state())
        assertEquals(0, sink.size())
        assertEquals(app.getString(R.string.core_result_unavailable), vm.saveError.value)
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

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun state() = vm.jobState.value

    @Test
    fun opensFromHomeThroughNavigation() {
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { AppNavHost() } } }
        rule.waitForIdle()
        click("tool_merge")
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_MERGE_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun runEnabledOnlyWithTwoOrMoreAndNoteShown() {
        rule.onNodeWithTag(TAG_MERGE_NOTE).performScrollTo().assertIsDisplayed()
            .assertTextEquals(app.getString(R.string.merge_note))
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()

        val a = pdf("a.pdf")
        addAll(a)
        rule.onNodeWithTag(fileRowTag(0)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("a.pdf").assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsNotEnabled()

        // The same file twice is allowed and yields two rows.
        addAll(a)
        rule.onNodeWithTag(fileRowTag(1)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsEnabled()
        assertEquals("merged.pdf", vm.suggestedFileName())
    }

    @Test
    fun moveAndRemoveChangeEngineOrder() {
        addAll(pdf("a.pdf", "A"), pdf("b.pdf", "B"), pdf("c.pdf", "C"), pdf("d.pdf", "D"))
        recordInputs()

        click(fileRowDownTag(0)) // B A C D
        click(fileRowUpTag(3)) // B A D C
        click(fileRowRemoveTag(1)) // B D C
        assertEquals(listOf("b.pdf", "d.pdf", "c.pdf"), vm.items.value.map { it.file.name })

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }

        assertEquals(1, fake.callsFor(FakePdfEngine.Op.MERGE).size)
        assertEquals(listOf(listOf("B", "D", "C")), received)
    }

    @Test
    fun progressSummarySaveAndSaved() {
        addAll(pdf("a.pdf", "A"), pdf("b.pdf", "B"))
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Ok(pages = 5, bytes = 340L * 1024))
        fake.outputContent = "%PDF-1.7\nmerged output\n".toByteArray()
        val atStep2 = CountDownLatch(1)
        val release = CountDownLatch(1)
        recordInputs { step ->
            if (step == 2) {
                atStep2.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_JOB_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.job_progress, 1, 3)).assertIsDisplayed()
        // Inputs cannot be edited while the job runs.
        rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsNotEnabled()

        release.countDown()
        CoreTestSupport.idleUntil(message = "ReadyToSave") { state() is JobUiState.ReadyToSave }
        rule.waitForIdle()
        val summary = app.resources.getQuantityString(R.plurals.merge_summary, 5, 5, "340.0 KB")
        assertEquals("5 pages · 340.0 KB", summary)
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()
        assertEquals(listOf(listOf("A", "B")), received)

        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/${System.nanoTime()}/merged.pdf")
        shadowOf(app.contentResolver).registerOutputStream(dest, sink)
        rule.runOnUiThread { vm.save(dest) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()

        assertArrayEquals(fake.outputContent, sink.toByteArray())
        rule.onNodeWithText(app.getString(R.string.job_saved_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_SUMMARY).performScrollTo().assertTextEquals(summary)
    }

    @Test
    fun encryptedErrorShowsMessage() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        fake.setResult(FakePdfEngine.Op.MERGE, PdfResult.Err(PdfError.Encrypted))

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Failed") { state() is JobUiState.Failed }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_ERROR).performScrollTo()
            .assertTextEquals(app.getString(R.string.error_encrypted))
    }

    @Test
    fun cancelReturnsToIdle() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        val release = CountDownLatch(1)
        recordInputs { step -> if (step == 2) release.await(10, TimeUnit.SECONDS) }

        click(TAG_JOB_RUN)
        CoreTestSupport.idleUntil(message = "Running(1, 3)") { state() == JobUiState.Running(1, 3) }
        rule.waitForIdle()
        click(TAG_JOB_CANCEL)
        release.countDown()
        CoreTestSupport.idleUntil(message = "Idle") { state() == JobUiState.Idle && !vm.isRunning }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_JOB_RUN).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(TAG_JOB_CANCEL).assertDoesNotExist()
        rule.onNodeWithTag(fileRowUpTag(1)).performScrollTo().assertIsEnabled()
        assertEquals(2, vm.items.value.size)
        assertTrue(CoreTestSupport.jobsEmpty(app))
    }

    @Test
    fun changingTheListDiscardsReadyResult() {
        addAll(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"))
        runToReady()
        assertFalse(CoreTestSupport.jobsEmpty(app))
        click(fileRowDownTag(0))
        assertResultDiscarded()

        runToReady()
        click(fileRowRemoveTag(2))
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
            assertTrue(state() is JobUiState.Saving)
            rule.onNodeWithTag(TAG_JOB_SAVING).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
            rule.onNodeWithTag(fileRowDownTag(0)).assertDoesNotExist()
            rule.onNodeWithTag(fileRowRemoveTag(0)).assertDoesNotExist()
            rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsNotEnabled()
            // The ViewModel ignores changes too.
            rule.runOnUiThread {
                vm.moveDown(0)
                vm.remove(1)
                vm.add(listOf(pdf("c.pdf")))
            }
            rule.waitForIdle()
            assertEquals(listOf("a.pdf", "b.pdf"), vm.items.value.map { it.file.name })
            assertTrue(state() is JobUiState.Saving)
        } finally {
            release.countDown()
        }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MERGE_ADD).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(fileRowRemoveTag(0)).performScrollTo().assertIsEnabled()
        assertArrayEquals(fake.outputContent, sink.bytes.toByteArray())
    }

    @Test
    fun backWithResultAsksBeforeLeaving() {
        back()
        assertEquals(1, backCalls)
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertDoesNotExist()

        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        back()
        rule.onNodeWithTag(TAG_LEAVE_STAY).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
        assertTrue(state() is JobUiState.ReadyToSave)
        rule.onNodeWithTag(TAG_JOB_SAVE).performScrollTo().assertIsDisplayed()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LEAVE_CONFIRM).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(2, backCalls)
    }

    @Test
    fun successfulSaveIsRecordedAsRecent() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        CoreTestSupport.idleUntil(message = "Saved") { state() is JobUiState.Saved }
        val recents = RecentFilesStore.get(app).recents.value
        assertEquals(1, recents.size)
        assertEquals("merge", recents.single().tool)
        assertEquals(MergeViewModel.TOOL_ID, vm.toolId)
    }

    @Test
    fun failedSaveErrorStaysUntilNextAction() {
        addAll(pdf("a.pdf"), pdf("b.pdf"))
        runToReady()
        rule.runOnUiThread { vm.save(dest(FailingSink)) }
        CoreTestSupport.idleUntil(message = "saveError") { vm.saveError.value != null }
        rule.waitForIdle()
        val failed = app.getString(R.string.core_save_failed)
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).performScrollTo().assertTextEquals(failed)
        assertTrue(state() is JobUiState.ReadyToSave)

        // Changing the list (the next action) clears it along with the result.
        click(fileRowDownTag(0))
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).assertDoesNotExist()

        // "Result no longer available" arrives in Idle and stays visible.
        rule.runOnUiThread { vm.save(dest(ByteArrayOutputStream())) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).performScrollTo()
            .assertTextEquals(app.getString(R.string.core_result_unavailable))
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).assertExists()
        runToReady()
        rule.onNodeWithTag(TAG_MERGE_SAVE_ERROR).assertDoesNotExist()
        click(TAG_JOB_RESET)
        assertEquals(JobUiState.Idle, state())
    }
}
