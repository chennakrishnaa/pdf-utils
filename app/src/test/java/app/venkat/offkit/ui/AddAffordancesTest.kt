package app.venkat.offkit.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.FileRow
import app.venkat.offkit.ui.components.OffkitHeaderActionStyle
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.OffkitSelectableFileList
import app.venkat.offkit.ui.components.OffkitThumbnailRow
import app.venkat.offkit.ui.components.TAG_FILE_LIST_ADD
import app.venkat.offkit.ui.components.TAG_THUMB_ADD
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_LATER
import app.venkat.offkit.ui.components.fileRowTag
import app.venkat.offkit.ui.components.thumbTag
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The visible "Add" controls of the list screens (T18): tonal header pill, Add tile, Add row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AddAffordancesTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val notSelectable = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

    @Composable
    private fun Thumbs(count: Int, selected: Int? = null, onAdd: () -> Unit = {}, onSelect: (Int?) -> Unit = {}) {
        OffkitThumbnailRow(
            count = count,
            selectedIndex = selected,
            onSelect = onSelect,
            onMoveEarlier = {},
            onMoveLater = {},
            onRemove = {},
            onAdd = onAdd,
        ) { Box(Modifier.fillMaxSize()) }
    }

    @Test
    fun tonalAndTextHeaderActionsBothClickAndKeepA48dpTarget() {
        var adds = 0
        var clears = 0
        show {
            Column {
                OffkitSectionHeader("Images · 2", actionLabel = "Add", onAction = { adds++ }, actionTestTag = "add", actionStyle = OffkitHeaderActionStyle.Tonal)
                OffkitSectionHeader("Recent", actionLabel = "Clear", onAction = { clears++ }, actionTestTag = "clear")
            }
        }
        for (tag in listOf("add", "clear")) {
            val b = rule.onNodeWithTag(tag).assertIsDisplayed().assert(isButton).getBoundsInRoot()
            assertTrue("$tag height ${b.bottom - b.top}", b.bottom - b.top >= 48.dp)
        }
        rule.onNodeWithTag("add").assertTextEquals("Add").performClick()
        rule.onNodeWithTag("clear").assertTextEquals("Clear").performClick()
        assertEquals(1, adds)
        assertEquals(1, clears)
    }

    @Test
    fun addTileFollowsTheImagesIsOneButtonAndIsNotAnImage() {
        var adds = 0
        var selected by mutableStateOf<Int?>(null)
        show { Thumbs(3, selected, onAdd = { adds++ }, onSelect = { selected = it }) }
        rule.onNodeWithTag(thumbTag(3)).assertDoesNotExist()
        val tile = rule.onNodeWithTag(TAG_THUMB_ADD).performScrollTo().assertIsDisplayed()
            .assert(isButton).assert(notSelectable).assertHasClickAction().assertTextEquals(str(R.string.add))
        val last = rule.onNodeWithTag(thumbTag(2)).getBoundsInRoot()
        val t = tile.getBoundsInRoot()
        assertTrue("tile after last image", t.left >= last.right)
        assertEquals(96.dp.value, (t.right - t.left).value, 0.5f)
        assertEquals(128.dp.value, (t.bottom - t.top).value, 0.5f)
        // No page badge "4" anywhere.
        rule.onNodeWithText("4", useUnmergedTree = true).assertDoesNotExist()
        tile.performClick()
        assertEquals(1, adds)
        assertEquals(null, selected)
        // The last image is still the last one for reordering.
        rule.onNodeWithTag(thumbTag(2)).performScrollTo().performClick()
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).assertIsNotEnabled()
    }

    @Test
    fun addTileAloneWhenThereAreNoImages() {
        var adds = 0
        show { Thumbs(0, onAdd = { adds++ }) }
        rule.onNodeWithTag(thumbTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_THUMB_ADD).assertIsDisplayed().performClick()
        assertEquals(1, adds)
    }

    @Test
    fun rowScrollsToTheAddTileWhenImagesAreAddedButNotOnRemove() {
        var count by mutableIntStateOf(2)
        show { Box(Modifier.width(300.dp)) { Thumbs(count) } }
        rule.onNodeWithTag(TAG_THUMB_ADD).assertIsDisplayed()
        count = 8
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_THUMB_ADD).assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(7)).assertIsDisplayed()
        rule.onNodeWithTag(thumbTag(0)).assertIsNotDisplayed()
        // Back to the start, then remove one: the row stays where it is.
        rule.onNodeWithTag(thumbTag(0)).performScrollTo()
        count = 7
        rule.waitForIdle()
        rule.onNodeWithTag(thumbTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(TAG_THUMB_ADD).assertIsNotDisplayed()
    }

    @Test
    fun addTileLabelIsNotClippedAtDoubleFontScale() {
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { Thumbs(1) }
        }
        val tile = rule.onNodeWithTag(TAG_THUMB_ADD).assertIsDisplayed().assertTextEquals(str(R.string.add)).getBoundsInRoot()
        val label = rule.onNodeWithText(str(R.string.add), useUnmergedTree = true).getBoundsInRoot()
        assertTrue("label inside tile: $label in $tile", label.bottom <= tile.bottom && label.top >= tile.top)
        assertTrue(tile.bottom - tile.top >= 128.dp)
    }

    @Test
    fun fileListAddRowFollowsTheRowsAndClicks() {
        var adds = 0
        show {
            OffkitSelectableFileList(
                rows = listOf(FileRow("a", "a.pdf", "1 MB"), FileRow("b", "b.pdf", "2 MB")),
                selectedIndex = null,
                onSelect = {},
                onMoveUp = {},
                onMoveDown = {},
                onRemove = {},
                onAdd = { adds++ },
            )
        }
        rule.onNodeWithTag(fileRowTag(2)).assertDoesNotExist()
        val row = rule.onNodeWithTag(TAG_FILE_LIST_ADD).assertIsDisplayed()
            .assert(isButton).assert(notSelectable).assertTextEquals(str(R.string.add_pdfs))
        val b = row.getBoundsInRoot()
        assertTrue(b.top >= rule.onNodeWithTag(fileRowTag(1)).getBoundsInRoot().bottom)
        assertTrue(b.bottom - b.top >= 72.dp)
        row.performClick()
        assertEquals(1, adds)
    }

    @Test
    fun fileListAddRowAloneWhenEmptyAndBroughtIntoViewWhenFilesAreAdded() {
        var rows by mutableStateOf(emptyList<FileRow>())
        show {
            Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(300.dp))
                OffkitSelectableFileList(rows, null, {}, {}, {}, {}, onAdd = {})
            }
        }
        rule.onNodeWithTag(TAG_FILE_LIST_ADD).assertExists()
        rows = (1..4).map { FileRow("$it", "$it.pdf", null) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_FILE_LIST_ADD).assertIsDisplayed()
    }
}
