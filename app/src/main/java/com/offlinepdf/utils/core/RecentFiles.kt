package com.offlinepdf.utils.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A PDF this app saved, as listed in "recent files".
 *
 * @property uri the document the user chose in the save dialog (a persistable read grant is
 *   held for it while it is listed, when the provider allows).
 * @property name display name for the UI; never used as a path.
 * @property sizeBytes size in bytes, or null if unknown.
 * @property savedAtMillis wall-clock time of the save (`System.currentTimeMillis()`).
 * @property tool id of the tool that produced it (e.g. `"merge"`), or `""`.
 */
data class RecentFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long?,
    val savedAtMillis: Long,
    val tool: String,
)

/**
 * The list of recently saved files, newest first, at most [MAX_ENTRIES], one per Uri.
 * Persisted on the device only (app-private `SharedPreferences` "recent_files", backups are
 * disabled); only Uri, name, size, time and tool id are stored, never contents or paths.
 *
 * This is the only place in the app that persists Uri grants: [add] takes a persistable read
 * grant for the document the app just saved; [remove], eviction and [clear] release it.
 *
 * Obtain the process-wide instance with [get]. All methods are thread-safe and never throw;
 * they do small blocking work (preferences, grants), so prefer calling them off the main thread.
 */
class RecentFilesStore private constructor(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _recents = MutableStateFlow(load())

    /** The recent files, newest first. */
    val recents: StateFlow<List<RecentFile>> = _recents.asStateFlow()

    /**
     * Records [file] at the top of the list (replacing any entry with the same Uri) and takes a
     * persistable read grant on its Uri if the provider allows. Entries beyond [MAX_ENTRIES]
     * are dropped and their grants released.
     */
    fun add(file: RecentFile) {
        try {
            appContext.contentResolver.takePersistableUriPermission(file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // SecurityException if the provider does not offer persistable grants: still record it.
        }
        val evicted: List<RecentFile>
        synchronized(lock) {
            val updated = listOf(file) + _recents.value.filter { it.uri != file.uri }
            evicted = updated.drop(MAX_ENTRIES)
            commit(updated.take(MAX_ENTRIES))
        }
        evicted.forEach { release(it.uri) }
    }

    /** Removes the entry for [uri] (if any) and releases its grant. */
    fun remove(uri: Uri) {
        synchronized(lock) { commit(_recents.value.filter { it.uri != uri }) }
        release(uri)
    }

    /** Removes every entry and releases their grants. */
    fun clear() {
        val old: List<RecentFile>
        synchronized(lock) {
            old = _recents.value
            commit(emptyList())
        }
        old.forEach { release(it.uri) }
    }

    /** An `ACTION_VIEW` intent for [file] (type `application/pdf`, read grant). Start it with an activity context. */
    fun viewIntent(file: RecentFile): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, MIME_PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** A chooser wrapping an `ACTION_SEND` of [file] (`EXTRA_STREAM`, `application/pdf`, read grant). */
    fun shareIntent(file: RecentFile): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_PDF)
            .putExtra(Intent.EXTRA_STREAM, file.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, file.uri)
        return Intent.createChooser(send, null)
    }

    /** True if [file]'s document can still be opened for reading. Blocking; never throws. */
    fun isAvailable(file: RecentFile): Boolean = try {
        appContext.contentResolver.openInputStream(file.uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun release(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Nothing held, or the provider is gone.
        }
    }

    /** Publishes and persists [list]; call with [lock] held. */
    private fun commit(list: List<RecentFile>) {
        _recents.value = list
        try {
            prefs.edit().putString(KEY_ENTRIES, encode(list)).apply()
        } catch (e: Exception) {
            // The in-memory list still works for this process.
        }
    }

    private fun load(): List<RecentFile> = try {
        decode(prefs.getString(KEY_ENTRIES, null))
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Maximum number of entries kept. */
        const val MAX_ENTRIES = 20

        private const val PREFS_NAME = "recent_files"
        private const val KEY_ENTRIES = "entries"
        private const val MIME_PDF = "application/pdf"

        @Volatile
        private var instance: RecentFilesStore? = null

        /** The process-wide store, bound to the application context. */
        fun get(context: Context): RecentFilesStore {
            val app = context.applicationContext ?: context
            instance?.let { if (it.appContext === app) return it }
            return synchronized(this) {
                instance?.takeIf { it.appContext === app } ?: RecentFilesStore(app).also { instance = it }
            }
        }

        /** Forgets the singleton so the next [get] reloads from preferences. */
        @VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) { instance = null }
        }

        internal fun encode(list: List<RecentFile>): String {
            val array = JSONArray()
            list.forEach { f ->
                array.put(
                    JSONObject()
                        .put("uri", f.uri.toString())
                        .put("name", f.name)
                        .put("size", f.sizeBytes ?: JSONObject.NULL)
                        .put("at", f.savedAtMillis)
                        .put("tool", f.tool),
                )
            }
            return array.toString()
        }

        /** Parses stored JSON; malformed entries are skipped, malformed data gives an empty list. */
        internal fun decode(json: String?): List<RecentFile> {
            if (json.isNullOrBlank()) return emptyList()
            val array = try {
                JSONArray(json)
            } catch (e: Exception) {
                return emptyList()
            }
            val out = ArrayList<RecentFile>()
            for (i in 0 until array.length()) {
                try {
                    val o = array.getJSONObject(i)
                    val uri = Uri.parse(o.getString("uri").takeIf { it.isNotBlank() } ?: continue)
                    if (out.any { it.uri == uri }) continue
                    out += RecentFile(
                        uri = uri,
                        name = o.optString("name").ifBlank { UriIo.FALLBACK_NAME },
                        sizeBytes = if (o.isNull("size")) null else o.getLong("size").takeIf { it >= 0 },
                        savedAtMillis = o.getLong("at"),
                        tool = o.optString("tool"),
                    )
                } catch (e: Exception) {
                    // Skip this entry.
                }
            }
            return out.take(MAX_ENTRIES)
        }
    }
}
