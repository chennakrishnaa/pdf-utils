package com.offlinepdf.utils.core

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
 */
data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long?)

/**
 * Blocking stream helpers between content Uris and local files. Call them off the main thread
 * (e.g. on `Dispatchers.IO`). Nothing here takes persistable permissions.
 */
object UriIo {
    private const val BUFFER_SIZE = 64 * 1024

    /** Fallback display name when neither the provider nor the Uri yields one. */
    const val FALLBACK_NAME = "file"

    /**
     * Describes [uri] for display: name and size from the provider's [OpenableColumns], falling
     * back to the Uri's last path segment (and, for `file://` Uris, the file's length).
     * Never throws; a blank name becomes [FALLBACK_NAME].
     */
    fun describe(context: Context, uri: Uri): PickedFile {
        var name: String? = null
        var size: Long? = null
        try {
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
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            try {
                val f = uri.path?.let(::File)
                if (f != null && f.isFile && size == null) size = f.length()
            } catch (e: Exception) {
                // Ignore: size stays unknown.
            }
        }
        val fallback = try {
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        } catch (e: Exception) {
            null
        }
        val finalName = name?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
        return PickedFile(uri, finalName, size)
    }

    /**
     * Copies the content of [src] into [dest] (created or overwritten). Returns the number of
     * bytes copied. [isCancelled] is polled between 64 KiB chunks; when it returns true the
     * partial [dest] is deleted and a [CancellationException] is thrown.
     *
     * @throws IOException if [src] is missing, unreadable or the copy fails (any provider
     *   exception such as SecurityException is wrapped); the partial [dest] is deleted.
     */
    @Throws(IOException::class)
    fun copyToFile(context: Context, src: Uri, dest: File, isCancelled: () -> Boolean = { false }): Long {
        try {
            val input = openInput(context, src)
            return input.use { inp ->
                FileOutputStream(dest).use { out -> pump(inp, out, isCancelled) }
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

    private fun pump(input: InputStream, output: OutputStream, isCancelled: () -> Boolean): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw CancellationException("Copy cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
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
