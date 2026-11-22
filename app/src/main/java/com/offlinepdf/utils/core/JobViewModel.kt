package com.offlinepdf.utils.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.JobControl
import com.offlinepdf.utils.engine.PdfEngine
import com.offlinepdf.utils.engine.PdfError
import com.offlinepdf.utils.engine.PdfResult
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.pdfErrorMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Base class for tool ViewModels: owns one job's lifecycle and exposes it as [jobState] for
 * `JobPanel`. Subclasses hold their own input state and call [startJob].
 *
 * State machine (all transitions on the main thread):
 * - `Idle`/`ReadyToSave`/`Saved`/`Failed` --[startJob]--> `Running(0, 0)` --progress--> `Running(d, t)`
 * - `Running` --success--> `ReadyToSave(summary)`; --error--> `Failed(message)`;
 *   --[cancel] or engine `Cancelled`--> `Idle` (once the engine has stopped and the cache is clean).
 * - `ReadyToSave` --[save] ok--> `Saved(summary)`; failure stays `ReadyToSave` and sets [saveError].
 * - any --[reset]--> `Idle` (cancels a running job, discards a pending output).
 *
 * @param jobs job runner; defaults to the process-wide [PdfJobs.get]. Tests may inject one.
 */
open class JobViewModel(
    application: Application,
    private val jobs: PdfJobs = PdfJobs.get(application),
) : AndroidViewModel(application) {

    private val _jobState = MutableStateFlow<JobUiState>(JobUiState.Idle)

    /** Current job state; render with `JobPanel(state = jobState.collectAsState().value, ...)`. */
    val jobState: StateFlow<JobUiState> = _jobState.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)

    /** One-shot user-facing message after a failed save (state stays `ReadyToSave`); see [clearSaveError]. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** Result of the last successful job (pages, bytes of the output), or null. Cleared by [reset] and [startJob]. */
    var lastResult: PdfResult.Ok? = null
        private set

    private var pending: PendingOutput? = null
    private var summary: String = ""
    private var runningJob: Job? = null
    private var saveJob: Job? = null

    /** True while a job is running (including while it winds down after [cancel]). */
    val isRunning: Boolean get() = runningJob != null

    /**
     * Starts a job; ignored while one is already running. Any previous pending output is
     * discarded. [block] runs on the engine thread with the prepared input files (same order as
     * [inputs]) and must call exactly one [PdfEngine] operation with `out` and `job`, e.g.
     * `{ engine, files, out, job -> engine.merge(files, out, job) }`. [summarize] runs on the
     * main thread and turns the result into the user-facing summary shown in `ReadyToSave` and
     * `Saved`.
     */
    protected fun startJob(
        inputs: List<JobInput>,
        summarize: (PdfResult.Ok) -> String,
        block: (PdfEngine, List<File>, File, JobControl) -> PdfResult,
    ) {
        if (isRunning) return
        discardPending()
        saveJob?.cancel()
        lastResult = null
        summary = ""
        _saveError.value = null
        _jobState.value = JobUiState.Running(0, 0)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome = jobs.run(
                    inputs = inputs,
                    onProgress = { done, total ->
                        if (_jobState.value is JobUiState.Running) _jobState.value = JobUiState.Running(done, total)
                    },
                    block = block,
                )
                when (outcome) {
                    is JobOutcome.Ready -> {
                        val ok = PdfResult.Ok(outcome.output.pages, outcome.output.bytes)
                        pending = outcome.output
                        lastResult = ok
                        summary = summarize(ok)
                        _jobState.value = JobUiState.ReadyToSave(summary)
                    }
                    is JobOutcome.Failed -> _jobState.value = stateFor(outcome.error)
                }
            } catch (e: CancellationException) {
                if (_jobState.value is JobUiState.Running) _jobState.value = JobUiState.Idle
                throw e
            } finally {
                // Only now (engine stopped, cache cleaned) may a new job start.
                if (runningJob === coroutineContext.job) runningJob = null
            }
        }
        runningJob = job
        job.start()
    }

    /** Cancels a running job; the state becomes `Idle` once the engine has stopped. No-op otherwise. */
    fun cancel() {
        runningJob?.cancel()
    }

    /**
     * Saves the pending output to [dest], the Uri from the "create document" dialog. `null`
     * (dialog dismissed) keeps `ReadyToSave`. Success → `Saved(summary)`; failure keeps
     * `ReadyToSave` (the output is kept for another attempt) and sets [saveError].
     */
    fun save(dest: Uri?) {
        if (dest == null) return
        val output = pending ?: return
        if (_jobState.value !is JobUiState.ReadyToSave || saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            val result = jobs.save(output, dest)
            if (pending !== output) return@launch
            if (result.isSuccess) {
                pending = null
                _jobState.value = JobUiState.Saved(summary)
            } else {
                _saveError.value = getApplication<Application>().getString(R.string.core_save_failed)
            }
        }
    }

    /** Clears [saveError] after the UI has shown it. */
    fun clearSaveError() {
        _saveError.value = null
    }

    /**
     * Discards any pending output and returns to `Idle`. While a job is running this is the
     * same as [cancel] (the state becomes `Idle` once the engine has stopped).
     */
    fun reset() {
        if (isRunning) {
            cancel()
            return
        }
        saveJob?.cancel()
        discardPending()
        lastResult = null
        summary = ""
        _saveError.value = null
        _jobState.value = JobUiState.Idle
    }

    /** File name pre-filled in the save dialog. Override per tool, e.g. `"merged.pdf"`. */
    open fun suggestedFileName(): String = getApplication<Application>().getString(R.string.core_default_file_name)

    /** Describes [uris] (display name, size) off the main thread, in order. Never throws. */
    protected suspend fun describe(uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
        uris.map { UriIo.describe(getApplication(), it) }
    }

    override fun onCleared() {
        runningJob?.cancel()
        saveJob?.cancel()
        discardPending()
        super.onCleared()
    }

    private fun discardPending() {
        pending?.let(jobs::discard)
        pending = null
    }

    private fun stateFor(error: PdfError): JobUiState =
        if (error == PdfError.Cancelled) {
            JobUiState.Idle
        } else {
            JobUiState.Failed(getApplication<Application>().getString(pdfErrorMessage(error)))
        }
}
