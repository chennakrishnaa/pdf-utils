package app.venkat.offkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import app.venkat.offkit.R

/**
 * The app's icon set: 24dp single-colour vector drawables (`res/drawable/ic_*.xml`, Material
 * shapes), tinted by whatever draws them (`Icon` uses `LocalContentColor` by default).
 */
object AppIcons {
    val add: Painter @Composable get() = painterResource(R.drawable.ic_add)
    val image: Painter @Composable get() = painterResource(R.drawable.ic_image)
    val pdf: Painter @Composable get() = painterResource(R.drawable.ic_pdf)
    val compress: Painter @Composable get() = painterResource(R.drawable.ic_compress)
    val merge: Painter @Composable get() = painterResource(R.drawable.ic_merge)
    val addToPdf: Painter @Composable get() = painterResource(R.drawable.ic_add_to_pdf)
    val save: Painter @Composable get() = painterResource(R.drawable.ic_save)
    val share: Painter @Composable get() = painterResource(R.drawable.ic_share)
    val delete: Painter @Composable get() = painterResource(R.drawable.ic_delete)
    val open: Painter @Composable get() = painterResource(R.drawable.ic_open)
    val info: Painter @Composable get() = painterResource(R.drawable.ic_info)
    val check: Painter @Composable get() = painterResource(R.drawable.ic_check)
    val arrowUp: Painter @Composable get() = painterResource(R.drawable.ic_arrow_up)
    val arrowDown: Painter @Composable get() = painterResource(R.drawable.ic_arrow_down)
    val close: Painter @Composable get() = painterResource(R.drawable.ic_close)
    val history: Painter @Composable get() = painterResource(R.drawable.ic_history)
    val chevronRight: Painter @Composable get() = painterResource(R.drawable.ic_chevron_right)
    val star: Painter @Composable get() = painterResource(R.drawable.ic_star)
    val mail: Painter @Composable get() = painterResource(R.drawable.ic_mail)
    val heart: Painter @Composable get() = painterResource(R.drawable.ic_heart)
    val feedback: Painter @Composable get() = painterResource(R.drawable.ic_feedback)
    val code: Painter @Composable get() = painterResource(R.drawable.ic_code)
}
