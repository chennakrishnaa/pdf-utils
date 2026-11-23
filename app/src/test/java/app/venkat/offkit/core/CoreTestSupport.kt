package app.venkat.offkit.core

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/** Shared helpers for core tests. */
internal object CoreTestSupport {
    /** Registers [bytes] as the (re-readable) content of a fresh `content://` Uri. */
    fun contentUri(context: Context, name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://app.venkat.offkit.test/${System.nanoTime()}/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    /** All regular files below `cacheDir/jobs`. */
    fun jobFiles(context: Context): List<File> =
        File(context.cacheDir, "jobs").walkTopDown().filter { it.isFile }.toList()

    /** True if `cacheDir/jobs` holds nothing (directories included). */
    fun jobsEmpty(context: Context): Boolean =
        File(context.cacheDir, "jobs").listFiles().isNullOrEmpty()

    /**
     * Pumps the Robolectric main looper until [condition] holds or [timeoutMs] passes. Use this
     * instead of `runBlocking` on the test (main) thread when work is dispatched to `Dispatchers.Main`
     * (e.g. `viewModelScope`).
     *
     * It does NOT run coroutines started from a composition (`rememberCoroutineScope`,
     * `LaunchedEffect`, `produceState`): under the v2 Compose test rule those run on a
     * `StandardTestDispatcher` that only the rule's clock advances, so a continuation resuming
     * after an off-main hop (`withContext(Dispatchers.IO)`) stays parked forever. In screen tests
     * use the overload that takes the rule.
     */
    fun idleUntil(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $message")
            Thread.sleep(5)
        }
    }

    /**
     * Like [idleUntil], for Compose screen tests: each poll runs the composition's pending
     * coroutines and advances the test clock by a frame (via [ComposeTestRule.waitUntil]) as
     * well as pumping the main looper, so work resumed on a composition scope after a
     * background hop actually runs. Fails with [message] after [timeoutMs].
     */
    fun idleUntil(
        rule: ComposeTestRule,
        timeoutMs: Long = 5_000,
        message: String = "condition",
        condition: () -> Boolean,
    ) {
        try {
            rule.waitUntil(message, timeoutMs) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: ComposeTimeoutException) {
            fail("Timed out waiting for $message")
        }
    }
}
