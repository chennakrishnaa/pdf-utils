package app.venkat.offkit.ui

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
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.hasUnsavedWork
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.launchOrExplain
import app.venkat.offkit.ui.components.rememberPdfPicker
import android.content.ActivityNotFoundException
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import app.venkat.offkit.ui.components.pdfErrorMessage
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    // ---- hasUnsavedWork ----

    @Test
    fun hasUnsavedWorkTruthTable() {
        assertEquals(false, JobUiState.Idle.hasUnsavedWork())
        assertEquals(true, JobUiState.Running(0, 0).hasUnsavedWork())
        val done = JobUiState.Done(1, 10, null, "a.pdf")
        assertEquals(true, done.hasUnsavedWork())
        assertEquals(true, done.copy(saving = true).hasUnsavedWork())
        assertEquals(true, done.copy(saving = true, savedUri = android.net.Uri.parse("content://x/1")).hasUnsavedWork())
        assertEquals(false, done.copy(savedName = "a.pdf", savedUri = android.net.Uri.parse("content://x/1")).hasUnsavedWork())
        assertEquals(false, done.copy(shared = true).hasUnsavedWork())
        assertEquals(false, JobUiState.Failed(app.venkat.offkit.engine.PdfError.Encrypted).hasUnsavedWork())
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
            PdfError.EngineCrashed,
            PdfError.EngineUnavailable,
            PdfError.TimedOut,
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

    @Test
    fun launchOrExplainShowsMessageInsteadOfCrashingWhenNoPickerExists() {
        val msg = rule.activity.getString(R.string.core_no_picker)
        assertEquals("No app on this device can open the file picker.", msg)
        var ran = false
        rule.runOnUiThread {
            assertTrue(launchOrExplain(rule.activity) { ran = true })
            assertTrue(ran)
            assertFalse(launchOrExplain(rule.activity) { throw ActivityNotFoundException("no DocumentsUI") })
        }
        assertEquals(msg, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun pdfPickerWithoutHandlerDoesNotCrash() {
        // Make Robolectric throw ActivityNotFoundException for intents nobody handles (OPEN_DOCUMENT).
        shadowOf(rule.activity.application).checkActivities(true)
        show {
            val pick = rememberPdfPicker(multiple = true) { }
            Button(onClick = pick, modifier = Modifier.testTag("pick")) { Text("Pick") }
        }
        rule.onNodeWithTag("pick").performClick()
        rule.waitForIdle()
        assertEquals(rule.activity.getString(R.string.core_no_picker), ShadowToast.getTextOfLatestToast())
        rule.onNodeWithTag("pick").assertIsDisplayed()
    }
}

