package app.venkat.paperpixel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.venkat.paperpixel.ui.nav.AppNavHost
import app.venkat.paperpixel.ui.theme.PdfUtilsTheme

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
