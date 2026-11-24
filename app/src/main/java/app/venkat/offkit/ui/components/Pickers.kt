package app.venkat.offkit.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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

/*
 * All helpers use the Storage Access Framework / photo picker with transient grants only:
 * the returned Uris are readable for as long as the activity lives, and nothing persists them.
 * If the user cancels a picker, onPicked is NOT called (a pick never yields an empty list).
 * If the device has no app to handle a picker, a short message is shown instead of crashing.
 */

/**
 * Runs [launch] (an `ActivityResultLauncher.launch` call). If no activity can handle the
 * request ([ActivityNotFoundException]), shows a short "no file picker" Toast instead of
 * crashing. Returns true if [launch] completed normally.
 */
internal fun launchOrExplain(context: Context, launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.core_no_picker, Toast.LENGTH_SHORT).show()
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
 * An `ACTION_VIEW` intent that opens the saved PDF at [uri] in another app: data [uri], type
 * `application/pdf`, with `FLAG_GRANT_READ_URI_PERMISSION`.
 */
fun openPdfIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, MIME_PDF)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * Opens the PDF at [uri] in a viewer app ([openPdfIntent]), guarded by [launchOrExplain] so a
 * device without a PDF viewer shows a short message instead of crashing. Returns true if launched.
 */
fun openSavedPdf(context: Context, uri: Uri): Boolean =
    launchOrExplain(context) { context.startActivity(openPdfIntent(uri)) }

/**
 * Launches the share sheet for each new [request] (guarded by [launchOrExplain]) and reports
 * whether it launched through [onHandled] (e.g. `JobViewModel.onShareHandled`).
 */
@Composable
fun ShareRequestEffect(request: ShareRequest?, onHandled: (launched: Boolean) -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onHandled)
    LaunchedEffect(request) {
        if (request != null) {
            callback(launchOrExplain(context) { context.startActivity(sharePdfIntent(request.uri, request.name)) })
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
 * Returns a function that opens the system photo picker for images only
 * ([ActivityResultContracts.PickMultipleVisualMedia]). [onPicked] receives the chosen Uris in
 * the order the picker returns them; not called on cancel.
 */
@Composable
fun rememberImagePicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) callback(uris)
    }
    val context = LocalContext.current
    return remember(launcher, context) {
        {
            launchOrExplain(context) {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
}

/**
 * Returns a function that opens the system "create document" dialog for a PDF
 * ([ActivityResultContracts.CreateDocument] with [MIME_PDF]), pre-filled with the given
 * suggested file name. [onChosen] receives the new document's Uri (writable via
 * `ContentResolver.openOutputStream`), or null if the user cancelled.
 */
@Composable
fun rememberPdfSaver(onChosen: (Uri?) -> Unit): (suggestedName: String) -> Unit {
    val callback by rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_PDF)) { uri ->
        callback(uri)
    }
    val context = LocalContext.current
    return remember(launcher, context) {
        { suggestedName: String -> launchOrExplain(context) { launcher.launch(suggestedName) } }
    }
}
