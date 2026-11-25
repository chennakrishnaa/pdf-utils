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
import app.venkat.offkit.ui.components.isSaving
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
 * `OffkitJobFlow`. Subclasses hold their own input state and call [startJob].
 *
 * State machine (all transitions on the main thread):
 * - `Idle`/`Done`/`Failed` --[startJob]--> `Running(0, 0)` --progress--> `Running(d, t)`.
 *   Starting discards the previous output and wipes `cacheDir/shared/`.
 * - `Running` --success--> `Done(pages, bytes, originalBytes, suggestedName)`; --error-->
 *   `Failed(error)`; --[cancel] or engine `Cancelled`--> `Idle` (once the engine has stopped and
 *   the cache is clean).
 * - `Done` --[save]--> `Done(saving = true)` --ok--> `Done(saving = false, savedName, savedUri)`
 *   (recorded in [RecentFilesStore] with [toolId] first). The output is kept, so saving again
 *   ("save another copy") is allowed and updates `savedName`/`savedUri` to the latest;
 *   --failure--> `Done` as before with [saveError] set. While saving, [save], [share], [reset],
 *   [cancel], [invalidateResult] and [startJob] are ignored (an ignored save destination is
 *   deleted if it is empty). The copy runs in
 *   [PdfJobs.processScope], so it completes (and cleans up) even if this ViewModel is cleared.
 * - `Done` --[share]--> copy to `cacheDir/shared/` (one copy per output, reused by later shares
 *   while it exists) --> [shareRequest] set; the screen launches the share sheet and calls
 *   [onShareHandled], which clears it. The state does not change either way (sharing never counts
 *   as saving: Android gives no result for a share). A failed copy sets [saveError].
 * - any but saving --[reset]--> `Idle` (cancels a running job, discards the output).
 * - `Done` (not saving)/`Failed` --[invalidateResult]--> `Idle` (discards the output).
 *
 * The output file lives until [reset], [invalidateResult], the next [startJob] or [onCleared]
 * (deferred to the end of a save in progress).
 *
 * @param jobs job runner; defaults to the process-wide [PdfJobs.get]. Tests may inject one.
 */
open class JobViewModel(
    application: Application,
    private val jobs: PdfJobs = PdfJobs.get(application),
) : AndroidViewModel(application) {

    private val _jobState = MutableStateFlow<JobUiState>(JobUiState.Idle)

    /** Current job state; render with `OffkitJobFlow(state = jobState.collectAsState().value, ...)`. */
    val jobState: StateFlow<JobUiState> = _jobState.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)

    /** One-shot user-facing message after a failed save or share (state unchanged); see [clearSaveError]. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _shareRequest = MutableStateFlow<ShareRequest?>(null)

    /**
     * One-shot request to open the share sheet, set by [share] once the copy is ready. The screen
     * launches it and then calls [onShareHandled].
     */
    val shareRequest: StateFlow<ShareRequest?> = _shareRequest.asStateFlow()

    /** Result of the last successful job (pages, bytes of the output), or null. Cleared by [reset] and [startJob]. */
    var lastResult: PdfResult.Ok? = null
        private set

    private var pending: PendingOutput? = null
    private var runningJob: Job? = null
    private var shareJob: Job? = null
    private var cleared = false

    /** The destination of the save in progress, or null. */
    private var savingDest: Uri? = null

    /** True while a job is running (including while it winds down after [cancel]). */
    val isRunning: Boolean get() = runningJob != null

    /** True while the result is being copied to its destination (`Done.saving`). */
    private val isSaving: Boolean get() = _jobState.value.isSaving

    /**
     * Starts a job; ignored while one is already running or a save is in progress. Any previous
     * output is discarded and `cacheDir/shared/` is wiped. [block] runs on the engine thread with
     * the prepared input files (same order as [inputs]) and must call exactly one [PdfEngine]
     * operation with `out` and `job`, e.g. `{ engine, files, out, job -> engine.merge(files, out, job) }`.
     * [originalBytes] runs on the main thread after success and gives `Done.originalBytes` (the
     * size of the input the output replaces, or null). `Done.suggestedName` is
     * [suggestedFileName] at that moment.
     */
    protected fun startJob(
        inputs: List<JobInput>,
        originalBytes: () -> Long? = { null },
        block: (PdfEngine, List<File>, File, JobControl) -> PdfResult,
    ) {
        if (isRunning || isSaving) return
        clearResult()
        _jobState.value = JobUiState.Running(0, 0)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                jobs.wipeShared()
                val outcome = jobs.run(
                    inputs = inputs,
                    onProgress = { done, total ->
                        if (_jobState.value is JobUiState.Running) _jobState.value = JobUiState.Running(done, total)
                    },
                    block = block,
                )
                when (outcome) {
                    is JobOutcome.Ready -> {
                        val out = outcome.output
                        pending = out
                        lastResult = PdfResult.Ok(out.pages, out.bytes)
                        _jobState.value = JobUiState.Done(out.pages, out.bytes, originalBytes(), suggestedFileName())
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
     * Saves the output to [dest], the Uri from the "create document" dialog. `null` (dialog
     * dismissed) changes nothing. From `Done` (saved before or not): `saving = true`, then on
     * success `savedName` (the display name the provider reports for [dest]) and `savedUri` are
     * set; on failure `Done` is restored with [saveError] set (the destination document is
     * deleted). The output is kept either way.
     *
     * While saving, a new [dest] is ignored and, like below, deleted if it is empty (it was just
     * created by a second dialog); a non-empty document and the destination being written are
     * never deleted.
     *
     * If there is no output (e.g. the process was recreated while the dialog was open), the
     * just-created destination document is deleted if it is empty and [saveError] explains that the
     * result is gone.
     */
    fun save(dest: Uri?) {
        if (dest == null) return
        if (isSaving) {
            // A second dialog's document: not used, so remove it if it is still empty. Never the
            // destination being written now (it is empty until the copy starts).
            if (dest != savingDest) deleteIfEmptyInBackground(dest)
            return
        }
        val app = getApplication<Application>()
        val output = pending
        if (output == null) {
            deleteIfEmptyInBackground(dest)
            _saveError.value = app.getString(R.string.core_result_unavailable)
            return
        }
        val before = _jobState.value as? JobUiState.Done ?: return
        _saveError.value = null
        _jobState.value = before.copy(saving = true)
        savingDest = dest
        jobs.processScope.launch {
            val result = try {
                jobs.save(output, dest)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val savedName = if (result.isSuccess) recordRecent(dest, output) else null
            // onCleared left the output to this save, the last user of it.
            if (cleared) jobs.discard(output)
            savingDest = null
            val current = _jobState.value as? JobUiState.Done ?: before
            if (result.isSuccess) {
                _jobState.value = current.copy(saving = false, savedName = savedName, savedUri = dest)
            } else if (!cleared) {
                _jobState.value = current.copy(saving = false)
                // Let screens observe the restored state before the error.
                yield()
                _saveError.value = app.getString(
                    R.string.error_save_message,
                    app.getString(R.string.error_save_title),
                    app.getString(R.string.error_save_body),
                )
            }
        }
    }

    /** Deletes [dest] if it is empty ([PdfJobs.deleteDestinationIfEmpty]) off the main thread, best effort. */
    private fun deleteIfEmptyInBackground(dest: Uri) {
        jobs.processScope.launch {
            try {
                withContext(Dispatchers.IO) { jobs.deleteDestinationIfEmpty(dest) }
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /**
     * Prepares the output for sharing: copies it off the main thread to
     * `cacheDir/shared/<random id>/<sanitised Done.suggestedName>`, then sets [shareRequest].
     * Sharing the same output again reuses that copy (same Uri) while it still exists with the
     * output's length ([PdfJobs.prepareShare]); a new output always gets its own copy.
     * Ignored unless in `Done` and not saving, and while a share is already being prepared. A
     * failed copy (or a missing output) sets [saveError] and leaves the state unchanged.
     */
    fun share() {
        val done = _jobState.value as? JobUiState.Done ?: return
        if (done.saving || shareJob != null) return
        val app = getApplication<Application>()
        val output = pending ?: run {
            _saveError.value = app.getString(R.string.core_result_unavailable)
            return
        }
        _saveError.value = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val request = try {
                    jobs.prepareShare(output, done.suggestedName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (pending !== output) return@launch
                if (request != null) {
                    _shareRequest.value = request
                } else {
                    _saveError.value = app.getString(R.string.result_share_failed)
                }
            } finally {
                if (shareJob === coroutineContext.job) shareJob = null
            }
        }
        shareJob = job
        job.start()
    }

    /**
     * Call once the screen has handled [shareRequest] (launched the share sheet or explained that
     * no app can take it): clears it. The result's state is left as is: Android reports nothing
     * about what happens in the share sheet, so an unsaved result stays unsaved.
     */
    fun onShareHandled() {
        _shareRequest.value = null
    }

    /**
     * Id of the tool, recorded with each saved file in [RecentFilesStore] (e.g. `"merge"`).
     * Override per tool; must be a constant.
     */
    open val toolId: String = ""

    /**
     * Records a successful save in [RecentFilesStore] and returns the display name the provider
     * reports for [dest]: null if it cannot be read or the provider reports none (no guessed name;
     * the Recent entry still gets the fallback name). Never throws: recording must not fail a save.
     */
    private suspend fun recordRecent(dest: Uri, output: PendingOutput): String? {
        val app = getApplication<Application>()
        val described = try {
            withContext(Dispatchers.IO) { UriIo.describe(app, dest) }
        } catch (e: Exception) {
            return null
        }
        try {
            val tool = toolId
            withContext(Dispatchers.IO) {
                RecentFilesStore.get(app).add(
                    RecentFile(dest, described.name, described.sizeBytes ?: output.bytes, System.currentTimeMillis(), tool),
                )
            }
        } catch (e: Exception) {
            // Ignore: the file is saved; it just will not appear in the recent list.
        }
        return described.name.takeIf { described.nameReported }
    }

    /** Clears [saveError] after the UI has shown it. */
    fun clearSaveError() {
        _saveError.value = null
    }

    /**
     * Discards the output and returns to `Idle`. While a job is running this is the same as
     * [cancel] (the state becomes `Idle` once the engine has stopped). Ignored while saving.
     */
    fun reset() {
        if (isSaving) return
        if (isRunning) {
            cancel()
            return
        }
        clearResult()
        _jobState.value = JobUiState.Idle
    }

    /**
     * Marks the current result as stale. Call it whenever an input or option that the result
     * was produced from changes (a file added, removed or reordered, a level or page size
     * changed), so the user can never save an output that no longer matches the screen.
     *
     * From `Done` (not saving) or `Failed`: discards the output, clears [saveError] and
     * [lastResult], and returns to `Idle`. No-op while `Running` or saving (and in `Idle`).
     */
    protected fun invalidateResult() {
        val state = _jobState.value
        if (state is JobUiState.Failed || (state is JobUiState.Done && !state.saving)) {
            clearResult()
            _jobState.value = JobUiState.Idle
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
        shareJob?.cancel()
        // A save in progress owns the output: it discards it when it ends.
        if (!isSaving) discardPending()
        super.onCleared()
    }

    /** Drops the output, any share being prepared, [shareRequest], [lastResult] and [saveError]. */
    private fun clearResult() {
        shareJob?.cancel()
        shareJob = null
        _shareRequest.value = null
        discardPending()
        lastResult = null
        _saveError.value = null
    }

    private fun discardPending() {
        pending?.let(jobs::discard)
        pending = null
    }

    private fun stateFor(error: PdfError): JobUiState =
        if (error == PdfError.Cancelled) JobUiState.Idle else JobUiState.Failed(error)
}

/** A prepared share: a `content://` [uri] from the app's share provider and the file [name] it carries. */
data class ShareRequest(val uri: Uri, val name: String)
