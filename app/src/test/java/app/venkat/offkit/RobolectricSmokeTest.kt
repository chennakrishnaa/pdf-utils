package app.venkat.offkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RobolectricSmokeTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsAppName() {
        val appName = composeRule.activity.getString(R.string.app_name)
        composeRule.onNodeWithText(appName).assertIsDisplayed()
    }
}
