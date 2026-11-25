package app.venkat.offkit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.venkat.offkit.R

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)

// Five sizes only: 28 / 22 / 16 / 14 / 12 (see README → Type scale)
val OffkitTypography = Typography(
    displaySmall = style(28, 36, FontWeight.ExtraBold, -0.6),   // wordmark, result headline
    headlineSmall = style(28, 36, FontWeight.ExtraBold, -0.6),
    titleLarge = style(22, 28, FontWeight.ExtraBold, -0.3),     // top app bar, dialog title
    titleMedium = style(16, 22, FontWeight.Bold),               // list/card titles
    titleSmall = style(16, 24, FontWeight.ExtraBold),           // section headers
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),              // descriptions, notes
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(16, 20, FontWeight.ExtraBold),           // primary buttons
    labelMedium = style(14, 20, FontWeight.ExtraBold),          // text buttons, chips
    labelSmall = style(12, 16, FontWeight.ExtraBold),           // page badges
)
