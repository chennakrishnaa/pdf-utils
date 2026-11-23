package app.venkat.offkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.venkat.offkit.ui.nav.AppNavHost
import app.venkat.offkit.ui.theme.PdfUtilsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PdfUtilsTheme {
                AppNavHost()
            }
        }
    }
}
