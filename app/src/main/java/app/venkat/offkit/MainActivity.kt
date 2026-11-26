package app.venkat.offkit

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private val viewRequests: ViewRequests by viewModels()

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
        document?.let { viewRequests.post(it) }
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
                    AppNavHost(viewRequest = request, onViewRequestHandled = viewRequests::handled)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        val document = documentUri(intent)
        super.onNewIntent(launchIntentOnly(intent))
        document?.let { viewRequests.post(it) }
    }

    /**
     * The PDF to open from [received], or null. Only `ACTION_VIEW` of a `content:` Uri whose type
     * (the intent's, else the provider's) is `application/pdf` counts; everything else on the
     * intent (extras, clip data, categories, flags) is ignored. Only a read is ever done with the
     * Uri, so a write or persistable grant on the intent is never used.
     */
    private fun documentUri(received: Intent?): Uri? {
        if (received?.action != Intent.ACTION_VIEW) return null
        val uri = received.data ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val type = received.type ?: try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        return if (Intent.normalizeMimeType(type) == MIME_PDF) uri else null
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
 * The PDF this activity was asked to open ("Open with"), until the reader takes it. In memory
 * only: it survives rotation and the introduction, never process death (the grant would be gone).
 */
class ViewRequests : ViewModel() {
    private var next = 0L
    private val _request = MutableStateFlow<ViewRequest?>(null)

    val request: StateFlow<ViewRequest?> = _request.asStateFlow()

    fun post(uri: Uri) {
        _request.value = ViewRequest(uri, ++next)
    }

    /** The reader took [request]; a newer request posted meanwhile is kept. */
    fun handled(request: ViewRequest) {
        if (_request.value?.id == request.id) _request.value = null
    }
}
