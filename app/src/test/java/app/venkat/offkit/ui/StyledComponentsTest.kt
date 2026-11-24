package app.venkat.offkit.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.ChoiceCard
import app.venkat.offkit.ui.components.ChoiceGroup
import app.venkat.offkit.ui.components.InfoNote
import app.venkat.offkit.ui.components.JobPanel
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.PrimaryActionButton
import app.venkat.offkit.ui.components.SecondaryActionButton
import app.venkat.offkit.ui.components.SelectedFileCard
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests of the shared components added or restyled for the Material 3 look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StyledComponentsTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    @Test
    fun primaryButtonWithIconKeepsTextAndClick() {
        var clicks = 0
        show { PrimaryActionButton("Merge", onClick = { clicks++ }, enabled = true, testTag = "p", icon = AppIcons.merge) }
        rule.onNodeWithTag("p").assertTextEquals("Merge").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun secondaryButtonClicksAndDisables() {
        var clicks = 0
        show {
            androidx.compose.foundation.layout.Column {
                SecondaryActionButton("Add images", onClick = { clicks++ }, testTag = "s1", icon = AppIcons.add)
                SecondaryActionButton("Choose PDF", onClick = { clicks += 10 }, testTag = "s2", enabled = false)
            }
        }
        rule.onNodeWithTag("s1").assertTextEquals("Add images").assertIsEnabled().performClick()
        rule.onNodeWithTag("s2").assertTextEquals("Choose PDF").assertIsNotEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun choiceCardsExposeSelectionAndSwitchOnClick() {
        val picks = mutableListOf<Int>()
        show {
            var selected by remember { mutableIntStateOf(0) }
            ChoiceGroup {
                listOf("Low", "Medium", "High").forEachIndexed { i, title ->
                    ChoiceCard(
                        title = title,
                        selected = selected == i,
                        onClick = { selected = i; picks += i },
                        testTag = "choice_$i",
                        subtitle = "sub $i",
                        trailing = "≈ ${i + 1}.0 MB",
                    )
                }
                ChoiceCard("Off", selected = false, onClick = { picks += 99 }, testTag = "choice_off", enabled = false)
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        rule.onNodeWithTag("choice_0").assertIsSelected().assert(radio)
        rule.onNodeWithTag("choice_1").assertIsNotSelected().assert(radio)
        rule.onNodeWithTag("choice_1").assertTextContains("Medium").assertTextContains("sub 1").assertTextContains("≈ 2.0 MB")
        rule.onNode(hasText("≈ 3.0 MB") and hasAnyAncestor(hasTestTag("choice_2")), useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithTag("choice_2").performClick()
        rule.onNodeWithTag("choice_2").assertIsSelected()
        rule.onNodeWithTag("choice_0").assertIsNotSelected()

        rule.onNodeWithTag("choice_off").assertIsNotEnabled().assertIsNotSelected().performClick()
        assertEquals(listOf(2), picks)
        // The group carries selectableGroup semantics.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
    }

    @Test
    fun infoNoteShowsTextOnTaggedNode() {
        show { InfoNote("Bookmarks are not kept.", testTag = "note") }
        rule.onNodeWithTag("note").assertTextEquals("Bookmarks are not kept.").assertIsDisplayed()
    }

    @Test
    fun selectedFileCardShowsNameAndDetail() {
        show {
            androidx.compose.foundation.layout.Column {
                SelectedFileCard("report.pdf", "1.2 MB", testTag = "file")
                SelectedFileCard("photo.jpg", null, testTag = "file2", icon = AppIcons.image)
            }
        }
        rule.onNodeWithTag("file").assertTextEquals("report.pdf", "1.2 MB")
        rule.onNodeWithTag("file2").assertTextEquals("photo.jpg")
    }

    // ---- JobPanel callbacks in every state after the restyle ----

    private class Calls {
        var run = 0
        var cancel = 0
        var save = 0
        var reset = 0
    }

    private fun showPanel(state: JobUiState): Calls {
        val c = Calls()
        show {
            JobPanel(
                state = state,
                runLabel = "Go",
                runEnabled = true,
                onRun = { c.run++ },
                onCancel = { c.cancel++ },
                onSave = { c.save++ },
                onReset = { c.reset++ },
                runIcon = AppIcons.compress,
            )
        }
        return c
    }

    private fun Calls.counts() = listOf(run, cancel, save, reset)

    @Test
    fun jobPanelIdleRunWithIcon() {
        val c = showPanel(JobUiState.Idle)
        rule.onNodeWithTag("job_run").assertTextEquals("Go").performClick()
        assertEquals(listOf(1, 0, 0, 0), c.counts())
    }

    @Test
    fun jobPanelRunningShowsPercentAndCancels() {
        val c = showPanel(JobUiState.Running(done = 1, total = 4))
        rule.onNode(hasText(rule.activity.getString(R.string.job_percent, 25))).assertIsDisplayed()
        rule.onNodeWithTag("job_cancel").assertTextEquals(rule.activity.getString(R.string.job_cancel)).performClick()
        assertEquals(listOf(0, 1, 0, 0), c.counts())
    }

    @Test
    fun jobPanelReadyToSaveSaveAndDiscard() {
        val c = showPanel(JobUiState.Done(2, 1000, null, "a.pdf"))
        rule.onNodeWithTag("job_summary").assertIsDisplayed()
        rule.onNodeWithTag("job_save").assertTextEquals(rule.activity.getString(R.string.job_save)).performClick()
        rule.onNodeWithTag("job_reset").performClick()
        assertEquals(listOf(0, 0, 1, 1), c.counts())
    }

    @Test
    fun jobPanelSavingHasNoButtons() {
        val c = showPanel(JobUiState.Done(2, 1000, null, "a.pdf", saving = true))
        listOf("job_run", "job_cancel", "job_save", "job_reset").forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        rule.onNodeWithTag("job_saving").assertIsDisplayed()
        assertEquals(listOf(0, 0, 0, 0), c.counts())
    }

    @Test
    fun jobPanelSavedStartOver() {
        val c = showPanel(JobUiState.Done(2, 1000, null, "out.pdf", savedName = "out.pdf", savedUri = android.net.Uri.parse("content://x/1")))
        rule.onNode(hasText(rule.activity.getString(R.string.job_saved_title))).assertIsDisplayed()
        rule.onNodeWithTag("job_reset").performClick()
        assertEquals(listOf(0, 0, 0, 1), c.counts())
    }

    @Test
    fun jobPanelFailedTryAgain() {
        val c = Calls()
        show {
            JobPanel(JobUiState.Failed(app.venkat.offkit.engine.PdfError.Encrypted), "Go", true, { c.run++ }, { c.cancel++ }, { c.save++ }, { c.reset++ })
        }
        rule.onNodeWithTag("job_error").assertTextEquals(rule.activity.getString(R.string.error_encrypted))
        rule.onNodeWithTag("job_reset").assertTextEquals(rule.activity.getString(R.string.job_try_again)).performClick()
        assertEquals(listOf(0, 0, 0, 1), c.counts())
    }
}
