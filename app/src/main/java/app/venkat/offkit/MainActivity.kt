package app.venkat.offkit

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.venkat.offkit.ui.components.MIME_PDF
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.nav.ViewRequest
import app.venkat.offkit.ui.onboarding.OnboardingScreen
import app.venkat.offkit.ui.onboarding.OnboardingStore
import app.venkat.offkit.ui.theme.OffkitTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's only activity (singleTask). A PDF another app asks it to open ("Open with", in
 * [onCreate] or [onNewIntent]) opens in the reader with [ViewRequest.fromOtherApp] set: leaving that
 * reader with Back (system or top bar) pops it and moves this task to the back
 * ([returnToCaller]), so the user lands in the app they came from. Not [finish]: a tool's
 * unsaved result may sit under the reader, and returning to Offkit later shows it.
 */
class MainActivity : ComponentActivity() {
    private val viewRequests: ViewRequests by viewModels()

    /** Sends the user back to the app that asked Offkit to open a PDF; replaceable by tests. */
    @VisibleForTesting
    internal var returnToCaller: () -> Unit = { moveTaskToBack(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // "Open with": the only input taken from an intent is a PDF's content: Uri, read here once
        // (not again when the activity is recreated: the reader then has it or it is gone).
        val document = if (savedInstanceState == null) documentUri(intent) else null
        // Drop data, extras and grants another app may have attached, before the navigation host
        // could read them as a deep link (SB-13). The read grant of a VIEW intent belongs to this
        // activity, not to the Intent object, so the reader can still copy the document.
        intent = launchIntentOnly(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        document?.let { viewRequests.post(it.uri, it.typeVerified, fromOtherApp = true) }
        val onboarding = OnboardingStore(this)
        // Read before the first frame, so neither the introduction nor home flashes.
        val firstRun = !onboarding.seen
        setContent {
            OffkitTheme {
                var showIntroduction by rememberSaveable { mutableStateOf(firstRun) }
                if (showIntroduction) {
                    OnboardingScreen(onFinished = {
                        onboarding.markSeen()
                        showIntroduction = false
                    })
                } else {
                    val request by viewRequests.request.collectAsState()
                    val launcherReturns by viewRequests.launcherReturns.collectAsState()
                    AppNavHost(
                        viewRequest = request,
                        onViewRequestHandled = viewRequests::handled,
                        // In-app opens (a recent file, a tool's saved result) take the same path
                        // as "Open with", with no intent: never onNewIntent or the intent scrub.
                        onOpenDocument = { uri -> viewRequests.post(uri, fromOtherApp = false) },
                        onReturnToCaller = { returnToCaller() },
                        launcherReturns = launcherReturns,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        val document = documentUri(intent)
        // Read before the scrub, which turns every intent into a launcher one.
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        super.onNewIntent(launchIntentOnly(intent))
        if (document != null) {
            viewRequests.post(document.uri, document.typeVerified, fromOtherApp = true)
        } else if (fromLauncher) {
            // The user came back through Offkit's icon (when the system delivers it here: it
            // does not when the task is only brought to the front, nor from Recents). Back from a
            // reader opened by another app then stays in Offkit.
            viewRequests.launcherReturned()
        }
    }

    /** A document [documentUri] accepted; [typeVerified] is false when the intent named no type. */
    private class Received(val uri: Uri, val typeVerified: Boolean)

    /**
     * The PDF to open from [received], or null. Only `ACTION_VIEW` of a `content:` Uri whose
     * intent type is `application/pdf`, or absent, counts; any other type is dropped at once.
     * With no type the provider is not asked here (a slow or hostile provider would block the
     * main thread): the reader asks it on the IO dispatcher before copying anything. Everything
     * else on the intent (extras, clip data, categories, flags) is ignored. Only a read is ever
     * done with the Uri, so a write or persistable grant on the intent is never used.
     *
     * An intent relaunched from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) is never a
     * request: after Back finished the task's root (API 26-30) or after process death, the system
     * redelivers the original VIEW with no saved state, and its document was not asked for again.
     */
    private fun documentUri(received: Intent?): Received? {
        if (received?.action != Intent.ACTION_VIEW) return null
        if (received.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val uri = received.data ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val type = received.type ?: return Received(uri, typeVerified = false)
        return if (Intent.normalizeMimeType(type) == MIME_PDF) Received(uri, typeVerified = true) else null
    }

    private fun launchIntentOnly(received: Intent?): Intent {
        val keptFlags = (received?.flags ?: 0) and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            ).inv()
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(received?.component)
            .setFlags(keptFlags)
    }
}

/**
 * The PDF this activity was asked to open ("Open with", or a recent file tapped on home), until
 * the reader takes it. In memory
 * only: it survives rotation and the introduction, never process death (the grant would be gone).
 * [launcherReturns] counts launcher intents delivered to [MainActivity.onNewIntent].
 */
class ViewRequests : ViewModel() {
    private var next = 0L
    private val _request = MutableStateFlow<ViewRequest?>(null)

    val request: StateFlow<ViewRequest?> = _request.asStateFlow()

    private val _launcherReturns = MutableStateFlow(0)

    /** Bumped each time the user comes back to Offkit through its launcher icon. */
    val launcherReturns: StateFlow<Int> = _launcherReturns.asStateFlow()

    /** Asks the reader to open [uri]; [fromOtherApp]: "Open with" from another app (see [ViewRequest]). */
    fun post(uri: Uri, typeVerified: Boolean = true, fromOtherApp: Boolean = false) {
        _request.value = ViewRequest(uri, ++next, typeVerified, fromOtherApp)
    }

    fun launcherReturned() {
        _launcherReturns.value++
    }

    /** The reader took [request]; a newer request posted meanwhile is kept. */
    fun handled(request: ViewRequest) {
        if (_request.value?.id == request.id) _request.value = null
    }
}
