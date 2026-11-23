package app.venkat.offkit

import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real Activity on a device and checks the installed app requests no permissions. */
@RunWith(AndroidJUnit4::class)
class AppLaunchOnDeviceTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mainActivityReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertTrue("application is ${activity.application}", activity.application is PdfUtilApp)
            }
        }
    }

    @Test
    fun installedAppRequestsNoPermissions() {
        @Suppress("DEPRECATION")
        val info = target.packageManager.getPackageInfo(target.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals("app.venkat.offkit", info.packageName)
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue("requested permissions: $requested", requested.isEmpty())
    }
}
