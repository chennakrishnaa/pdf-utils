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
import java.io.OutputStream

/** Shared helpers for core tests. */
internal object CoreTestSupport {
    /** Registers [bytes] as the (re-readable) content of a fresh `content://` Uri. */
    fun contentUri(context: Context, name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://app.venkat.offkit.test/${System.nanoTime()}/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    /**
     * A fresh save destination, served by [DestinationProvider], whose writes go to [sink]. When
     * [displayName] is non-null the provider reports it as the Uri's `DISPLAY_NAME` (as a real
     * document provider does); when null the provider reports no row, so no name is known.
     */
    fun saveDestination(context: Context, sink: OutputStream, displayName: String?): Uri {
        org.robolectric.Robolectric.setupContentProvider(DestinationProvider::class.java, DestinationProvider.AUTHORITY)
        val builder = Uri.Builder().scheme("content").authority(DestinationProvider.AUTHORITY)
        if (displayName != null) {
            builder.appendPath(DestinationProvider.NAMED).appendPath("${System.nanoTime()}").appendPath(displayName)
        } else {
            builder.appendPath("doc").appendPath("${System.nanoTime()}")
        }
        val uri = builder.build()
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)
        return uri
    }

    /**
     * Test document provider behind [saveDestination]: for `/named/<id>/<name>` Uris it reports
     * `<name>` as the `DISPLAY_NAME`; for any other Uri it returns an empty cursor.
     */
    class DestinationProvider : android.content.ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): android.database.Cursor {
            val columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            val cursor = android.database.MatrixCursor(columns)
            val segments = uri.pathSegments
            if (segments.size == 3 && segments[0] == NAMED) cursor.addRow(arrayOf<Any?>(segments[2], null))
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri,
            values: android.content.ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            const val AUTHORITY = "savedest.screen.test"
            const val NAMED = "named"
        }
    }

    /** All regular files below `cacheDir/jobs`. */
    fun jobFiles(context: Context): List<File> =
        File(context.cacheDir, "jobs").walkTopDown().filter { it.isFile }.toList()

    /**
     * Forgets `FileProvider`'s static per-authority path cache. Robolectric gives each test a new
     * data directory, so a root cached by an earlier test would not match this test's cacheDir.
     */
    fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        synchronized(field.get(null)!!) { (field.get(null) as MutableMap<*, *>).clear() }
    }

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
