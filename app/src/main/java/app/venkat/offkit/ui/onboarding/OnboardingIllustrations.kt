package app.venkat.offkit.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import kotlin.math.abs
import kotlin.math.min

/** Brand colours of the launcher icon; the introduction's hero is a brand moment, never dynamic colour. */
internal object OnboardingBrand {
    val HeroLight = listOf(Color(0xFF5B82C6), Color(0xFF415F91), Color(0xFF284777))
    val Ink = Color(0xFF284777)
    val Coral = Color(0xFFFF7A59)
    val Amber = Color(0xFFFFC857)
    val Shadow = Color(0xFF0B1A3A)
}

/** Design size of every illustration; it is scaled uniformly to fit the hero. */
private val StageWidth = 280.dp
private val StageHeight = 220.dp

/**
 * Motion inputs of one page's illustration. [pageOffset] is the page's signed distance from the
 * settled position (0 = centred), [entrance] the one-time entrance progress (0..1), [travelPx] how
 * far (in stage pixels) a full page swipe moves. With [enabled] false everything is static.
 */
@Stable
internal class HeroMotion(
    val enabled: Boolean,
    val pageOffset: () -> Float,
    val entrance: () -> Float,
    val travelPx: Float,
    val direction: Float,
    val tile: Color,
)

/**
 * A layer of the illustration. [depth] is how much the layer lags behind the page while swiping
 * (0 = moves with the page, 0.6 = background, negative = foreground that moves faster); [order]
 * staggers the entrance. [rotation] is static.
 */
private fun Modifier.layer(motion: HeroMotion, depth: Float, order: Int, rotation: Float = 0f): Modifier =
    graphicsLayer {
        rotationZ = rotation
        if (!motion.enabled) return@graphicsLayer
        translationX = motion.direction * motion.pageOffset() * motion.travelPx * depth
        val p = ((motion.entrance() - order * 0.09f) / 0.55f).coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        alpha = eased
        translationY = (1f - eased) * 28.dp.toPx()
        val s = 0.9f + 0.1f * eased
        scaleX = s
        scaleY = s
    }

/**
 * The illustration of page [page] (1-based), centred and scaled to fit its parent. Decorative:
 * excluded from semantics, except that page 3's badges are announced once as text.
 */
@Composable
internal fun HeroIllustration(
    page: Int,
    enabled: Boolean,
    pageOffset: () -> Float,
    entrance: () -> Float,
    pageWidthPx: Float,
    rtl: Boolean,
    modifier: Modifier = Modifier,
) {
    val badges = listOf(
        stringResource(R.string.onboarding_private_badge_internet),
        stringResource(R.string.onboarding_private_badge_permissions),
        stringResource(R.string.onboarding_private_badge_ads),
    )
    val semantics = if (page == 3) {
        val text = badges.joinToString(", ")
        Modifier.clearAndSetSemantics { contentDescription = text }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    BoxWithConstraints(modifier.then(semantics), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth / StageWidth, maxHeight / StageHeight).coerceIn(0.3f, 1.35f)
        val motion = HeroMotion(
            enabled = enabled,
            pageOffset = pageOffset,
            entrance = entrance,
            travelPx = pageWidthPx / scale,
            direction = if (rtl) -1f else 1f,
            tile = Color.White,
        )
        Box(
            Modifier
                .requiredSize(StageWidth, StageHeight)
                .graphicsLayer {
                    // Entering and leaving pages shrink and fade a little.
                    val distance = if (enabled) abs(pageOffset()).coerceIn(0f, 1f) else 0f
                    scaleX = scale * (1f - 0.1f * distance)
                    scaleY = scale * (1f - 0.1f * distance)
                    alpha = 1f - 0.6f * distance
                },
        ) {
            when (page) {
                1 -> WelcomeArt(motion)
                2 -> ToolsArt(motion)
                3 -> PrivacyArt(motion, badges)
                else -> ControlArt(motion)
            }
        }
    }
}

/** A soft, translucent disc that fades out at its rim. */
@Composable
private fun Blob(x: Dp, y: Dp, diameter: Dp, color: Color, motion: HeroMotion, depth: Float = 0.6f, order: Int = 0) {
    Box(
        Modifier
            .absoluteOffset(x, y)
            .size(diameter)
            .layer(motion, depth, order)
            .background(color, CircleShape),
    )
}

/** A thin translucent ring, a calm background accent. */
@Composable
private fun Ring(x: Dp, y: Dp, diameter: Dp, motion: HeroMotion) {
    Box(
        Modifier
            .absoluteOffset(x, y)
            .size(diameter)
            .layer(motion, 0.6f, 0)
            .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape),
    )
}

/** A rounded tile with a soft shadow, holding an optional glyph or custom content. */
@Composable
private fun Tile(
    x: Dp,
    y: Dp,
    width: Dp,
    motion: HeroMotion,
    depth: Float,
    order: Int,
    rotation: Float = 0f,
    height: Dp = width,
    color: Color = motion.tile,
    glyph: Painter? = null,
    glyphTint: Color = OnboardingBrand.Ink,
    corner: Dp = min(width.value, height.value).dp * 0.28f,
    elevation: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = Modifier
            .absoluteOffset(x, y)
            .size(width, height)
            .layer(motion, depth, order, rotation)
            .shadow(elevation, shape, ambientColor = OnboardingBrand.Shadow, spotColor = OnboardingBrand.Shadow)
            .background(color, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(min(width.value, height.value).dp * 0.48f))
        }
        content()
    }
}

/** A coral disc with a white check mark. */
@Composable
private fun CheckBadge(x: Dp, y: Dp, size: Dp, motion: HeroMotion, depth: Float, order: Int) {
    Box(
        Modifier
            .absoluteOffset(x, y)
            .size(size)
            .layer(motion, depth, order)
            .shadow(10.dp, CircleShape, ambientColor = OnboardingBrand.Shadow, spotColor = OnboardingBrand.Shadow)
            .background(OnboardingBrand.Coral, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Page 1: the 2x2 tile mark, large and slightly fanned out, over soft discs and the amber sun. */
@Composable
private fun WelcomeArt(m: HeroMotion) {
    Blob(-40.dp, -20.dp, 200.dp, Color.White.copy(alpha = 0.08f), m, depth = 0.65f)
    Ring(176.dp, 70.dp, 120.dp, m)
    Blob(14.dp, 176.dp, 14.dp, OnboardingBrand.Coral, m, depth = 0.5f, order = 2)
    Box(
        Modifier
            .absoluteOffset(222.dp, 6.dp)
            .size(34.dp)
            .layer(m, 0.45f, 2)
            .background(OnboardingBrand.Amber, CircleShape),
    )
    Tile(54.dp, 22.dp, 80.dp, m, depth = 0.3f, order = 1, rotation = -8f, glyph = AppIcons.pdf)
    Tile(146.dp, 14.dp, 80.dp, m, depth = 0.22f, order = 2, rotation = 4f, glyph = AppIcons.image)
    Tile(60.dp, 114.dp, 80.dp, m, depth = 0.18f, order = 3, rotation = 3f, glyph = AppIcons.compress)
    Tile(
        150.dp, 106.dp, 84.dp, m, depth = -0.08f, order = 4, rotation = -6f,
        color = OnboardingBrand.Coral, glyph = AppIcons.merge, glyphTint = Color.White, elevation = 20.dp,
    )
}

/** Page 2: the four tool glyphs as floating tiles in a staggered cluster. */
@Composable
private fun ToolsArt(m: HeroMotion) {
    Blob(150.dp, 0.dp, 170.dp, Color.White.copy(alpha = 0.08f), m, depth = 0.65f)
    Ring(-30.dp, 90.dp, 120.dp, m)
    Blob(238.dp, 18.dp, 22.dp, OnboardingBrand.Amber, m, depth = 0.5f, order = 2)
    Tile(26.dp, 28.dp, 76.dp, m, depth = 0.32f, order = 1, rotation = -10f, glyph = AppIcons.image)
    Tile(128.dp, 4.dp, 64.dp, m, depth = 0.4f, order = 2, rotation = 6f, glyph = AppIcons.compress)
    Tile(
        104.dp, 92.dp, 92.dp, m, depth = 0.12f, order = 3, rotation = -4f,
        color = OnboardingBrand.Coral, glyph = AppIcons.merge, glyphTint = Color.White, elevation = 20.dp,
    )
    Tile(210.dp, 76.dp, 62.dp, m, depth = -0.06f, order = 4, rotation = 9f, glyph = AppIcons.addToPdf)
    Tile(36.dp, 134.dp, 54.dp, m, depth = 0.24f, order = 5, rotation = 7f, glyph = AppIcons.pdf)
}

/** Page 3: a phone made of rounded shapes with a coral check, ringed by three badges. */
@Composable
private fun PrivacyArt(m: HeroMotion, badges: List<String>) {
    Blob(50.dp, 0.dp, 180.dp, Color.White.copy(alpha = 0.08f), m, depth = 0.65f)
    Ring(180.dp, 96.dp, 110.dp, m)
    Blob(226.dp, 26.dp, 18.dp, OnboardingBrand.Amber, m, depth = 0.5f, order = 2)
    Tile(
        92.dp, 8.dp, 96.dp, m, depth = 0.2f, order = 1, height = 168.dp, corner = 24.dp, elevation = 18.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp).background(Color(0xFFDCE6F8), RoundedCornerShape(17.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(width = 24.dp, height = 5.dp).background(Color(0xFFB4C4E2), CircleShape))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(width = 54.dp, height = 7.dp).background(Color(0xFFB4C4E2), CircleShape))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(width = 40.dp, height = 7.dp).background(Color(0xFFB4C4E2), CircleShape))
        }
    }
    CheckBadge(110.dp, 76.dp, 60.dp, m, depth = 0.05f, order = 2)
    Pill(badges[0], 0.dp, 30.dp, m, depth = 0.3f, order = 3, rotation = -4f)
    Pill(badges[1], 144.dp, 112.dp, m, depth = -0.06f, order = 4, rotation = 3f)
    Pill(badges[2], 20.dp, 150.dp, m, depth = 0.16f, order = 5, rotation = -2f)
}

/** A white badge with a coral dot and short ink label; sized in dp so the stage scales as a whole. */
@Composable
private fun Pill(text: String, x: Dp, y: Dp, m: HeroMotion, depth: Float, order: Int, rotation: Float) {
    val shape = RoundedCornerShape(50)
    val textSize = with(LocalDensity.current) { 13.dp.toSp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .absoluteOffset(x, y)
            .layer(m, depth, order, rotation)
            .shadow(10.dp, shape, ambientColor = OnboardingBrand.Shadow, spotColor = OnboardingBrand.Shadow)
            .background(m.tile, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(8.dp).background(OnboardingBrand.Coral, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(fontSize = textSize, lineHeight = textSize, fontWeight = FontWeight.SemiBold, color = OnboardingBrand.Ink),
        )
    }
}

/** Page 4: a document flowing into a saved folder tile with a coral check, and a small recent-files stack. */
@Composable
private fun ControlArt(m: HeroMotion) {
    Blob(150.dp, 0.dp, 170.dp, Color.White.copy(alpha = 0.08f), m, depth = 0.65f)
    Ring(-40.dp, 100.dp, 116.dp, m)
    Blob(214.dp, 186.dp, 16.dp, OnboardingBrand.Amber, m, depth = 0.5f, order = 2)
    // The dashed path from document to folder.
    Canvas(
        Modifier
            .absoluteOffset(0.dp, 0.dp)
            .requiredSize(StageWidth, StageHeight)
            .layer(m, 0.25f, 2),
    ) {
        val u = size.width / 280f
        val path = Path().apply {
            moveTo(100f * u, 52f * u)
            cubicTo(130f * u, 20f * u, 168f * u, 24f * u, 186f * u, 62f * u)
        }
        drawPath(
            path,
            color = Color.White.copy(alpha = 0.85f),
            style = Stroke(width = 3f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * u, 9f * u))),
        )
        val tip = Offset(186f * u, 62f * u)
        drawLine(Color.White.copy(alpha = 0.85f), tip, Offset(tip.x - 9f * u, tip.y - 3f * u), 3f * u, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f), tip, Offset(tip.x + 1f * u, tip.y - 10f * u), 3f * u, StrokeCap.Round)
    }
    // Document.
    Tile(
        24.dp, 24.dp, 78.dp, m, depth = 0.3f, order = 1, rotation = -7f, height = 98.dp, corner = 16.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Icon(AppIcons.pdf, contentDescription = null, tint = OnboardingBrand.Coral, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Line(1f)
            Spacer(Modifier.height(6.dp))
            Line(0.8f)
            Spacer(Modifier.height(6.dp))
            Line(0.55f)
        }
    }
    // Saved tile with check.
    Tile(166.dp, 66.dp, 96.dp, m, depth = 0.12f, order = 3, rotation = 4f, glyph = AppIcons.save, elevation = 20.dp)
    CheckBadge(236.dp, 52.dp, 38.dp, m, depth = -0.08f, order = 4)
    // Recent files stack.
    RecentCard(52.dp, 168.dp, 150.dp, Color(0xFFAFC2E6), m, 5, content = false)
    RecentCard(44.dp, 156.dp, 150.dp, Color(0xFFD3DDF1), m, 5, content = false)
    RecentCard(36.dp, 144.dp, 150.dp, m.tile, m, 6)
}

@Composable
private fun Line(fraction: Float) {
    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Color(0xFFC9D5EC), CircleShape))
}

@Composable
private fun RecentCard(x: Dp, y: Dp, width: Dp, color: Color, m: HeroMotion, order: Int, content: Boolean = true) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .absoluteOffset(x, y)
            .size(width, 40.dp)
            .layer(m, 0.2f, order)
            .shadow(8.dp, shape, ambientColor = OnboardingBrand.Shadow, spotColor = OnboardingBrand.Shadow)
            .background(color, shape)
            .padding(horizontal = 10.dp),
    ) {
        if (!content) return@Row
        Box(Modifier.size(22.dp).background(Color(0xFFFFE3DA), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Icon(AppIcons.pdf, contentDescription = null, tint = OnboardingBrand.Coral, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Line(0.9f)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(5.dp).background(Color(0xFFE1E7F3), CircleShape))
        }
    }
}
