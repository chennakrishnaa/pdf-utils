package com.offlinepdf.utils

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.offlinepdf.utils.ui.nav.AppNavHost
import com.offlinepdf.utils.ui.theme.PdfUtilsTheme

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
