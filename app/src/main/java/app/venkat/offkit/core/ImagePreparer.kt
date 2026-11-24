package app.venkat.offkit.core

import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfError
import java.io.File

/**
 * Normalises a copied image (raw bytes, see [ImageNormalizer.rawFile]) into a JPEG or PNG the PDF
 * engine can embed ([ImageNormalizer.normalizeFile]).
 *
 * In the app this is the [app.venkat.offkit.engine.remote.RemoteEngine], so platform image codecs
 * decode untrusted WebP/HEIC/GIF/BMP content in the `:engine` process, never in the UI process.
 * `PdfJobs` uses the app's `PdfEngine` as its preparer; an engine that is not an [ImagePreparer]
 * cannot take image inputs. Tests use `FakePdfEngine`, which normalises in-process.
 */
interface ImagePreparer {
    /**
     * Normalises [raw] (deleted in every case) into [destDir] as image input [index]. Blocking; not
     * on the main thread. `job.isCancelled()` is honoured where possible.
     */
    fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl = JobControl.None): PreparedImage
}

/** Outcome of [ImagePreparer.prepareImage]. */
sealed interface PreparedImage {
    /** [file] is `img_NNN.jpg` or `img_NNN.png` in the requested directory. */
    data class Ok(val file: File) : PreparedImage

    /**
     * [error] is `UnsupportedImage` (undecodable), `OutOfMemory`, `EngineCrashed` (the engine
     * process died), `Cancelled`, or `Io`.
     */
    data class Err(val error: PdfError) : PreparedImage
}

/**
 * Runs [ImageNormalizer.normalizeFile] in the calling process and maps its failures to
 * [PreparedImage.Err]. Used by the `:engine` process ([app.venkat.offkit.engine.remote.EngineServer]).
 */
object InProcessImagePreparer : ImagePreparer {
    override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage = try {
        if (job.isCancelled()) {
            raw.delete()
            PreparedImage.Err(PdfError.Cancelled)
        } else {
            PreparedImage.Ok(ImageNormalizer.normalizeFile(raw, destDir, index))
        }
    } catch (e: UnsupportedImageException) {
        PreparedImage.Err(PdfError.UnsupportedImage)
    } catch (e: OutOfMemoryError) {
        PreparedImage.Err(PdfError.OutOfMemory)
    } catch (e: Exception) {
        PreparedImage.Err(PdfError.Io(e))
    } finally {
        raw.delete()
    }
}
