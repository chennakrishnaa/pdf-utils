package app.venkat.offkit.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.FileRow
import app.venkat.offkit.ui.components.OffkitBottomSummary
import app.venkat.offkit.ui.components.OffkitChipRow
import app.venkat.offkit.ui.components.OffkitFileCard
import app.venkat.offkit.ui.components.OffkitFileSlot
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitOption
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitRadioGroup
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.OffkitSelectableFileList
import app.venkat.offkit.ui.components.OffkitThumbnailRow
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_ACTION_CLOSE
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_EARLIER
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_LATER
import app.venkat.offkit.ui.components.TAG_THUMB_REMOVE
import app.venkat.offkit.ui.components.fileRowDownTag
import app.venkat.offkit.ui.components.fileRowRemoveTag
import app.venkat.offkit.ui.components.fileRowTag
import app.venkat.offkit.ui.components.fileRowUpTag
import app.venkat.offkit.ui.components.thumbTag
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OffkitComponentsTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private val options = listOf(
        OffkitOption("Low", "Up to 2000 px", "≈ 1 MB", testTag = "opt_0"),
        OffkitOption("Medium", "Up to 1500 px", "≈ 2 MB", testTag = "opt_1"),
        OffkitOption("High", "Up to 1000 px", testTag = "opt_2"),
    )

    // ---- Buttons ----

    @Test
    fun disabledPrimaryButtonShowsItsLabelAndDoesNotClick() {
        var clicks = 0
        show { OffkitPrimaryButton("Choose a PDF first", { clicks++ }, enabled = false, testTag = "primary") }
        rule.onNodeWithTag("primary").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("Choose a PDF first").assertIsDisplayed()
        rule.onNodeWithTag("primary").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun enabledPrimaryButtonClicks() {
        var clicks = 0
        show { OffkitPrimaryButton("Compress", { clicks++ }, testTag = "primary") }
        rule.onNodeWithTag("primary").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    // ---- Radio group and chips ----

    @Test
    fun radioGroupMarksSelectedRowAndReportsClicks() {
        var selected by mutableStateOf(1)
        val picks = mutableListOf<Int>()
        show { OffkitRadioGroup(options, selected, onSelect = { picks += it; selected = it }) }
        rule.onNodeWithTag("opt_1").assertIsSelected().assert(hasRole(Role.RadioButton))
        rule.onNodeWithTag("opt_0").assertIsNotSelected().assertHasClickAction()
        rule.onNode(hasText("≈ 1 MB"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("opt_2").performClick()
        assertEquals(listOf(2), picks)
        rule.onNodeWithTag("opt_2").assertIsSelected()
        rule.onNodeWithTag("opt_1").assertIsNotSelected()
    }

    @Test
    fun chipRowSelectsOneChip() {
        var selected by mutableStateOf(0)
        val picks = mutableListOf<Int>()
        show { OffkitChipRow(options, selected, onSelect = { picks += it; selected = it }) }
        rule.onNodeWithTag("opt_0").assertIsSelected().assert(hasRole(Role.RadioButton))
        rule.onNodeWithTag("opt_1").assertIsNotSelected().performClick()
        assertEquals(listOf(1), picks)
        rule.onNodeWithTag("opt_1").assertIsSelected()
        rule.onNodeWithTag("opt_0").assertIsNotSelected()
    }

    // ---- File slot, card, header ----

    @Test
    fun fileSlotIsOneButtonThatClicks() {
        var clicks = 0
        show { OffkitFileSlot("Choose a PDF", "From Files", onClick = { clicks++ }, testTag = "slot") }
        rule.onNodeWithTag("slot").assert(hasRole(Role.Button)).assert(hasText("Choose a PDF")).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun fileCardTrailingActionClicks() {
        var changes = 0
        show {
            OffkitFileCard(
                name = "report.pdf",
                detail = "1.5 MB",
                actionLabel = "Change",
                onAction = { changes++ },
                actionTestTag = "change",
                testTag = "card",
            )
        }
        rule.onNodeWithTag("card").assertIsDisplayed()
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
        rule.onNodeWithTag("change").assert(hasText("Change")).performClick()
        assertEquals(1, changes)
    }

    @Test
    fun sectionHeaderActionClicks() {
        var adds = 0
        show { OffkitSectionHeader("Images · 2", actionLabel = "Add", onAction = { adds++ }, actionTestTag = "add") }
        rule.onNodeWithText("Images · 2").assertIsDisplayed()
        rule.onNodeWithTag("add").performClick()
        assertEquals(1, adds)
    }

    // ---- Thumbnail row ----

    @Test
    fun thumbnailRowSelectsDeselectsAndReportsToolbarActions() {
        var selected by mutableStateOf<Int?>(null)
        val earlier = mutableListOf<Int>()
        val later = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        show {
            OffkitThumbnailRow(
                count = 4,
                selectedIndex = selected,
                onSelect = { selected = it },
                onMoveEarlier = { earlier += it },
                onMoveLater = { later += it },
                onRemove = { removed += it },
            ) { Box(Modifier.fillMaxSize()) }
        }
        val image2 = str(R.string.cd_image_n, 2)
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()
        rule.onNodeWithTag(thumbTag(1)).assertIsNotSelected().assertContentDescriptionEquals(image2).performClick()
        assertEquals(1, selected)
        rule.onNodeWithTag(thumbTag(1)).assertIsSelected().assertContentDescriptionEquals(str(R.string.cd_selected, image2))
        rule.onNodeWithText(image2).assertIsDisplayed()

        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertIsEnabled().performClick()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsEnabled().performClick()
        rule.onNodeWithTag(TAG_THUMB_REMOVE).performClick()
        assertEquals(listOf(1), earlier)
        assertEquals(listOf(1), later)
        assertEquals(listOf(1), removed)
        rule.onNodeWithContentDescription(str(R.string.move_earlier)).assertExists()
        rule.onNodeWithContentDescription(str(R.string.move_later)).assertExists()
        rule.onNodeWithContentDescription(str(R.string.remove)).assertExists()

        // Tap again: deselects and hides the toolbar.
        rule.onNodeWithTag(thumbTag(1)).performClick()
        assertEquals(null, selected)
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertDoesNotExist()

        // Ends: first tile cannot move earlier, last cannot move later.
        rule.onNodeWithTag(thumbTag(0)).performClick()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsEnabled()
        rule.onNodeWithTag(thumbTag(3)).performScrollTo().performClick()
        assertEquals(3, selected)
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_EARLIER).assertIsEnabled()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).performClick()
        assertEquals(listOf(1), later)
    }

    // ---- Selectable file list ----

    @Test
    fun fileListSelectsAndReportsToolbarActions() {
        val rows = listOf(FileRow("a", "first.pdf", "1 page"), FileRow("b", "second.pdf", "2 pages"), FileRow("c", "third.pdf"))
        var selected by mutableStateOf<Int?>(null)
        val ups = mutableListOf<Int>()
        val downs = mutableListOf<Int>()
        val removes = mutableListOf<Int>()
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OffkitSelectableFileList(
                    rows,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    onMoveUp = { ups += it },
                    onMoveDown = { downs += it },
                    onRemove = { removes += it },
                )
            }
        }
        rule.onNodeWithTag(fileRowUpTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(fileRowTag(1)).assertIsNotSelected().performClick()
        assertEquals(1, selected)
        rule.onNodeWithTag(fileRowTag(1)).assertIsSelected()
            .assertContentDescriptionEquals(str(R.string.cd_selected, "second.pdf") + ", 2 pages")
        rule.onNodeWithTag(fileRowUpTag(1)).assertIsEnabled().performClick()
        rule.onNodeWithTag(fileRowDownTag(1)).assertIsEnabled().performClick()
        rule.onNodeWithTag(fileRowRemoveTag(1)).assert(hasText(str(R.string.remove))).performClick()
        assertEquals(listOf(1), ups)
        assertEquals(listOf(1), downs)
        assertEquals(listOf(1), removes)

        rule.onNodeWithTag(fileRowTag(0)).performClick()
        rule.onNodeWithTag(fileRowUpTag(0)).assertIsNotEnabled()
        rule.onNodeWithTag(fileRowUpTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(fileRowTag(2)).performClick()
        rule.onNodeWithTag(fileRowDownTag(2)).assertIsNotEnabled()
        rule.onNodeWithTag(fileRowTag(2)).performClick()
        assertEquals(null, selected)
        rule.onNodeWithTag(fileRowRemoveTag(2)).assertDoesNotExist()
    }

    // ---- Scaffold ----

    @Test
    fun scaffoldBackAndCloseIconsCallBack() {
        var icon by mutableStateOf(OffkitNavIcon.Back)
        var navigations = 0
        show {
            OffkitScaffold(
                title = "Compress PDF",
                navIcon = icon,
                onNavigate = { navigations++ },
                bottomBar = { OffkitPrimaryButton("Compress", {}, Modifier.fillMaxWidth(), testTag = "primary") },
            ) { OffkitSectionHeader("PDF") }
        }
        rule.onNodeWithText("Compress PDF").assertIsDisplayed()
        rule.onNodeWithTag("primary").assertIsDisplayed()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertContentDescriptionEquals(str(R.string.action_back)).performClick()
        assertEquals(1, navigations)
        rule.onNodeWithTag(TAG_ACTION_CLOSE).assertDoesNotExist()

        icon = OffkitNavIcon.Close
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ACTION_CLOSE).assertContentDescriptionEquals(str(R.string.cd_close)).performClick()
        assertEquals(2, navigations)

        icon = OffkitNavIcon.None
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ACTION_CLOSE).assertDoesNotExist()
        rule.onNodeWithText("Compress PDF").assertIsDisplayed()
    }

    // ---- Large font ----

    @Test
    fun renderAtDoubleFontScaleKeepsPrimaryButtonDisplayed() {
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OffkitScaffold(
                    title = "Images to PDF",
                    navIcon = OffkitNavIcon.Back,
                    onNavigate = {},
                    bottomBar = {
                        OffkitBottomSummary(title = "12 pages", subtitle = "≈ 3.4 MB") { m ->
                            OffkitPrimaryButton("Create PDF", {}, m, testTag = "primary")
                        }
                    },
                ) {
                    OffkitChipRow(options.mapIndexed { i, o -> o.copy(testTag = "chip_$i") }, 0, {})
                    OffkitRadioGroup(options, 1, {})
                }
            }
        }
        rule.onNodeWithTag("primary").assertIsDisplayed()
        rule.onNodeWithText("12 pages", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("opt_2", useUnmergedTree = false).performScrollTo().assertIsDisplayed()
    }
}
