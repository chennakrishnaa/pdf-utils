package app.venkat.filetools.feature.imagestopdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.venkat.filetools.R
import app.venkat.filetools.core.JobInput
import app.venkat.filetools.core.JobViewModel
import app.venkat.filetools.core.PdfJobs
import app.venkat.filetools.core.PickedFile
import app.venkat.filetools.engine.CompressionLevel
import app.venkat.filetools.engine.PDF_DOCUMENT_OVERHEAD_BYTES
import app.venkat.filetools.engine.PDF_PAGE_OVERHEAD_BYTES
import app.venkat.filetools.engine.PageSize
import app.venkat.filetools.engine.PdfEstimate
import app.venkat.filetools.ui.components.JobUiState
import app.venkat.filetools.ui.components.formatBytes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Size estimate of one picked image. */
sealed interface ImageEstimate {
    /** Not computed yet (queued, in progress, or paused while a job runs). */
    data object Pending : ImageEstimate

    /** Computed. */
    data class Ready(val estimate: PdfEstimate.Ok) : ImageEstimate

    /** The image could not be estimated (e.g. unsupported or unreadable). */
    data object Failed : ImageEstimate
}

/**
 * Estimated output sizes for the current image list, per quality option.
 *
 * @property imageCount number of images in the list.
 * @property pendingCount images whose estimate is not known yet.
 * @property anyFailed true if at least one image could not be estimated (totals are then unknown).
 */
data class QualityEstimates(
    val imageCount: Int = 0,
    val pendingCount: Int = 0,
    val anyFailed: Boolean = false,
    private val original: Long? = null,
    private val byLevel: Map<CompressionLevel, Long> = emptyMap(),
) {
    /**
     * Estimated PDF size in bytes for [quality] (null = original quality), or null when not
     * known (no images, estimates pending or failed).
     */
    fun total(quality: CompressionLevel?): Long? = if (quality == null) original else byLevel[quality]

    companion object {
        /** Builds the totals from per-image [estimates], in any order. */
        fun of(estimates: List<ImageEstimate>): QualityEstimates {
            val pending = estimates.count { it == ImageEstimate.Pending }
            val failed = estimates.any { it == ImageEstimate.Failed }
            if (estimates.isEmpty() || pending > 0 || failed) {
                return QualityEstimates(estimates.size, pending, failed)
            }
            val ready = estimates.map { (it as ImageEstimate.Ready).estimate }
            fun sum(bytes: (PdfEstimate.Ok) -> Long?): Long? {
                var total = PDF_DOCUMENT_OVERHEAD_BYTES
                for (e in ready) total += (bytes(e) ?: return null) + PDF_PAGE_OVERHEAD_BYTES
                return total
            }
            val byLevel = CompressionLevel.entries.mapNotNull { level -> sum { it.byLevel[level] }?.let { level to it } }.toMap()
            return QualityEstimates(estimates.size, 0, false, sum { it.original }, byLevel)
        }
    }
}

/** State and actions of the "Images to PDF" tool. */
class ImagesToPdfViewModel(
    app: Application,
    private val pdfJobs: PdfJobs = PdfJobs.get(app),
) : JobViewModel(app, pdfJobs) {

    /** One picked image with a stable identity (the same file may be picked twice). */
    private data class Entry(val id: Long, val file: PickedFile)

    private var nextId = 0L
    private var entries: List<Entry> = emptyList()
    private val estimateById = mutableMapOf<Long, ImageEstimate>()

    private val _images = MutableStateFlow<List<PickedFile>>(emptyList())

    /** Picked images, in page order. The same image may appear more than once. */
    val images: StateFlow<List<PickedFile>> = _images.asStateFlow()

    private val _pageSize = MutableStateFlow(PageSize.FIT_IMAGE)

    /** Page size for the output; defaults to [PageSize.FIT_IMAGE]. */
    val pageSize: StateFlow<PageSize> = _pageSize.asStateFlow()

    private val _quality = MutableStateFlow<CompressionLevel?>(null)

    /** Image compression for the output; null (the default) embeds images unchanged. */
    val quality: StateFlow<CompressionLevel?> = _quality.asStateFlow()

    private val _estimates = MutableStateFlow(QualityEstimates())

    /** Estimated output size per quality option, updated as image estimates arrive. */
    val estimates: StateFlow<QualityEstimates> = _estimates.asStateFlow()

    /** Background estimation worker (one image at a time), or null when idle/paused. */
    private var estimator: Job? = null

    /** Id of the entry the [estimator] is working on, or null. */
    private var estimatingId: Long? = null

    override val toolId: String = TOOL_ID

    /** Inputs and options cannot change while a job runs or its result is being saved. */
    private val locked: Boolean get() = isRunning || jobState.value is JobUiState.Saving

    init {
        // Resume estimation of still-pending images once a job has finished (or a save completed).
        viewModelScope.launch {
            jobState.collect { ensureEstimator() }
        }
    }

    /** Appends [uris] (in order) to the list once they are described. Ignored while locked. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || locked) return
        viewModelScope.launch {
            val picked = describe(uris)
            if (locked) return@launch
            val added = picked.map { Entry(nextId++, it) }
            added.forEach { estimateById[it.id] = ImageEstimate.Pending }
            setEntries(entries + added)
            invalidateResult()
            ensureEstimator()
        }
    }

    fun moveUp(index: Int) = move(index, index - 1)

    fun moveDown(index: Int) = move(index, index + 1)

    fun remove(index: Int) {
        if (locked || index !in entries.indices) return
        val removed = entries[index]
        estimateById.remove(removed.id)
        setEntries(entries.toMutableList().apply { removeAt(index) })
        if (estimatingId == removed.id) {
            pauseEstimates()
            ensureEstimator()
        }
        invalidateResult()
    }

    fun setPageSize(size: PageSize) {
        if (locked || _pageSize.value == size) return
        _pageSize.value = size
        invalidateResult()
    }

    /** Selects the image quality: null = original, or a [CompressionLevel]. */
    fun setQuality(level: CompressionLevel?) {
        if (locked || _quality.value == level) return
        _quality.value = level
        invalidateResult()
    }

    /** Starts the conversion of the current list with the current options; no-op when empty. */
    fun run() {
        val list = entries
        if (list.isEmpty() || locked) return
        val size = _pageSize.value
        val compression = _quality.value
        val app = getApplication<Application>()
        // Keep the engine thread free for the real job; pending estimates resume afterwards.
        pauseEstimates()
        startJob(
            inputs = list.map { JobInput.Image(it.file.uri) },
            summarize = { ok ->
                app.getString(
                    R.string.imagestopdf_summary,
                    app.resources.getQuantityString(R.plurals.imagestopdf_pages, ok.pages, ok.pages),
                    formatBytes(ok.bytes),
                )
            },
        ) { engine, files, out, job -> engine.imagesToPdf(files, size, out, job, compression = compression) }
    }

    override fun suggestedFileName(): String = SUGGESTED_FILE_NAME

    private fun move(from: Int, to: Int) {
        if (locked || from !in entries.indices || to !in entries.indices) return
        setEntries(entries.toMutableList().apply { add(to, removeAt(from)) })
        invalidateResult()
    }

    private fun setEntries(list: List<Entry>) {
        entries = list
        _images.value = list.map { it.file }
        publishEstimates()
    }

    private fun publishEstimates() {
        _estimates.value = QualityEstimates.of(entries.map { estimateById[it.id] ?: ImageEstimate.Pending })
    }

    private fun pauseEstimates() {
        estimator?.cancel()
        estimator = null
        estimatingId = null
    }

    /** Starts the background worker if any image is pending and no job or save is in progress. */
    private fun ensureEstimator() {
        // Gate on the state, not isRunning: once the state has left Running the engine call has returned.
        val state = jobState.value
        if (estimator != null || state is JobUiState.Running || state is JobUiState.Saving) return
        if (entries.none { estimateById[it.id] == ImageEstimate.Pending }) return
        lateinit var worker: Job
        worker = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val next = entries.firstOrNull { estimateById[it.id] == ImageEstimate.Pending } ?: break
                    estimatingId = next.id
                    val result = pdfJobs.estimateImage(next.file.uri)
                    if (estimator !== worker) return@launch
                    if (estimateById.containsKey(next.id)) {
                        estimateById[next.id] = if (result is PdfEstimate.Ok) ImageEstimate.Ready(result) else ImageEstimate.Failed
                        publishEstimates()
                    }
                }
            } finally {
                if (estimator === worker) {
                    estimator = null
                    estimatingId = null
                }
            }
        }
        estimator = worker
        worker.start()
    }

    companion object {
        const val SUGGESTED_FILE_NAME = "images.pdf"
        const val TOOL_ID = "images_to_pdf"
    }
}
