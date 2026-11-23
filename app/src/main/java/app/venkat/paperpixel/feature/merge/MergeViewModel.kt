package app.venkat.paperpixel.feature.merge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.venkat.paperpixel.R
import app.venkat.paperpixel.core.JobInput
import app.venkat.paperpixel.core.JobViewModel
import app.venkat.paperpixel.core.PickedFile
import app.venkat.paperpixel.ui.components.JobUiState
import app.venkat.paperpixel.ui.components.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One PDF in the merge list; [key] is unique and stable across moves (the same file may appear twice). */
data class MergeItem(val key: Long, val file: PickedFile)

/** State and actions of the "Merge PDFs" screen. */
class MergeViewModel(app: Application) : JobViewModel(app) {
    private val _items = MutableStateFlow<List<MergeItem>>(emptyList())

    /** The PDFs to merge, in output order. */
    val items: StateFlow<List<MergeItem>> = _items.asStateFlow()

    private var nextKey = 0L

    /** True when the list holds enough PDFs to merge (two or more). */
    val canMerge: Boolean get() = _items.value.size >= MIN_INPUTS

    override val toolId: String = TOOL_ID

    /** True while the list must not change: a job is running or its result is being saved. */
    private val locked: Boolean get() = isRunning || jobState.value is JobUiState.Saving

    /**
     * Appends [uris] (in picker order) after describing them off the main thread. Discards any
     * result of the previous list. Ignored while running or saving.
     */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || locked) return
        viewModelScope.launch {
            val picked = describe(uris)
            if (locked) return@launch
            inputsChanged()
            _items.update { current -> current + picked.map { MergeItem(nextKey++, it) } }
        }
    }

    fun moveUp(index: Int) = swap(index, index - 1)

    fun moveDown(index: Int) = swap(index, index + 1)

    /** Removes the PDF at [index]; discards any result. Ignored while running or saving. */
    fun remove(index: Int) {
        if (locked || index !in _items.value.indices) return
        inputsChanged()
        _items.update { list -> if (index in list.indices) list.toMutableList().apply { removeAt(index) } else list }
    }

    /** Starts merging the current list, in order. No-op with fewer than two PDFs or while running. */
    fun merge() {
        val list = _items.value
        if (list.size < MIN_INPUTS) return
        val res = getApplication<Application>().resources
        startJob(
            inputs = list.map { JobInput.Pdf(it.file.uri) },
            summarize = { ok -> res.getQuantityString(R.plurals.merge_summary, ok.pages, ok.pages, formatBytes(ok.bytes)) },
        ) { engine, files, out, job -> engine.merge(files, out, job) }
    }

    override fun suggestedFileName(): String = getApplication<Application>().getString(R.string.merge_file_name)

    /** The result no longer matches the list: drop it (and any save error) so it cannot be saved. */
    private fun inputsChanged() {
        invalidateResult()
        clearSaveError()
    }

    private fun swap(from: Int, to: Int) {
        if (locked) return
        val list = _items.value
        if (from !in list.indices || to !in list.indices) return
        inputsChanged()
        _items.update { list ->
            if (from !in list.indices || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { this[from] = this[to].also { this[to] = this[from] } }
            }
        }
    }

    companion object {
        /** Id recorded with files saved from this screen in the recent-files list. */
        const val TOOL_ID = "merge"
        private const val MIN_INPUTS = 2
    }
}
