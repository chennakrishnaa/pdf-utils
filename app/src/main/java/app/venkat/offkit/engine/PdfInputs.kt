package app.venkat.offkit.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/** An input PDF could not be used; [error] is the classification to report. */
class PdfInputException(val error: PdfError, cause: Throwable? = null) :
    RuntimeException("unusable input PDF: $error", cause)

/**
 * RDR-4: a one-entry memory of the last input whose pre-checks PASSED, for the reader, which opens
 * the same document once per tile ([app.venkat.offkit.engine.ops.RenderPageOp], `PageInfoOp`).
 *
 * Measured on the dev host (MuPDF 1.28.5, warm page cache): [ObjStmScan] costs ~10 ms per MB
 * (200 MB photo PDF: 1.9-2.0 s per open, of which MuPDF's own open is ~0; a damaged 16 MB file
 * that MuPDF repairs: 160-230 ms per open, scan 172 ms), so without this every tile of a large
 * document would pay seconds. Only the Kotlin verdicts are remembered (never a MuPDF object: they
 * die with the op's thread, see [EngineStack]): that [ObjStmScan] passed, and which pages passed
 * [app.venkat.offkit.engine.ops.RenderImageGate] (at most [MAX_PAGES]). A failed check is never
 * remembered, so a hostile file is refused on every call.
 *
 * The [Key] is the canonical path, length, last-modified time and a CRC-32 of the first and last
 * [FINGERPRINT_BYTES] bytes, so another file, or the same path rewritten with a different size,
 * time, head or tail, misses and is scanned again. Residual: an in-place rewrite that keeps size,
 * mtime, head and tail would hit; the reader's copy is private and never rewritten in place, and
 * other operations do not use the memo.
 *
 * Passwords (reader): [Key.auth] is a tag of the password the document was opened with (0 for
 * none): the first 8 bytes of SHA-256(per-process random salt || UTF-8 password), never the
 * password. A verdict recorded under one password (or none) is therefore not found under another;
 * a verdict is only ever recorded after MuPDF accepted the password, so a wrong password never
 * reaches the memo.
 */
internal object ScanMemo {
    const val FINGERPRINT_BYTES = 64 * 1024
    const val MAX_PAGES = 4096

    data class Key(val path: String, val length: Long, val modified: Long, val fingerprint: Long, val auth: Long = 0L)

    private val salt: ByteArray by lazy { ByteArray(16).also { SecureRandom().nextBytes(it) } }

    /** The [Key.auth] tag of [password]: 0 for null or empty. */
    fun authTag(password: String?): Long {
        if (password.isNullOrEmpty()) return 0L
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val d = md.digest(password.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return if (v == 0L) 1L else v
    }

    private var key: Key? = null
    private val pages = HashSet<Int>()

    /** The key of [file] now, or null if it cannot be read (then nothing is remembered). */
    fun keyOf(file: File, password: String? = null): Key? = try {
        val canonical = file.canonicalFile
        RandomAccessFile(canonical, "r").use { raf ->
            val length = raf.length()
            val modified = canonical.lastModified()
            val crc = CRC32()
            val buf = ByteArray(minOf(length, FINGERPRINT_BYTES.toLong()).toInt())
            raf.readFully(buf)
            crc.update(buf)
            raf.seek(maxOf(0L, length - buf.size))
            raf.readFully(buf)
            crc.update(buf)
            Key(canonical.path, length, modified, crc.value, authTag(password))
        }
    } catch (e: IOException) {
        null
    }

    @Synchronized
    fun scanned(k: Key): Boolean = k == key

    @Synchronized
    fun rememberScan(k: Key) {
        if (k != key) {
            key = k
            pages.clear()
        }
    }

    @Synchronized
    fun pagePassed(k: Key, page: Int): Boolean = k == key && page in pages

    @Synchronized
    fun rememberPage(k: Key, page: Int) {
        if (k == key && pages.size < MAX_PAGES) pages.add(page)
    }

    @Synchronized
    fun clear() {
        key = null
        pages.clear()
    }
}

/** Opening and classifying input PDFs, and the error mapping shared by all ops. */
internal object PdfInputs {
    /**
     * Save options for documents the engine BUILDS (Images to PDF, the new document of Merge): their
     * object graph is either the engine's own or a grafted copy, whose depth MuPDF itself caps (see
     * [ObjectGraph]), so the recursive `garbage` marking is safe and removes unused objects.
     */
    const val SAVE_OPTIONS = "garbage=deduplicate,compress,objstms"

    /**
     * Save options for an INPUT document saved after in-place edits (Compress, Add images; SA-11 /
     * SR2-4). No `garbage`: in MuPDF 1.28.5 `pdf-write.c` the only step that follows indirect
     * references is `markobj` (recursive, one native frame per link), and it runs only when
     * `do_garbage >= 1`; without it every xref entry is simply marked used, and the remaining steps
     * (object-stream unpack/gather, stream deflate, serialisation) iterate over object numbers.
     * Serialisation recurses only over DIRECT nesting, which the parser caps at the 256-slot
     * `fz_try` stack. So the save cost no longer depends on the attacker's reference depth. Price:
     * objects the input itself left unreferenced are kept (Compress overwrites image streams in
     * place, Add images only adds objects, so the ops themselves orphan nothing).
     */
    const val EDIT_SAVE_OPTIONS = "compress,objstms"

    /**
     * Opens [file] as a PDF. The caller owns the result and must `destroy()` it.
     *
     * Throws [PdfInputException] with:
     * - [PdfError.Encrypted] when the document is encrypted in any way: it needs a password, or it
     *   has only an owner password (permission restrictions, empty user password). Policy: every
     *   encrypted PDF is rejected, so no operation can strip restrictions from its output;
     * - [PdfError.Corrupt] when it cannot be parsed, is not a PDF, or has no pages;
     * - [PdfError.Io] when the file cannot be opened (missing, unreadable);
     * - [PdfError.OutOfMemory] when it is larger than [ImageLimits.MAX_INPUT_PDF_BYTES] (checked
     *   before MuPDF opens it; the user is told "Not enough memory. Try fewer or smaller files.")
     *   or has more than [ObjectGraph.MAX_OBJECTS] objects, or when the raw-file pre-scan
     *   [ObjStmScan] (before MuPDF opens it) finds an object stream that decodes too large.
     * The object graph is NOT walked here (SR2-3: a walk over every reachable object costs memory
     * proportional to the references): Compress and Add images save without the recursive
     * `garbage` pass ([EDIT_SAVE_OPTIONS]), and Merge bounds what grafting touches itself
     * ([ObjectGraph.checkPages]).
     * On failure nothing opened is left alive.
     *
     * MuPDF picks the document handler by file name extension. A file whose name does not end in
     * ".pdf" is still handled: see [PdfInputsTest] — such files are opened as PDFs (MuPDF falls back
     * to content sniffing), and non-PDF content is reported as Corrupt.
     */
    fun open(file: File, memo: ScanMemo.Key? = null): PDFDocument = openImpl(file, memo, reader = false, password = null)

    /**
     * [open] for the reader only ([app.venkat.offkit.engine.PdfPageRenderer]): an encrypted PDF is
     * opened instead of refused. When it needs a password: none ([password] null or empty) is
     * [PdfError.Encrypted], one MuPDF rejects (`authenticatePassword`) is [PdfError.WrongPassword].
     * A PDF with only an owner password opens without one (MuPDF authenticates the empty user
     * password at open); a [password] given for it, or for an unencrypted PDF, is ignored. A
     * [password] that fails [ReaderPassword.check] is `Io(IllegalArgumentException)`.
     *
     * Pre-checks on an encrypted PDF (see `security/engine-audit.md`, "Reader password addendum"):
     * the size and object-count limits are as for [open]; [ObjStmScan] pass 1 runs over the raw
     * file and, after authentication and before the page count, pass 2
     * ([ObjStmScan.checkDecrypted]) measures every Flate, ASCII or ASCII-then-Flate object stream
     * on its decrypted data. A file
     * that pass 1 could not read is refused when encrypted. [memo] must be the key for [password]
     * ([ScanMemo.keyOf] with it); only a document that passed both passes is remembered.
     */
    fun openForReader(file: File, password: String?, memo: ScanMemo.Key?): PDFDocument {
        if (password != null) {
            ReaderPassword.check(password)?.let { throw PdfInputException(PdfError.Io(IllegalArgumentException(it))) }
        }
        return openImpl(file, memo, reader = true, password = password)
    }

    private fun openImpl(file: File, memo: ScanMemo.Key?, reader: Boolean, password: String?): PDFDocument {
        if (file.length() > ImageLimits.MAX_INPUT_PDF_BYTES) throw PdfInputException(PdfError.OutOfMemory)
        val memoHit = memo != null && ScanMemo.scanned(memo)
        var scan: ObjStmScan.Result? = null
        if (!memoHit) {
            try {
                objStmScans++
                scan = ObjStmScan.check(file) // SR3-1: object-stream bombs are paid inside MuPDF's open
            } catch (e: IOException) {
                // Unreadable: MuPDF's own open below reports it (Io); an encrypted one is refused below.
            }
        }
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: RuntimeException) {
            throw classifyOpenFailure(e)
        }
        try {
            if (doc.needsPassword()) {
                if (!reader || password.isNullOrEmpty()) throw PdfInputException(PdfError.Encrypted)
                if (!doc.authenticatePassword(password)) throw PdfInputException(PdfError.WrongPassword)
            }
            val pdf = doc as? PDFDocument ?: throw PdfInputException(PdfError.Corrupt)
            if (isEncrypted(pdf)) {
                if (!reader) throw PdfInputException(PdfError.Encrypted)
                if (!memoHit) {
                    // Pass 1 saw ciphertext: measure the object streams again on decrypted data,
                    // before countPages resolves the catalog (which may live in one).
                    val pass1 = scan ?: throw PdfInputException(PdfError.OutOfMemory)
                    checkGraph { ObjStmScan.checkDecrypted(pdf, pass1) }
                }
            }
            if (memo != null && scan != null) ScanMemo.rememberScan(memo) // only a PASSED scan is remembered
            val pages = try {
                pdf.countPages()
            } catch (e: RuntimeException) {
                // SR3-5: a native allocation failure while loading the page tree is "too large".
                throw PdfInputException(if (isOutOfMemoryFailure(e)) PdfError.OutOfMemory else PdfError.Corrupt, e)
            }
            if (pages <= 0) throw PdfInputException(PdfError.Corrupt)
            if (pdf.countObjects() > ObjectGraph.MAX_OBJECTS) throw PdfInputException(PdfError.OutOfMemory)
            return pdf
        } catch (t: Throwable) {
            doc.destroy()
            throw t
        }
    }

    /** Number of [ObjStmScan] runs by [open] (test observability of [ScanMemo]). */
    @Volatile
    internal var objStmScans = 0L

    /**
     * Runs an [ObjectGraph] check on an opened input: [PdfInputException]s pass through, any other
     * MuPDF failure while walking (an unparsable object) is reported as [PdfError.Corrupt].
     */
    inline fun checkGraph(block: () -> Unit) {
        try {
            block()
        } catch (e: PdfInputException) {
            throw e
        } catch (e: RuntimeException) {
            if (isOutOfMemoryFailure(e)) throw e
            throw PdfInputException(PdfError.Corrupt, e)
        }
    }

    /**
     * True when [pdf] uses PDF encryption, independent of password state: MuPDF reports a crypt
     * handler (`isUnencryptedPDF()` is false) or the trailer has an `/Encrypt` entry.
     */
    internal fun isEncrypted(pdf: PDFDocument): Boolean {
        if (!pdf.isUnencryptedPDF) return true
        val trailer = pdf.trailer
        try {
            val encrypt = trailer.get("Encrypt")
            try {
                return !encrypt.isNull
            } finally {
                if (encrypt !== PDFObject.Null) encrypt.destroy()
            }
        } finally {
            trailer.destroy()
        }
    }

    internal fun classifyOpenFailure(e: RuntimeException): PdfInputException {
        val msg = e.message.orEmpty()
        return if (isOutOfMemoryFailure(e)) {
            PdfInputException(PdfError.OutOfMemory, e) // SR3-5
        } else if (msg.startsWith("cannot open")) {
            PdfInputException(PdfError.Io(e), e)
        } else {
            PdfInputException(PdfError.Corrupt, e)
        }
    }

    /**
     * Output check after a save (security audit SA-5): MuPDF can repair a damaged input and then
     * save a document whose page tree is cyclic or malformed. Reopens [out] and requires exactly
     * [expectedPages] pages, every one of which loads (each page and the document are destroyed;
     * cancellation is polled between pages). Returns null when [out] is good; otherwise deletes [out]
     * and returns `Err(Corrupt)` (or `Err(Cancelled)`).
     */
    fun validateOutput(out: File, expectedPages: Int, job: JobControl): PdfResult.Err? {
        val err = checkPages(out, expectedPages, job)
        if (err != null) out.delete()
        return err
    }

    /**
     * Null when [file] opens with exactly [expectedPages] pages and every page loads; otherwise
     * `Err(Corrupt)`, or `Err(Cancelled)` if [job] is cancelled between pages. Never modifies [file].
     * Out-of-memory failures propagate (see [isOutOfMemoryFailure]).
     */
    fun checkPages(file: File, expectedPages: Int, job: JobControl): PdfResult.Err? {
        val corrupt = PdfResult.Err(PdfError.Corrupt)
        val doc = try {
            Document.openDocument(file.absolutePath)
        } catch (e: RuntimeException) {
            if (isOutOfMemoryFailure(e)) throw e
            return corrupt
        }
        try {
            for (i in 0 until expectedPages) {
                if (job.isCancelled()) return PdfResult.Err(PdfError.Cancelled)
                if (i == 0 && doc.countPages() != expectedPages) return corrupt
                doc.loadPage(i).destroy()
            }
            return if (expectedPages > 0) null else corrupt
        } catch (e: RuntimeException) {
            if (isOutOfMemoryFailure(e)) throw e
            return corrupt
        } finally {
            doc.destroy()
        }
    }

    /**
     * Runs [block], mapping a thrown [PdfInputException] to `Err(error)` and an
     * [UnsupportedImageFileException] to `Err(UnsupportedImage)`. Anything else propagates
     * (MuPdfEngine's guardedRun maps it to OutOfMemory / Io).
     */
    inline fun toResult(block: () -> PdfResult): PdfResult = try {
        block()
    } catch (e: PdfInputException) {
        PdfResult.Err(e.error)
    } catch (e: UnsupportedImageFileException) {
        PdfResult.Err(PdfError.UnsupportedImage)
    } catch (e: ImageTooLargeException) {
        PdfResult.Err(PdfError.ImageTooLarge)
    }
}
