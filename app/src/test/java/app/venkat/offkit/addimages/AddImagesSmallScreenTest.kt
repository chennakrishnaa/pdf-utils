package app.venkat.offkit.addimages

import android.app.Application
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.feature.addimages.AddImagesScreen
import app.venkat.offkit.feature.addimages.AddImagesViewModel
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.TAG_THUMB_MOVE_LATER
import app.venkat.offkit.ui.components.TAG_THUMB_REMOVE
import app.venkat.offkit.ui.components.thumbTag
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add images on a small screen (320 x 480 dp): after scrolling, the thumbnail tiles and the
 * selection toolbar must sit fully above the fixed bottom bar and take taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h480dp")
class AddImagesSmallScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var vm: AddImagesViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        (app as PdfUtilApp).engine = FakePdfEngine()
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { AddImagesScreen(onBack = {}) } } }
        rule.waitForIdle()
        vm = ViewModelProvider(rule.activity)[AddImagesViewModel::class.java]
    }

    private fun content(name: String, bytes: ByteArray): Uri = CoreTestSupport.contentUri(app, name, bytes)

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    /** Top of the bottom bar in dp: the run button's top minus the bar's top padding and 1dp border. */
    private fun bottomBarTop(): Float =
        bounds(rule.onNodeWithTag(TAG_JOB_RUN)).top - (OffkitDimens.bottomBarPaddingTop + Dp(1f)).value

    /**
     * Brings [tag] fully into view in both scrollers and checks it ends above the bottom bar.
     * `performScrollTo()` only scrolls the closest scrollable parent, which for a tile is the
     * horizontal thumbnail row, so the screen column is then scrolled by the overflow with a
     * `ScrollBy` action, as a user's swipe would.
     */
    private fun assertAboveBottomBar(tag: String) {
        val node = rule.onNodeWithTag(tag).performScrollTo()
        rule.waitForIdle()
        val column = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasTestTag(tag)),
        )
        val viewportBottom = column.fetchSemanticsNode().boundsInRoot.bottom / rule.density.density
        val overflow = bounds(node).bottom - viewportBottom
        if (overflow > 0f) {
            column.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, overflow * rule.density.density) }
            rule.waitForIdle()
        }
        val b = bounds(node)
        val barTop = bottomBarTop()
        assertTrue("$tag bottom ${b.bottom} is not above the bottom bar at $barTop", b.bottom <= barTop + 0.5f)
        node.assertIsDisplayed()
    }

    @Test
    fun tilesAndToolbarAreAboveTheBottomBarAndClickable() {
        rule.runOnUiThread { vm.choosePdf(listOf(content("doc.pdf", "%PDF-1.7 doc".toByteArray()))) }
        CoreTestSupport.idleUntil(message = "pdf") { vm.pdf.value != null }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        rule.runOnUiThread { vm.addImages((1..3).map { content("i$it.jpg", jpeg + "i$it".toByteArray()) }) }
        CoreTestSupport.idleUntil(message = "3 images") { vm.images.value.size == 3 }
        rule.waitForIdle()

        assertAboveBottomBar(thumbTag(2))
        rule.onNodeWithTag(thumbTag(2)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(thumbTag(2)).assertIsSelected()

        assertAboveBottomBar(TAG_THUMB_REMOVE)
        rule.onNodeWithTag(TAG_THUMB_REMOVE).assertIsEnabled()
        assertAboveBottomBar(thumbTag(0))
        rule.onNodeWithTag(thumbTag(0)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(thumbTag(0)).assertIsSelected()
        assertAboveBottomBar(TAG_THUMB_MOVE_LATER)
        val before = vm.images.value
        rule.onNodeWithTag(TAG_THUMB_MOVE_LATER).performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(listOf(before[1], before[0], before[2]), vm.images.value)
    }
}
