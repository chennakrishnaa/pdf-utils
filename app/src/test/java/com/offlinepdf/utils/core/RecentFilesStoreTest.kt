package com.offlinepdf.utils.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecentFilesStoreTest {
    private lateinit var context: Context
    private lateinit var store: RecentFilesStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecentFilesStore.resetForTesting()
        store = RecentFilesStore.get(context)
    }

    @After
    fun tearDown() {
        RecentFilesStore.resetForTesting()
    }

    private fun uri(i: Int) = Uri.parse("content://com.example.docs/document/doc$i")

    private fun file(i: Int, at: Long = i.toLong()) = RecentFile(uri(i), "doc$i.pdf", 100L + i, at, "merge")

    private fun granted(): Set<Uri> = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()

    @Test
    fun startsEmptyAndIsASingleton() {
        assertTrue(store.recents.value.isEmpty())
        assertTrue(store === RecentFilesStore.get(context))
    }

    @Test
    fun addPutsNewestFirstAndTakesGrant() {
        store.add(file(1))
        store.add(file(2))
        assertEquals(listOf(uri(2), uri(1)), store.recents.value.map { it.uri })
        assertEquals(file(2), store.recents.value.first())
        assertEquals(setOf(uri(1), uri(2)), granted())
    }

    @Test
    fun reAddingSameUriMovesItToTopWithoutDuplicate() {
        store.add(file(1))
        store.add(file(2))
        val again = file(1, at = 99).copy(name = "renamed.pdf")
        store.add(again)
        assertEquals(listOf(again, file(2)), store.recents.value)
        assertTrue(uri(1) in granted())
    }

    @Test
    fun capsAtTwentyAndReleasesEvictedGrant() {
        for (i in 0..20) store.add(file(i))
        val list = store.recents.value
        assertEquals(RecentFilesStore.MAX_ENTRIES, list.size)
        assertEquals(uri(20), list.first().uri)
        assertEquals(uri(1), list.last().uri)
        assertFalse(list.any { it.uri == uri(0) })
        assertFalse(uri(0) in granted())
        assertTrue(uri(1) in granted())
    }

    @Test
    fun removeDropsEntryAndReleasesGrant() {
        store.add(file(1))
        store.add(file(2))
        store.remove(uri(1))
        assertEquals(listOf(uri(2)), store.recents.value.map { it.uri })
        assertEquals(setOf(uri(2)), granted())
        store.remove(uri(42)) // unknown: no-op
        assertEquals(1, store.recents.value.size)
    }

    @Test
    fun clearEmptiesAndReleasesAll() {
        store.add(file(1))
        store.add(file(2))
        store.clear()
        assertTrue(store.recents.value.isEmpty())
        assertTrue(granted().isEmpty())
        RecentFilesStore.resetForTesting()
        assertTrue(RecentFilesStore.get(context).recents.value.isEmpty())
    }

    @Test
    fun persistsAcrossInstances() {
        store.add(file(1))
        store.add(file(2).copy(sizeBytes = null, tool = ""))
        RecentFilesStore.resetForTesting()
        val reloaded = RecentFilesStore.get(context)
        assertTrue(reloaded !== store)
        assertEquals(store.recents.value, reloaded.recents.value)
        assertNull(reloaded.recents.value.first().sizeBytes)
    }

    @Test
    fun corruptStoredDataIsEmpty() {
        val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", "{not json").commit()
        RecentFilesStore.resetForTesting()
        assertTrue(RecentFilesStore.get(context).recents.value.isEmpty())

        prefs.edit().putString(
            "entries",
            """[{"uri":"content://a/1","name":"a.pdf","size":5,"at":7,"tool":"merge"},{"bogus":1},3]""",
        ).commit()
        RecentFilesStore.resetForTesting()
        val list = RecentFilesStore.get(context).recents.value
        assertEquals(listOf(RecentFile(Uri.parse("content://a/1"), "a.pdf", 5, 7, "merge")), list)

        prefs.edit().putInt("entries", 3).commit()
        RecentFilesStore.resetForTesting()
        assertTrue(RecentFilesStore.get(context).recents.value.isEmpty())
    }

    @Test
    fun viewIntentContents() {
        val intent = store.viewIntent(file(1))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri(1), intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun shareIntentContents() {
        val chooser = store.shareIntent(file(1))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uri(1), send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri(1), send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun isAvailableTrueForReadableFalseOtherwise() {
        val readable = CoreTestSupport.contentUri(context, "r.pdf", "%PDF".toByteArray())
        assertTrue(store.isAvailable(RecentFile(readable, "r.pdf", 4, 1, "")))
        val f = File(context.filesDir, "gone.pdf").apply { writeBytes(ByteArray(3)) }
        val rf = RecentFile(Uri.fromFile(f), "gone.pdf", 3, 1, "")
        assertTrue(store.isAvailable(rf))
        f.delete()
        assertFalse(store.isAvailable(rf))
        // (Robolectric serves a placeholder stream for unregistered content Uris, so the
        // negative case is exercised with a deleted file.)
    }
}
