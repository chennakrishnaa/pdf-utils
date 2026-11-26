package app.venkat.offkit.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    fun garbageStoredDataNeverCrashesAndDropsBadEntries() {
        val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
        fun load(json: String): List<RecentFile> {
            prefs.edit().putString("entries", json).commit()
            RecentFilesStore.resetForTesting()
            return RecentFilesStore.get(context).recents.value
        }
        val good = """{"uri":"content://a/1","name":"a.pdf","size":5,"at":7,"tool":"merge"}"""
        // Wrong types: uri as number/object, at as string, size as string, name as array, tool as object.
        val wrongTypes = listOf(
            """{"uri":12,"name":"x","at":1}""",
            """{"uri":{"a":1},"name":"x","at":1}""",
            """{"uri":"content://a/2","name":"x","at":"yesterday"}""",
            """{"uri":"content://a/3","name":"x","at":1,"tool":{"t":1}}""",
            """{"uri":"","name":"x","at":1}""",
            """{"uri":"content://a/${"u".repeat(5000)}","name":"x","at":1}""",
            """{"uri":"content://a/4","name":"x","at":1,"tool":"${"t".repeat(100)}"}""",
            "null", "true", "[]", "\"str\"",
        ).joinToString(",")
        val loaded = load("[$good,$wrongTypes]")
        // A wrongly typed tool id is tolerated (blank); every other bad entry is dropped.
        assertEquals(listOf(Uri.parse("content://a/1"), Uri.parse("content://a/3")), loaded.map { it.uri })
        assertEquals("", loaded[1].tool)

        // Wrong-typed name and size are tolerated: fallback name, unknown size.
        val odd = load("""[{"uri":"content://a/5","name":["x"],"size":"big","at":9}]""").single()
        assertEquals(RecentFile(Uri.parse("content://a/5"), UriIo.FALLBACK_NAME, null, 9, ""), odd)

        // Hostile and absurdly long names are sanitised on load.
        val hostile = load("""[{"uri":"content://a/6","name":"x\u202efdp.exe\n${"n".repeat(10_000)}.pdf","at":1}]""").single()
        assertTrue(hostile.name.length <= MAX_DISPLAY_NAME_LENGTH)
        assertFalse(hostile.name.contains('\u202E') || hostile.name.contains('\n'))
        assertTrue(hostile.name.endsWith(".pdf"))

        // More than 20 entries and duplicate Uris: at most 20, one per Uri, first wins.
        val many = (0 until 50).joinToString(",") { """{"uri":"content://a/${it % 30}","name":"n$it","at":$it}""" }
        val list = load("[$many]")
        assertEquals(RecentFilesStore.MAX_ENTRIES, list.size)
        assertEquals(list.map { it.uri }.distinct(), list.map { it.uri })
        assertEquals("n0", list.first().name)

        // Not an array, truncated, enormous.
        assertTrue(load("""{"uri":"content://a/1"}""").isEmpty())
        assertTrue(load("[$good").isEmpty())
        assertTrue(load("[" + List(20_000) { good }.joinToString(",") + "]").isEmpty())
        assertTrue(load("[".repeat(100_000)).isEmpty())
    }

    @Test
    fun addSanitisesTheName() {
        store.add(RecentFile(uri(1), "evil\u202Efdp.exe\u0000", 1, 1, "merge"))
        assertEquals("evilfdp.exe", store.recents.value.single().name)
    }

    @Test
    fun homeAvailabilityCheckKeepsReadableEntryAndRemovesUnreadableOne() {
        // What home checks before handing a recent file to the in-app reader.
        val readable = RecentFile(CoreTestSupport.contentUri(context, "r.pdf", "%PDF".toByteArray()), "r.pdf", 4, 2, "")
        val gone = RecentFile(Uri.fromFile(File(context.filesDir, "missing.pdf").apply { delete() }), "missing.pdf", 3, 1, "")
        store.add(gone)
        store.add(readable)
        val vm = HomeViewModel(context as Application, Dispatchers.Unconfined)
        assertTrue(runBlocking { vm.checkAvailable(readable) })
        assertEquals(listOf(readable, gone), store.recents.value)
        assertFalse(runBlocking { vm.checkAvailable(gone) })
        assertEquals(listOf(readable), store.recents.value)
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
