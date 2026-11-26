package app.venkat.offkit.feature.reader

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.annotation.StringRes
import app.venkat.offkit.R
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.ReaderDocument
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor

/**
 * What printing needs of the open document: its [name], [pageCount] and a way to open the file to
 * print ([openSource], blocking, called off the main thread; throws if the document is closed or
 * the file is gone): the private copy, or for an encrypted document its unencrypted copy
 * ([app.venkat.offkit.core.PdfReader.preparePrint]).
 */
class ReaderPrintJob(val name: String, val pageCount: Int, val openSource: () -> FileChannel)

/**
 * Why [doc] may not be printed, or null: always null. The PDF's no-print permission is not
 * honoured (owner decision). An encrypted document is printed from an unencrypted copy, since the
 * print spooler renders the file in its own process without the password.
 */
@StringRes
internal fun printBlockReason(@Suppress("UNUSED_PARAMETER") doc: ReaderDocument): Int? = null

/**
 * Starts printing [job] with the system print framework. [context] must lead to an Activity
 * (`PrintManager.print` needs one). Returns a message for the user if printing could not start
 * (no Activity, no `PrintManager`, or `print` threw), else null.
 */
@StringRes
internal fun startPrint(context: Context, job: ReaderPrintJob): Int? {
    val activity = context.findActivity() ?: return R.string.reader_print_unavailable
    return try {
        val manager = activity.getSystemService(PrintManager::class.java) ?: return R.string.reader_print_unavailable
        manager.print(job.name, ReaderPrintAdapter(job), PrintAttributes.Builder().build())
        null
    } catch (e: Exception) {
        R.string.reader_print_unavailable
    }
}

/**
 * Hands the private copy's bytes, unchanged, to the print framework. No rendering or parsing here:
 * the spooler renders the PDF in its own process.
 *
 * - [onLayout] reports a document of [ReaderPrintJob.pageCount] pages.
 * - [onWrite] copies the whole file to the destination on a background thread ([executor]) and
 *   reports `PageRange.ALL_PAGES`: the adapter cannot split a PDF, and the framework keeps only the
 *   requested pages of what was written. [CancellationSignal] is checked between chunks
 *   (`onWriteCancelled`); any failure, including a document closed before its copy was first
 *   opened, gives `onWriteFailed`.
 * - The source is opened on the first write and kept until [onFinish]: a document closed later
 *   (its folder, with any unencrypted print copy, deleted) still prints from the open
 *   descriptor. A document closed before the first write gives `onWriteFailed`. The destination descriptor is
 *   the framework's: it is written and flushed but never closed here.
 */
class ReaderPrintAdapter(
    private val job: ReaderPrintJob,
    private val executor: Executor = Executor { Thread(it, "reader-print").apply { isDaemon = true }.start() },
) : PrintDocumentAdapter() {
    private val lock = Any()
    private var source: FileChannel? = null
    private var finished = false

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(PdfJobs.shareFileName(job.name))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(job.pageCount)
            .build()
        // The content never depends on the attributes: changed only for the first layout.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        executor.execute { write(destination, cancellationSignal, callback) }
    }

    private fun write(destination: ParcelFileDescriptor, signal: CancellationSignal?, callback: WriteResultCallback) {
        try {
            val channel = synchronized(lock) {
                if (finished) throw IllegalStateException("Print finished")
                source ?: job.openSource().also { source = it }
            }
            // Not closed: the stream does not own the descriptor (closing it would close the
            // framework's fd under it on the JVM); the framework closes [destination] itself.
            FileOutputStream(destination.fileDescriptor).let { out ->
                val buf = ByteBuffer.allocate(BUFFER_BYTES)
                var position = 0L
                while (true) {
                    if (signal?.isCanceled == true) {
                        callback.onWriteCancelled()
                        return
                    }
                    buf.clear()
                    val n = channel.read(buf, position)
                    if (n < 0) break
                    out.write(buf.array(), 0, n)
                    position += n
                }
                out.flush()
            }
            if (signal?.isCanceled == true) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(null)
        }
    }

    override fun onFinish() {
        val channel = synchronized(lock) {
            finished = true
            source.also { source = null }
        }
        try {
            channel?.close()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** For tests: whether the source is open. */
    internal val sourceOpen: Boolean get() = synchronized(lock) { source?.isOpen == true }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
