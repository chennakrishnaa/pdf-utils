package app.venkat.offkit.ui

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.feature.compress.CompressScreen
import app.venkat.offkit.feature.merge.MergeScreen
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.onboarding.OnboardingScreen
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_INDICATOR
import app.venkat.offkit.ui.onboarding.TAG_ONBOARDING_NEXT
import app.venkat.offkit.ui.theme.OffkitTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controls in the fixed bottom bars (tool screens) and the onboarding bar must have real, on-screen
 * semantics bounds: a zero-size node can be unreachable for TalkBack (on-device `uiautomator dump`
 * once reported `[0,0][0,0]` for them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BottomBarBoundsTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        (ApplicationProvider.getApplicationContext<Application>() as PdfUtilApp).engine = FakePdfEngine()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { OffkitTheme { content() } } }
        rule.waitForIdle()
    }

    private fun assertOnScreen(node: SemanticsNodeInteraction, what: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$what has no width: $b", b.width > 0f)
        assertTrue("$what has no height: $b", b.height > 0f)
        assertTrue("$what is above the screen: $b", b.top >= 0f)
        assertTrue("$what is below the screen (root $root): $b", b.bottom <= root.height)
        assertTrue("$what is outside the screen width: $b", b.left >= 0f && b.right <= root.width)
    }

    private fun text(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

    @Test
    fun compressBottomButtonHasOnScreenBounds() {
        show { CompressScreen(onBack = {}) }
        assertOnScreen(rule.onNodeWithTag(TAG_JOB_RUN), "Compress primary button")
        assertOnScreen(rule.onNodeWithText(text(R.string.choose_pdf_first)), "Compress button label")
    }

    @Test
    fun mergeBottomSummaryAndButtonHaveOnScreenBounds() {
        show { MergeScreen(onBack = {}) }
        assertOnScreen(rule.onNodeWithTag(TAG_JOB_RUN), "Merge primary button")
        assertOnScreen(rule.onNodeWithText(text(R.string.merge_need_two), substring = true), "Merge summary text")
    }

    @Test
    fun onboardingNextAndIndicatorHaveOnScreenBounds() {
        show { OnboardingScreen(onFinished = {}) }
        assertOnScreen(rule.onNodeWithTag(TAG_ONBOARDING_NEXT), "Onboarding Next button")
        assertOnScreen(rule.onNodeWithTag(TAG_ONBOARDING_INDICATOR), "Onboarding page indicator")
    }
}
