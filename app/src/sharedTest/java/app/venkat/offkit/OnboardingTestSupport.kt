package app.venkat.offkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.ui.onboarding.OnboardingStore
import org.junit.rules.ExternalResource

/**
 * Sets the first-run introduction flag before the activity starts, so `MainActivity` opens on the
 * home screen. Declare it as the outermost rule of a test class:
 *
 *     @get:Rule(order = Int.MIN_VALUE) val onboardingSeen = OnboardingSeenRule()
 *
 * (`createAndroidComposeRule` launches the activity before any `@Before` method runs.)
 */
class OnboardingSeenRule(private val seen: Boolean = true) : ExternalResource() {
    override fun before() = setOnboardingSeen(seen)
}

/** Writes (synchronously) whether the introduction counts as seen; `false` restores a first run. */
fun setOnboardingSeen(seen: Boolean) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences(OnboardingStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
    if (seen) prefs.putBoolean(OnboardingStore.KEY_SEEN, true) else prefs.clear()
    prefs.commit()
}
