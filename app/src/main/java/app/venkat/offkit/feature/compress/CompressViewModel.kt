package app.venkat.offkit.feature.compress

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.R
import app.venkat.offkit.core.JobInput
import app.venkat.offkit.core.JobViewModel
import app.venkat.offkit.core.PickedFile
import app.venkat.offkit.core.ReaderCopyHandoff
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.venkat.offkit.core.UriIo
import app.venkat.offkit.core.sanitizeDisplayName
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.isSaving
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State and actions of the "Compress PDF" screen. */
class CompressViewModel(app: Application) : JobViewModel(app) {
    private val _file = MutableStateFlow<PickedFile?>(null)

    /** The chosen PDF, or null. */
    val file: StateFlow<PickedFile?> = _file.asStateFlow()

    private val _level = MutableStateFlow(CompressionLevel.MEDIUM)

    /** The selected compression level; Medium by default. */
    val level: StateFlow<CompressionLevel> = _level.asStateFlow()

    /** Size of the input as copied for the job (what the engine compares against); provider size is the fallback. */
    @Volatile
    private var copiedInputBytes: Long? = null

    override val toolId: String = TOOL_ID

    /** True while inputs and options must not change: a job is running or its result is being saved. */
    private val locked: Boolean get() = isRunning || jobState.value.isSaving

    /**
     * Chooses [uri] as the PDF to compress, replacing any previous choice. Any result of the
     * previous choice is discarded (see [inputsChanged]). Ignored while running or saving.
     */
    fun choose(uri: Uri) {
        if (locked || !UriIo.isContentUri(uri)) return // only content Uris from the picker (SB-14)
        viewModelScope.launch {
            val picked = describe(listOf(uri)).first()
            if (locked) return@launch
            inputsChanged()
            readerCopy = null
            _file.value = picked
        }
    }

    /**
     * The reader's private copy chosen through the reader's "Compress" ([handoff]), or null. When
     * set, [file] carries its name and size and a [Uri.EMPTY] that nothing reads: [run] compresses
     * this file instead ([JobInput.ReaderCopy]). Memory only, like the hand-off.
     */
    private var readerCopy: File? = null

    /**
     * Chooses the reader's open document ([handoff]) as the PDF to compress, shown with its name
     * and size as if picked. Ignored while running or saving.
     */
    fun chooseReaderCopy(handoff: ReaderCopyHandoff) {
        if (locked) return
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) { handoff.file.length().takeIf { it > 0 } }
            if (locked) return@launch
            inputsChanged()
            readerCopy = handoff.file
            _file.value = PickedFile(Uri.EMPTY, sanitizeDisplayName(handoff.name, UriIo.FALLBACK_NAME), size, nameReported = true)
        }
    }

    /** Selects the compression level; a different level discards any result. Ignored while running or saving. */
    fun setLevel(level: CompressionLevel) {
        if (locked || level == _level.value) return
        inputsChanged()
        _level.value = level
    }

    /** Starts compressing the chosen PDF at the selected level. No-op without a PDF or while running. */
    fun run() {
        val picked = _file.value ?: return
        if (locked) return
        val level = _level.value
        copiedInputBytes = null
        val input = readerCopy?.let { JobInput.ReaderCopy(it) } ?: JobInput.Pdf(picked.uri)
        startJob(
            inputs = listOf(input),
            originalBytes = { copiedInputBytes ?: picked.sizeBytes },
        ) { engine, files, out, job ->
            val input = files.first()
            copiedInputBytes = input.length()
            engine.compress(input, level, out, job)
        }
    }

    override fun suggestedFileName(): String =
        compressedFileName(_file.value?.name, getApplication<Application>().getString(R.string.compress_file_name))

    /** The result no longer matches the inputs: drop it (and any save error) so it cannot be saved. */
    private fun inputsChanged() {
        invalidateResult()
        clearSaveError()
    }

    companion object {
        /** Id recorded with files saved from this screen in the recent-files list. */
        const val TOOL_ID = "compress"
        private const val SUFFIX = "-compressed"
        private const val EXTENSION = ".pdf"
        private const val MAX_BASE_LENGTH = 100
        private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /**
         * Save-dialog suggestion for a compressed copy of [originalName]: the name with `-compressed`
         * before `.pdf` (`report.pdf` → `report-compressed.pdf`, `scan` → `scan-compressed.pdf`).
         * Characters that are unsafe in file names are replaced; a blank or unusable name gives
         * [fallback]. Only ever a suggestion for the system dialog, never a path.
         */
        fun compressedFileName(originalName: String?, fallback: String = "compressed.pdf"): String {
            var base = sanitizeDisplayName(originalName, "").replace(UNSAFE_CHARS, "_").trim()
            if (base.endsWith(EXTENSION, ignoreCase = true)) base = base.dropLast(EXTENSION.length)
            base = base.trim().trimStart('.').take(MAX_BASE_LENGTH).trim()
            if (base.isEmpty() || base.all { it == '_' }) return fallback
            return base + SUFFIX + EXTENSION
        }

        /** Saving in whole percent, rounded down but at least 1 when the file did get smaller. */
        fun savingPercent(originalBytes: Long, newBytes: Long): Int =
            app.venkat.offkit.ui.components.savingPercent(originalBytes, newBytes)
    }
}
