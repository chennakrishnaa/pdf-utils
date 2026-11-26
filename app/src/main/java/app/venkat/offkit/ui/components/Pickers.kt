package app.venkat.offkit.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.venkat.offkit.R
import app.venkat.offkit.core.ShareRequest

/** MIME type of PDF documents. */
const val MIME_PDF = "application/pdf"

/**
 * MIME types offered by the image picker ([rememberImagePicker]): every image type. The app
 * accepts anything Android can decode: JPEG and PNG (recognised by magic bytes, not by MIME type)
 * are embedded as is, and everything else (WebP, HEIC/HEIF, GIF, BMP, ...) is transcoded to JPEG
 * by `ImageNormalizer` in the `:engine` process. Undecodable content is rejected there as
 * `UnsupportedImage`, so a narrower list would only hide files that work.
 */
val IMAGE_PICKER_MIME_TYPES: List<String> = listOf("image/*")

/*
 * All helpers use the Storage Access Framework (system file picker) with transient grants only:
 * the returned Uris are readable for as long as the activity lives, and nothing persists them.
 * If the user cancels a picker, onPicked is NOT called (a pick never yields an empty list).
 * If the device has no app to handle a picker, a short message is shown instead of crashing.
 */

/**
 * Runs [launch] (an `ActivityResultLauncher.launch` call or `startActivity`). If no activity can
 * handle the request ([ActivityNotFoundException]), shows [message] (by default "no file picker")
 * as a short Toast instead of crashing. Returns true if [launch] completed normally.
 */
internal fun launchOrExplain(
    context: Context,
    @StringRes message: Int = R.string.core_no_picker,
    launch: () -> Unit,
): Boolean = try {
    launch()
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    false
}

/**
 * A chooser for `ACTION_SEND` of one PDF: type `application/pdf`, [uri] as `EXTRA_STREAM` and as
 * `ClipData` (labelled [name]), with `FLAG_GRANT_READ_URI_PERMISSION` so only the chosen app gets
 * temporary read access.
 */
fun sharePdfIntent(uri: Uri, name: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = MIME_PDF
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData(name, arrayOf(MIME_PDF), ClipData.Item(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * Launches the share sheet for each new [request] (guarded by [launchOrExplain]), then calls
 * [onHandled] (e.g. `JobViewModel.onShareHandled`), whether or not it launched.
 */
@Composable
fun ShareRequestEffect(request: ShareRequest?, onHandled: () -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onHandled)
    LaunchedEffect(request) {
        if (request != null) {
            launchOrExplain(context, R.string.core_no_share_target) {
                context.startActivity(sharePdfIntent(request.uri, request.name))
            }
            callback()
        }
    }
}

/**
 * Returns a function that opens the system document picker for PDFs
 * ([ActivityResultContracts.OpenDocument], or [ActivityResultContracts.OpenMultipleDocuments]
 * when [multiple]). [onPicked] receives the chosen Uris in picker order; not called on cancel.
 */
@Composable
fun rememberPdfPicker(multiple: Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) callback(listOf(uri))
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) callback(uris)
    }
    val context = LocalContext.current
    return remember(multiple, single, many, context) {
        {
            launchOrExplain(context) {
                if (multiple) many.launch(arrayOf(MIME_PDF)) else single.launch(arrayOf(MIME_PDF))
            }
        }
    }
}

/**
 * Returns a function that opens the system file picker for images
 * ([ActivityResultContracts.OpenMultipleDocuments] with [IMAGE_PICKER_MIME_TYPES]: an
 * `ACTION_OPEN_DOCUMENT` intent with `EXTRA_ALLOW_MULTIPLE`), so the user can browse any folder,
 * Downloads, an SD card or another document provider, not only the photo gallery.
 * [onPicked] receives the chosen Uris in the order the picker returns them (the system does not
 * promise selection order; they are not sorted); not called on cancel. Transient grants only,
 * like [rememberPdfPicker]: the images are copied when a job runs, while the activity lives.
 */
@Composable
fun rememberImagePicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) callback(uris)
    }
    val context = LocalContext.current
    return remember(launcher, context) {
        { launchOrExplain(context) { launcher.launch(IMAGE_PICKER_MIME_TYPES.toTypedArray()) } }
    }
}

/**
 * Returns a function that opens the system "create document" dialog for a PDF
 * ([ActivityResultContracts.CreateDocument] with [MIME_PDF]), pre-filled with the given
 * suggested file name. [onChosen] receives the new document's Uri (writable via
 * `ContentResolver.openOutputStream`), or null if the user cancelled. The function returns false
 * when no app can handle the request (a toast explains it), so no result will arrive.
 */
@Composable
fun rememberPdfSaver(onChosen: (Uri?) -> Unit): (suggestedName: String) -> Boolean {
    val callback by rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_PDF)) { uri ->
        callback(uri)
    }
    val context = LocalContext.current
    return remember(launcher, context) {
        { suggestedName: String -> launchOrExplain(context) { launcher.launch(suggestedName) } }
    }
}
