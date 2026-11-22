package com.offlinepdf.utils.merge

import android.app.Application
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
import com.offlinepdf.utils.FakePdfEngine
import com.offlinepdf.utils.MainActivity
import com.offlinepdf.utils.PdfUtilApp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.core.CoreTestSupport
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.feature.merge.MergeScreen
import com.offlinepdf.utils.feature.merge.MergeViewModel
import com.offlinepdf.utils.feature.merge.TAG_MERGE_ADD
import com.offlinepdf.utils.feature.merge.TAG_MERGE_NOTE
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.TAG_JOB_CANCEL
import com.offlinepdf.utils.ui.components.TAG_JOB_ERROR
import com.offlinepdf.utils.ui.components.TAG_JOB_PROGRESS
import com.offlinepdf.utils.ui.components.TAG_JOB_RUN
import com.offlinepdf.utils.ui.components.TAG_JOB_SAVE
import com.offlinepdf.utils.ui.components.TAG_JOB_SUMMARY
import com.offlinepdf.utils.ui.components.fileRowDownTag
import com.offlinepdf.utils.ui.components.fileRowRemoveTag
import com.offlinepdf.utils.ui.components.fileRowTag
import com.offlinepdf.utils.ui.components.fileRowUpTag
import com.offlinepdf.utils.ui.nav.AppNavHost
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
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

    /** Contents of the files the engine received, per merge call (read on the engine thread). */
    private val received = CopyOnWriteArrayList<List<String>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
        rule.runOnUiThread { rule.activity.setContent { PdfUtilsTheme { MergeScreen(onBack = {}) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[MergeViewModel::class.java]
    }

    @After
    fun tearDown() {
        fake.beforeStep = null
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
}
