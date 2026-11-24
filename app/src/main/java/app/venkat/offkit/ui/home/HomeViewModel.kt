package app.venkat.offkit.ui.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.R
import app.venkat.offkit.core.RecentFile
import app.venkat.offkit.core.RecentFilesStore
import app.venkat.offkit.ui.theme.OffkitTool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * Home screen state: the recent-files list from [RecentFilesStore]. Every store access
 * (loading preferences, availability checks, removals) runs on [io], never on the main thread.
 */
class HomeViewModel(
    app: Application,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {
    private val store = viewModelScope.async(io) { RecentFilesStore.get(app) }

    /** Recent files, newest first; null until the store has loaded. */
    val recents: StateFlow<List<RecentFile>?> =
        flow { emitAll(store.await().recents) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Removes [file] from the list (the document itself is untouched). */
    fun remove(file: RecentFile) {
        viewModelScope.launch(io) { store.await().remove(file.uri) }
    }

    /** Empties the list (the documents themselves are untouched). */
    fun clear() {
        viewModelScope.launch(io) { store.await().clear() }
    }

    /**
     * The intent that opens [file] in a PDF viewer, or null if the document can no longer be
     * read, in which case its entry is removed.
     */
    suspend fun viewIntentIfAvailable(file: RecentFile): Intent? = withContext(io) {
        val s = store.await()
        if (s.isAvailable(file)) {
            s.viewIntent(file)
        } else {
            s.remove(file.uri)
            null
        }
    }

    /** A chooser intent that shares [file]. */
    suspend fun shareIntent(file: RecentFile): Intent = store.await().shareIntent(file)
}

/** Title of the tool with id [toolId] (as recorded in [RecentFile.tool]), or null if unknown. */
@StringRes
internal fun toolTitle(toolId: String): Int? = when (toolId) {
    "images_to_pdf" -> OffkitTool.IMAGES.title
    "compress" -> OffkitTool.COMPRESS.title
    "merge" -> OffkitTool.MERGE.title
    "add_images" -> OffkitTool.ADD_PAGES.title
    else -> null
}

/**
 * When a file was saved, relative to [now]: "Just now" (under a minute, or in the future),
 * "N min ago", "N h ago" (same day), "Yesterday", otherwise the date in the user's format.
 */
internal fun relativeTime(context: Context, then: Long, now: Long): String {
    val diff = now - then
    if (diff < 60_000L) return context.getString(R.string.recent_just_now)
    if (diff < 3_600_000L) return context.getString(R.string.recent_minutes_ago, (diff / 60_000L).toInt())
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (then >= startOfToday.timeInMillis) {
        return context.getString(R.string.recent_hours_ago, (diff / 3_600_000L).toInt())
    }
    val startOfYesterday = (startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (then >= startOfYesterday.timeInMillis) return context.getString(R.string.recent_yesterday)
    return DateFormat.getMediumDateFormat(context).format(Date(then))
}
