package com.offlinepdf.utils.core

import android.content.Context
import android.net.Uri
import android.os.Looper
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/** Shared helpers for core tests. */
internal object CoreTestSupport {
    /** Registers [bytes] as the (re-readable) content of a fresh `content://` Uri. */
    fun contentUri(context: Context, name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.offlinepdf.utils.test/${System.nanoTime()}/$name")
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
     * instead of `runBlocking` on the test (main) thread when work is dispatched to `Dispatchers.Main`.
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
}
