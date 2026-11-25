package app.venkat.offkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.onboarding.OnboardingScreen
import app.venkat.offkit.ui.onboarding.OnboardingStore
import app.venkat.offkit.ui.theme.OffkitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app takes no input through intents: drop data, extras and grants another app may
        // have attached, before the navigation host could read them as a deep link (SB-13).
        intent = launchIntentOnly(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(launchIntentOnly(intent))
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
