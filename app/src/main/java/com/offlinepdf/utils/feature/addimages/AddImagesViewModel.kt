package com.offlinepdf.utils.feature.addimages

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

/** One image in the list; [key] is unique and stable across moves (the same image may appear twice). */
data class AddImagesItem(val key: Long, val file: PickedFile)

/** State and actions of the "Add images to PDF" screen. */
class AddImagesViewModel(app: Application) : JobViewModel(app) {
    private val _pdf = MutableStateFlow<PickedFile?>(null)

    /** The PDF the images are appended to, or null when none is chosen yet. */
    val pdf: StateFlow<PickedFile?> = _pdf.asStateFlow()

    private val _images = MutableStateFlow<List<AddImagesItem>>(emptyList())

    /** The images to append, in page order. */
    val images: StateFlow<List<AddImagesItem>> = _images.asStateFlow()

    private var nextKey = 0L

    /** True when a PDF and at least one image are chosen. */
    val canRun: Boolean get() = _pdf.value != null && _images.value.isNotEmpty()

    /** Sets the PDF to the first of [uris], replacing any earlier choice. Ignored while a job runs. */
    fun choosePdf(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        if (isRunning) return
        viewModelScope.launch {
            val picked = describe(listOf(uri)).first()
            if (!isRunning) _pdf.value = picked
        }
    }

    /** Appends [uris] (in picker order) to the image list. Ignored while a job runs. */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty() || isRunning) return
        viewModelScope.launch {
            val picked = describe(uris)
            _images.update { current -> current + picked.map { AddImagesItem(nextKey++, it) } }
        }
    }

    fun moveUp(index: Int) = swap(index, index - 1)

    fun moveDown(index: Int) = swap(index, index + 1)

    fun remove(index: Int) {
        if (isRunning) return
        _images.update { list -> if (index in list.indices) list.toMutableList().apply { removeAt(index) } else list }
    }

    /** Appends the current images, in order, to the chosen PDF. No-op unless [canRun]. */
    fun run() {
        val pdf = _pdf.value ?: return
        val list = _images.value
        if (list.isEmpty()) return
        val res = getApplication<Application>().resources
        startJob(
            inputs = listOf(JobInput.Pdf(pdf.uri)) + list.map { JobInput.Image(it.file.uri) },
            summarize = { ok -> res.getQuantityString(R.plurals.addimages_summary, ok.pages, ok.pages, formatBytes(ok.bytes)) },
        ) { engine, files, out, job -> engine.appendImages(files.first(), files.drop(1), out, job) }
    }

    override fun suggestedFileName(): String = suggestedNameFor(_pdf.value?.name)

    private fun swap(from: Int, to: Int) {
        if (isRunning) return
        _images.update { list ->
            if (from !in list.indices || to !in list.indices) {
                list
            } else {
                list.toMutableList().apply { this[from] = this[to].also { this[to] = this[from] } }
            }
        }
    }

    companion object {
        const val FALLBACK_FILE_NAME = "with-images.pdf"
        private const val SUFFIX = "-with-images"
        private const val PDF_EXT = ".pdf"

        /**
         * Save-dialog suggestion for an original display name: `report.pdf` -> `report-with-images.pdf`;
         * null or blank -> [FALLBACK_FILE_NAME]. Only a suggestion for the system dialog, never a path.
         */
        fun suggestedNameFor(originalName: String?): String {
            val base = originalName.orEmpty()
                .substringAfterLast('/')
                .trim()
                .let { if (it.endsWith(PDF_EXT, ignoreCase = true)) it.dropLast(PDF_EXT.length) else it }
                .trim()
            return if (base.isEmpty()) FALLBACK_FILE_NAME else "$base$SUFFIX$PDF_EXT"
        }
    }
}
