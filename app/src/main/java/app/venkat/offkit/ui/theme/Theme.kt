package app.venkat.offkit.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Offkit theme (Option B, light). Call enableEdgeToEdge() in your Activity and use
 * Scaffold/WindowInsets padding so headers and bottom action bars clear the system bars.
 * Dark theme is not designed yet — this theme is light-only on purpose.
 */
@Composable
fun OffkitTheme(content: @Composable () -> Unit) {
    DarkSystemBarIcons()
    MaterialTheme(
        colorScheme = OffkitLightColors,
        typography = OffkitTypography,
        shapes = OffkitShapes,
        content = content,
    )
}

/**
 * The background is always light, but enableEdgeToEdge()'s default style follows the system
 * dark setting. Force dark status- and navigation-bar icons so they stay readable. A screen
 * that sits on a dark surface (onboarding) may still switch them for as long as it is shown.
 */
@Composable
private fun DarkSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        onDispose { }
    }
}
