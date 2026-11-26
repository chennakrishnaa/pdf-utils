package app.venkat.offkit.engine.remote

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Watchdog limits for one call of [RemoteEngine]. A call that breaks one gets the engine process
 * killed: [stallMs] / [totalMs] / [imageTotalMs] / [infoTotalMs] / [renderTotalMs] give
 * `PdfError.TimedOut`, [memoryBytes] gives
 * `PdfError.OutOfMemory`.
 *
 * MuPDF calls cannot be interrupted from inside (see `security/engine-audit.md`, SR3: hostile PDFs
 * that spend minutes in one call or allocate 900 MB+), so the only reliable stop is killing the
 * process from outside. The defaults are the production values; tests inject smaller ones.
 *
 * @param stallMs longest time a PDF operation may go without a progress event or a result. The
 *   clock starts when the request is sent and restarts on every progress event.
 * @param totalMs longest time a PDF operation may take in all, progress or not.
 * @param imageTotalMs longest time an `estimateImage` or image-normalisation request may take.
 * @param memoryBytes largest resident set (RSS) the engine process may have while a call waits;
 *   [Long.MAX_VALUE] disables the check.
 * @param memorySampleMs how often the engine's RSS is sampled while waiting.
 * @param infoTotalMs longest time a reader page-info request may take. Reader requests report no
 *   progress and have no stall limit, only this total.
 * @param renderTotalMs longest time one reader page render may take (total only, as [infoTotalMs]).
 * @param textTotalMs longest time one reader page-text extraction may take (total only).
 */
data class EngineLimits(
    val stallMs: Long = STALL_MS,
    val totalMs: Long = TOTAL_MS,
    val imageTotalMs: Long = IMAGE_TOTAL_MS,
    val memoryBytes: Long = Long.MAX_VALUE,
    val memorySampleMs: Long = MEMORY_SAMPLE_MS,
    val infoTotalMs: Long = INFO_TOTAL_MS,
    val renderTotalMs: Long = RENDER_TOTAL_MS,
    val textTotalMs: Long = TEXT_TOTAL_MS,
) {
    companion object {
        /**
         * 90 s without progress. The operations report progress once per page or image, and one
         * legitimate page or image takes seconds. The phases after the last progress event (the
         * final save and the output validation) report nothing; for ordinary files they also take
         * seconds, but a very large input on a slow phone is the main false-positive risk of this
         * limit. A hostile file stuck in one MuPDF call (SR3-2 66 s, SR3-3 Merge link copy 60 s
         * for 50k links and growing quadratically, SR3-4 Merge save 107 s) is stopped after 90 s
         * instead of never.
         */
        const val STALL_MS = 90_000L

        /**
         * 15 minutes per PDF operation in all. The largest legitimate jobs (500 images, a 1 GiB
         * PDF) still make progress, so the stall limit does not stop them; this bounds a hostile
         * file that keeps reporting progress while every page is slow (SR3-3: one shared `/Annots`
         * array on many pages makes Compress spend about 8 s per page, estimated 13 minutes for
         * 100 pages) and the battery it can burn.
         */
        const val TOTAL_MS = 15 * 60_000L

        /**
         * 30 s for one image estimate or normalisation. These handle one image, already size-capped
         * by `ImageLimits` from its header, and report no progress; a decode that takes longer than
         * this is a hostile or broken file.
         */
        const val IMAGE_TOTAL_MS = 30_000L

        /**
         * 60 s to open a document and read every page size for the reader. Ordinary files take well
         * under a second; a hostile page tree (deep, huge, cyclic) is stopped here.
         */
        const val INFO_TOTAL_MS = 60_000L

        /**
         * 30 s for one page render (one tile of at most `RenderLimits.MAX_PIXELS`). A legitimate
         * heavy page (large scans, complex vector maps) renders in seconds; a page stuck longer is
         * hostile or broken, and the reader shows an error for that tile only.
         */
        const val RENDER_TOTAL_MS = 30_000L

        /** 30 s for one page's text and geometry: as a render, a page stuck longer is hostile or broken. */
        const val TEXT_TOTAL_MS = 30_000L

        /**
         * 10 s: the shortest gap between two wake-ups of [RemoteEngine]'s wait loop (normally
         * ~100 ms apart) after which an engine death noticed soon afterwards is blamed on a freeze
         * (`EngineUnavailable`) rather than on the file (`EngineCrashed`) when the app is not in
         * the background. A GC pause or a busy CPU stalls the loop for a few seconds and proves
         * nothing (PI4R-1), so this is well above such pauses (the review suggested about 10 s).
         * A shorter real freeze is still `EngineUnavailable` while the app is cached
         * (`inBackground`); only one that ends with the app already in the foreground is now
         * reported as `EngineCrashed`. A gap of more than 2 s still shifts the time limits (see
         * `RemoteEngine.FREEZE_GAP_MS`).
         */
        const val FROZEN_DEATH_GAP_MS = 10_000L

        /** RSS sampled about once a second: `/proc/<pid>/statm` is one small read. */
        const val MEMORY_SAMPLE_MS = 1_000L

        /**
         * Hard ceiling of the memory limit: 1536 MiB. An ordinary HIGH compress of a 69 MiB,
         * 16-page photo PDF peaked at 219 MiB RSS on the emulator. SR3-1 shows sub-1 MB hostile PDFs
         * reaching 902 MB, and 1,502 MB with a larger count, bounded only by the device. Without a
         * limit the engine could grow until the low-memory killer has killed other apps (and the
         * phone stutters) before it gets to the engine.
         */
        const val MEMORY_CEILING_BYTES = 1536L * 1024 * 1024

        /**
         * Share of the device's total RAM the engine may use: 40 %. On a 3 GiB phone that is
         * about 1.2 GiB, leaving the rest for the system, the foreground UI and other apps.
         */
        const val MEMORY_RAM_PERCENT = 40

        /** min([MEMORY_CEILING_BYTES], [MEMORY_RAM_PERCENT] % of [totalRamBytes]). */
        fun memoryLimitFor(totalRamBytes: Long): Long =
            if (totalRamBytes <= 0) MEMORY_CEILING_BYTES
            else minOf(MEMORY_CEILING_BYTES, totalRamBytes / 100 * MEMORY_RAM_PERCENT)
    }
}

/** Reads a process's resident set size from `/proc/<pid>/statm` (readable for the same UID). */
object ProcRss {
    private val pageSize: Long by lazy {
        try {
            Os.sysconf(OsConstants._SC_PAGESIZE).takeIf { it > 0 } ?: 4096L
        } catch (e: Throwable) {
            4096L
        }
    }

    /** RSS of [pid] in bytes, or null if `/proc/<pid>/statm` cannot be read or parsed. */
    fun bytes(pid: Int): Long? = try {
        parseStatm(File("/proc/$pid/statm").readText(), pageSize)
    } catch (e: Exception) {
        null
    }

    /** RSS in bytes from the text of a `statm` file (second field, in pages); null if malformed. */
    fun parseStatm(text: String, pageSize: Long): Long? =
        text.trim().split(' ').getOrNull(1)?.toLongOrNull()?.let { it * pageSize }
}
