package app.venkat.offkit

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** SB-13: an explicit intent shaped like a nav deep link opens the app normally, with its data dropped. */
    @Test
    fun hostileDeepLinkIntentIsDropped() {
        val hostile = Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/support"))
            .setComponent(ComponentName(target, MainActivity::class.java))
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://evil.provider/doc/1"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Not ActivityScenario: it matches the activity by its intent, which MainActivity replaces.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(hostile)
        try {
            assertTrue(activity is MainActivity)
            assertNull(activity.intent.data)
            assertNull(activity.intent.extras)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    /** SB-6: the launcher activity has an empty task affinity. */
    @Test
    fun mainActivityHasEmptyTaskAffinity() {
        val info = target.packageManager.getActivityInfo(ComponentName(target, MainActivity::class.java), 0)
        assertTrue("taskAffinity is ${info.taskAffinity}", info.taskAffinity.isNullOrEmpty())
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
