package com.offlinepdf.utils.feature.merge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.offlinepdf.utils.R
import com.offlinepdf.utils.core.JobInput
import com.offlinepdf.utils.core.JobViewModel
import com.offlinepdf.utils.core.PickedFile
import com.offlinepdf.utils.ui.components.formatBytes
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

    /** Appends [uris] (in picker order) after describing them off the main thread. Ignored while a job runs. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || isRunning) return
        viewModelScope.launch {
            val picked = describe(uris)
            _items.update { current -> current + picked.map { MergeItem(nextKey++, it) } }
        }
    }

    fun moveUp(index: Int) = swap(index, index - 1)

    fun moveDown(index: Int) = swap(index, index + 1)

    fun remove(index: Int) {
        if (isRunning) return
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

    private fun swap(from: Int, to: Int) {
        if (isRunning) return
        _items.update { list ->
            if (from !in list.indices || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { this[from] = this[to].also { this[to] = this[from] } }
            }
        }
    }

    private companion object {
        const val MIN_INPUTS = 2
    }
}
