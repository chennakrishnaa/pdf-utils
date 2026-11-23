package app.venkat.offkit.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.R
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.pdfErrorMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
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
 * - `ReadyToSave` --[save]--> `Saving(summary)` --ok--> `Saved(summary)` (recorded in
 *   [RecentFilesStore] with [toolId] first); --failure--> back to
 *   `ReadyToSave` with [saveError] set. While `Saving`, [save], [reset], [cancel] and [startJob]
 *   are ignored. The copy runs in [PdfJobs.processScope], so it completes (or cleans up) even if
 *   this ViewModel is cleared meanwhile.
 * - any but `Saving` --[reset]--> `Idle` (cancels a running job, discards a pending output).
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
    private var cleared = false

    /** True while a job is running (including while it winds down after [cancel]). */
    val isRunning: Boolean get() = runningJob != null

    /** True while the result is being copied to its destination (state `Saving`). */
    private val isSaving: Boolean get() = _jobState.value is JobUiState.Saving

    /**
     * Starts a job; ignored while one is already running or a save is in progress. Any previous pending output is
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
        if (isRunning || isSaving) return
        discardPending()
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

    /** Cancels a running job; the state becomes `Idle` once the engine has stopped. No-op otherwise (also while saving). */
    fun cancel() {
        if (isSaving) return
        runningJob?.cancel()
    }

    /**
     * Saves the pending output to [dest], the Uri from the "create document" dialog. `null`
     * (dialog dismissed) keeps `ReadyToSave`. Otherwise `ReadyToSave` → `Saving` → `Saved`, or
     * back to `ReadyToSave` with [saveError] set (the output is kept for another attempt and the
     * destination document is deleted). Ignored while `Saving`.
     *
     * If there is no pending output (e.g. the process was recreated while the dialog was open),
     * the just-created destination document is deleted if it is empty and [saveError] explains that the
     * result is gone.
     */
    fun save(dest: Uri?) {
        if (dest == null || isSaving) return
        val app = getApplication<Application>()
        val output = pending
        if (output == null) {
            jobs.processScope.launch {
                try {
                    withContext(Dispatchers.IO) { jobs.deleteDestinationIfEmpty(dest) }
                } catch (e: Exception) {
                    // Best effort.
                }
            }
            _saveError.value = app.getString(R.string.core_result_unavailable)
            return
        }
        if (_jobState.value !is JobUiState.ReadyToSave) return
        val savedSummary = summary
        _saveError.value = null
        _jobState.value = JobUiState.Saving(savedSummary)
        jobs.processScope.launch {
            val result = try {
                jobs.save(output, dest)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (result.isSuccess) {
                if (pending === output) pending = null
                recordRecent(dest, output)
                _jobState.value = JobUiState.Saved(savedSummary)
            } else if (cleared) {
                jobs.discard(output)
            } else {
                _jobState.value = JobUiState.ReadyToSave(savedSummary)
                // Let screens that clear the error on leaving ReadyToSave observe the new state first.
                yield()
                _saveError.value = app.getString(R.string.core_save_failed)
            }
        }
    }

    /**
     * Id of the tool, recorded with each saved file in [RecentFilesStore] (e.g. `"merge"`).
     * Override per tool; must be a constant.
     */
    open val toolId: String = ""

    /** Records a successful save in [RecentFilesStore]. Never throws: recording must not fail a save. */
    private suspend fun recordRecent(dest: Uri, output: PendingOutput) {
        try {
            val app = getApplication<Application>()
            val tool = toolId
            withContext(Dispatchers.IO) {
                val described = UriIo.describe(app, dest)
                RecentFilesStore.get(app).add(
                    RecentFile(dest, described.name, described.sizeBytes ?: output.bytes, System.currentTimeMillis(), tool),
                )
            }
        } catch (e: Exception) {
            // Ignore: the file is saved; it just will not appear in the recent list.
        }
    }

    /** Clears [saveError] after the UI has shown it. */
    fun clearSaveError() {
        _saveError.value = null
    }

    /**
     * Discards any pending output and returns to `Idle`. While a job is running this is the
     * same as [cancel] (the state becomes `Idle` once the engine has stopped). Ignored while saving.
     */
    fun reset() {
        if (isSaving) return
        if (isRunning) {
            cancel()
            return
        }
        discardPending()
        lastResult = null
        summary = ""
        _saveError.value = null
        _jobState.value = JobUiState.Idle
    }

    /**
     * Marks the current result as stale. Call it whenever an input or option that the result
     * was produced from changes (a file added, removed or reordered, a level or page size
     * changed), so the user can never save an output that no longer matches the screen.
     *
     * From `ReadyToSave`, `Saved` or `Failed`: discards any pending output, clears [saveError]
     * and [lastResult], and returns to `Idle`. No-op while `Running` or `Saving` (and in `Idle`).
     */
    protected fun invalidateResult() {
        when (_jobState.value) {
            is JobUiState.ReadyToSave, is JobUiState.Saved, is JobUiState.Failed -> {
                discardPending()
                lastResult = null
                summary = ""
                _saveError.value = null
                _jobState.value = JobUiState.Idle
            }
            else -> Unit
        }
    }

    /** File name pre-filled in the save dialog. Override per tool, e.g. `"merged.pdf"`. */
    open fun suggestedFileName(): String = getApplication<Application>().getString(R.string.core_default_file_name)

    /** Describes [uris] (display name, size) off the main thread, in order. Never throws. */
    protected suspend fun describe(uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
        uris.map { UriIo.describe(getApplication(), it) }
    }

    override fun onCleared() {
        cleared = true
        runningJob?.cancel()
        // A save in progress owns the pending output: it deletes it on success, discards it on failure.
        if (!isSaving) discardPending()
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
