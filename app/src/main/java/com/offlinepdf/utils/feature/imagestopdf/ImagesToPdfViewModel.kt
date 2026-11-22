package com.offlinepdf.utils.feature.imagestopdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.offlinepdf.utils.R
import com.offlinepdf.utils.core.JobInput
import com.offlinepdf.utils.core.JobViewModel
import com.offlinepdf.utils.core.PickedFile
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.ui.components.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State and actions of the "Images to PDF" tool. */
class ImagesToPdfViewModel(app: Application) : JobViewModel(app) {
    private val _images = MutableStateFlow<List<PickedFile>>(emptyList())

    /** Picked images, in page order. The same image may appear more than once. */
    val images: StateFlow<List<PickedFile>> = _images.asStateFlow()

    private val _pageSize = MutableStateFlow(PageSize.FIT_IMAGE)

    /** Page size for the output; defaults to [PageSize.FIT_IMAGE]. */
    val pageSize: StateFlow<PageSize> = _pageSize.asStateFlow()

    /** Appends [uris] (in order) to the list once they are described. Ignored while a job runs. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || isRunning) return
        viewModelScope.launch {
            val picked = describe(uris)
            _images.update { it + picked }
        }
    }

    fun moveUp(index: Int) = move(index, index - 1)

    fun moveDown(index: Int) = move(index, index + 1)

    fun remove(index: Int) {
        if (isRunning) return
        _images.update { list -> if (index in list.indices) list.toMutableList().apply { removeAt(index) } else list }
    }

    fun setPageSize(size: PageSize) {
        if (isRunning) return
        _pageSize.value = size
    }

    /** Starts the conversion of the current list with the current page size; no-op when empty. */
    fun run() {
        val list = _images.value
        if (list.isEmpty()) return
        val size = _pageSize.value
        val app = getApplication<Application>()
        startJob(
            inputs = list.map { JobInput.Image(it.uri) },
            summarize = { ok ->
                app.getString(
                    R.string.imagestopdf_summary,
                    app.resources.getQuantityString(R.plurals.imagestopdf_pages, ok.pages, ok.pages),
                    formatBytes(ok.bytes),
                )
            },
        ) { engine, files, out, job -> engine.imagesToPdf(files, size, out, job) }
    }

    override fun suggestedFileName(): String = SUGGESTED_FILE_NAME

    private fun move(from: Int, to: Int) {
        if (isRunning) return
        _images.update { list ->
            if (from !in list.indices || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { add(to, removeAt(from)) }
            }
        }
    }

    companion object {
        const val SUGGESTED_FILE_NAME = "images.pdf"
    }
}
