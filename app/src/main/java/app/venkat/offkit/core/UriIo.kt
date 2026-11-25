package app.venkat.offkit.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * A file the user picked, as shown in a file list.
 *
 * @property uri the content Uri returned by the picker (transient grant; never persisted).
 * @property name display name for the UI (from the provider, or a fallback); never used as a
 *   path on disk.
 * @property sizeBytes size in bytes if the provider reports it, else null.
 * @property nameReported true when [name] is the provider's own `DISPLAY_NAME` (or, for a
 *   `file:` Uri, the file's real name), false when it was guessed from the Uri or is the fallback.
 */
data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long?, val nameReported: Boolean = false)

/**
 * Blocking stream helpers between content Uris and local files. Call them off the main thread
 * (e.g. on `Dispatchers.IO`). Nothing here takes persistable permissions.
 */
object UriIo {
    private const val BUFFER_SIZE = 64 * 1024

    /** Fallback display name when neither the provider nor the Uri yields one. */
    const val FALLBACK_NAME = "file"

    /**
     * True if [uri] is a `content:` Uri, the only kind the system pickers and save dialog return.
     * Anything else (`file:`, `android.resource:`, no scheme) is never read or written by the app.
     */
    fun isContentUri(uri: Uri?): Boolean = uri?.scheme == ContentResolver.SCHEME_CONTENT

    /**
     * Describes [uri] for display: name and size from the provider's [OpenableColumns], falling
     * back to the Uri's last path segment. The name is untrusted and is passed through
     * [sanitizeDisplayName]. Non-`content:` Uris are not queried (name from the Uri only, no
     * size). Never throws; a blank name becomes [FALLBACK_NAME]. [PickedFile.nameReported] says
     * whether the name came from the provider's `DISPLAY_NAME` (or a `file:` Uri's own path)
     * rather than from a guess.
     */
    fun describe(context: Context, uri: Uri): PickedFile {
        var name: String? = null
        var size: Long? = null
        if (isContentUri(uri)) try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx).takeIf { it >= 0 }
                }
            }
        } catch (e: Exception) {
            // Fall through to the Uri-derived fallback.
        }
        val fallback = try {
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        } catch (e: Exception) {
            null
        }
        val reported = if (uri.scheme == ContentResolver.SCHEME_FILE) fallback else name
        val reportedName = sanitizeDisplayName(reported, "")
        val finalName = reportedName.ifEmpty { sanitizeDisplayName(fallback, FALLBACK_NAME) }
        return PickedFile(uri, finalName, size, nameReported = reportedName.isNotEmpty())
    }

    /** Thrown by [copyToFile] when the source is longer than its `maxBytes`. */
    class InputTooLargeException(val maxBytes: Long) : IOException("Input is larger than $maxBytes bytes")

    /**
     * Copies the content of [src] into [dest] (created or overwritten). Returns the number of
     * bytes copied. [isCancelled] is polled between 64 KiB chunks; when it returns true the
     * partial [dest] is deleted and a [CancellationException] is thrown. If more than [maxBytes]
     * bytes arrive, copying stops, the partial [dest] is deleted and [InputTooLargeException] is
     * thrown (for providers that report no size, or a wrong one).
     *
     * @throws IOException if [src] is missing, unreadable or the copy fails (any provider
     *   exception such as SecurityException is wrapped); the partial [dest] is deleted.
     */
    @Throws(IOException::class)
    fun copyToFile(
        context: Context,
        src: Uri,
        dest: File,
        isCancelled: () -> Boolean = { false },
        maxBytes: Long = Long.MAX_VALUE,
    ): Long {
        try {
            val input = openInput(context, src)
            return input.use { inp ->
                FileOutputStream(dest).use { out -> pump(inp, out, isCancelled, maxBytes) }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw wrap(e, "Cannot read $src")
        }
    }

    /**
     * Copies [src] to [dest], opening the destination with mode `"wt"` so an existing longer
     * document is truncated. [isCancelled] is polled between chunks (throws
     * [CancellationException]). Returns the number of bytes written.
     *
     * @throws IOException if [dest] cannot be opened or written, or [src] cannot be read.
     */
    @Throws(IOException::class)
    fun copyToUri(context: Context, src: File, dest: Uri, isCancelled: () -> Boolean = { false }): Long {
        try {
            val output = context.contentResolver.openOutputStream(dest, "wt")
                ?: throw IOException("Cannot open $dest for writing")
            return output.use { out ->
                FileInputStream(src).use { inp -> pump(inp, out, isCancelled) }
            }
        } catch (e: Throwable) {
            throw wrap(e, "Cannot write $dest")
        }
    }

    /**
     * Copies [src] into [out] and flushes it; does not close [out]. [isCancelled] is polled
     * between chunks (throws [CancellationException]). Returns the number of bytes written.
     */
    @Throws(IOException::class)
    fun copyToStream(src: File, out: OutputStream, isCancelled: () -> Boolean = { false }): Long =
        FileInputStream(src).use { inp -> pump(inp, out, isCancelled) }

    private fun openInput(context: Context, src: Uri): InputStream =
        context.contentResolver.openInputStream(src) ?: throw IOException("Cannot open $src")

    private fun pump(input: InputStream, output: OutputStream, isCancelled: () -> Boolean, maxBytes: Long = Long.MAX_VALUE): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw CancellationException("Copy cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw InputTooLargeException(maxBytes)
            output.write(buffer, 0, n)
        }
        output.flush()
        return total
    }

    /** Cancellation and errors pass through unchanged; any other exception becomes an IOException. */
    private fun wrap(e: Throwable, message: String): Throwable = when (e) {
        is CancellationException, is IOException, is Error -> e
        else -> IOException(message, e)
    }
}
