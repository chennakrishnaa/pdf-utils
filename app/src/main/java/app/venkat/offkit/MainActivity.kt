package app.venkat.offkit

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
import app.venkat.offkit.ui.theme.PdfUtilsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val onboarding = OnboardingStore(this)
        // Read before the first frame, so neither the introduction nor home flashes.
        val firstRun = !onboarding.seen
        setContent {
            PdfUtilsTheme {
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
}
