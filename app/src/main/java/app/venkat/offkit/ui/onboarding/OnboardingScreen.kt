package app.venkat.offkit.ui.onboarding

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.offkitCard
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool
import kotlinx.coroutines.launch

const val TAG_ONBOARDING_ROOT = "onboarding_root"
const val TAG_ONBOARDING_SKIP = "onboarding_skip"
const val TAG_ONBOARDING_NEXT = "onboarding_next"
const val TAG_ONBOARDING_DONE = "onboarding_done"
const val TAG_ONBOARDING_INDICATOR = "onboarding_indicator"

/** Number of introduction pages. */
const val ONBOARDING_PAGE_COUNT = 4

/** Test tag of introduction page [number] (1-based). */
fun onboardingPageTag(number: Int) = "onboarding_page_$number"

/** Test tag of the title of introduction page [number] (1-based). */
fun onboardingTitleTag(number: Int) = "onboarding_title_$number"

/** True when the system animator duration scale is 0 ("Remove animations" / animations off). */
internal fun onboardingReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Corner radius of the content sheet where it meets the hero (the 28dp sheet radius of the design). */
private val SheetRadius = OffkitDimens.dialogRadius

/**
 * The introduction: four swipeable pages, each an illustration on the light paper `background`
 * above a white content sheet with rounded top corners (system-bar icons stay dark). "Skip" (top end, not on the last page), a pill indicator and "Next" / "Get started".
 * Skip, Get started and system Back on the first page call [onFinished]; Back on a later page goes to
 * the previous page. The current page survives rotation and process death (the pager state is
 * saveable). With animations turned off in system settings the illustrations are static.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { ONBOARDING_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val last = ONBOARDING_PAGE_COUNT - 1
    val onLastPage = pager.currentPage == last
    val context = LocalContext.current
    val reducedMotion = remember { onboardingReducedMotion(context) }

    // One-time staggered entrance (not replayed after rotation).
    var entrancePlayed by rememberSaveable { mutableStateOf(reducedMotion) }
    val entrance = remember { Animatable(if (entrancePlayed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!entrancePlayed) {
            entrance.animateTo(1f, tween(1100, easing = LinearEasing))
            entrancePlayed = true
        }
    }

    BackHandler {
        if (pager.targetPage > 0) {
            scope.launch {
                if (reducedMotion) pager.scrollToPage(pager.targetPage - 1) else pager.animateScrollToPage(pager.targetPage - 1)
            }
        } else {
            onFinished()
        }
    }

    val pageColor = MaterialTheme.colorScheme.background
    val sheetColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    BoxWithConstraints(Modifier.fillMaxSize().background(pageColor).testTag(TAG_ONBOARDING_ROOT)) {
        val landscape = maxWidth > maxHeight
        val fontScale = density.fontScale
        val compact = maxHeight < 700.dp || fontScale >= 1.3f
        val heroHeight = if (landscape) maxHeight else maxHeight * (if (compact) 0.36f else 0.5f)
        val heroWidth = if (landscape) maxWidth * 0.44f else maxWidth
        val pageWidthPx = with(density) { maxWidth.toPx() }
        var barHeightPx by remember { mutableIntStateOf(0) }
        val barHeight = with(density) { barHeightPx.toDp() }

        // Fixed backdrop: the white sheet that overlaps the paper-coloured hero area.
        Box(
            Modifier
                .then(
                    if (landscape) {
                        Modifier.fillMaxHeight().width(maxWidth - heroWidth).align(Alignment.CenterEnd)
                            .background(sheetColor, RoundedCornerShape(topStart = SheetRadius, bottomStart = SheetRadius))
                    } else {
                        Modifier.fillMaxWidth().height(maxHeight - heroHeight + SheetRadius).align(Alignment.BottomCenter)
                            .background(sheetColor, RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius))
                    },
                ),
        )

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { index ->
            val number = index + 1
            val pageOffset = { (pager.currentPage - index) + pager.currentPageOffsetFraction }
            val hero = @Composable { modifier: Modifier ->
                HeroIllustration(
                    page = number,
                    enabled = !reducedMotion,
                    pageOffset = pageOffset,
                    entrance = { entrance.value },
                    pageWidthPx = pageWidthPx,
                    rtl = rtl,
                    modifier = modifier,
                )
            }
            val content = @Composable { modifier: Modifier ->
                PageContent(
                    number = number,
                    compact = compact,
                    entrance = { if (reducedMotion) 1f else entrance.value },
                    bottomPadding = barHeight,
                    modifier = modifier,
                )
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().testTag(onboardingPageTag(number))) {
                    hero(
                        Modifier.width(heroWidth).fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                            .padding(24.dp),
                    )
                    content(
                        Modifier.weight(1f).fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Top))
                            .padding(top = 40.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().testTag(onboardingPageTag(number))) {
                    hero(
                        Modifier.fillMaxWidth().height(heroHeight)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = SheetRadius + 8.dp),
                    )
                    content(
                        Modifier.weight(1f).fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    )
                }
            }
        }

        if (!onLastPage) {
            SkipButton(
                onClick = onFinished,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(top = 4.dp, end = 8.dp),
            )
        }

        // Bottom bar on the sheet: indicator at the start, primary action at the end, over a short fade.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .then(if (landscape) Modifier.width(maxWidth - heroWidth) else Modifier.fillMaxWidth())
                .padding(bottom = barHeight)
                .height(24.dp)
                .background(Brush.verticalGradient(listOf(sheetColor.copy(alpha = 0f), sheetColor))),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(if (landscape) Modifier.width(maxWidth - heroWidth) else Modifier.fillMaxWidth())
                .background(sheetColor)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + if (landscape) WindowInsetsSides.End else WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
                .onSizeChanged { barHeightPx = it.height },
        ) {
            PillIndicator(pager)
            Spacer(Modifier.weight(1f))
            PrimaryPagerButton(
                last = onLastPage,
                animate = !reducedMotion,
                onClick = {
                    if (onLastPage) {
                        onFinished()
                    } else {
                        scope.launch {
                            val target = (pager.targetPage + 1).coerceAtMost(last)
                            if (reducedMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target)
                        }
                    }
                },
            )
        }
    }
}

/** The text part of a page on the sheet: title, one sentence and page-specific extras; scrolls when needed. */
@Composable
private fun PageContent(number: Int, compact: Boolean, entrance: () -> Float, bottomPadding: Dp, modifier: Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 0.dp, bottom = bottomPadding + 16.dp)
            .widthIn(max = 560.dp)
            .graphicsLayer {
                val p = ((entrance() - 0.25f) / 0.6f).coerceIn(0f, 1f)
                val eased = 1f - (1f - p) * (1f - p)
                alpha = eased
                translationY = (1f - eased) * 16.dp.toPx()
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (number) {
            1 -> {
                PageTitle(1, stringResource(R.string.onboarding_welcome_title), compact)
                Body(stringResource(R.string.onboarding_welcome_text))
            }
            2 -> {
                PageTitle(2, stringResource(R.string.onboarding_tools_title), compact)
                Spacer(Modifier.height(4.dp))
                ToolGrid()
            }
            3 -> {
                PageTitle(3, stringResource(R.string.onboarding_private_title), compact)
                Body(stringResource(R.string.onboarding_private_text))
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PointRow(AppIcons.check, stringResource(R.string.onboarding_private_accounts))
                    PointRow(AppIcons.image, stringResource(R.string.onboarding_private_metadata))
                }
            }
            else -> {
                PageTitle(4, stringResource(R.string.onboarding_control_title), compact)
                Body(stringResource(R.string.onboarding_control_choose))
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PointRow(AppIcons.history, stringResource(R.string.onboarding_control_recent))
                    PointRow(AppIcons.check, stringResource(R.string.onboarding_control_nothing))
                }
            }
        }
    }
}

@Composable
private fun PageTitle(number: Int, text: String, compact: Boolean) {
    Text(
        text = text,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .testTag(onboardingTitleTag(number)),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A short point: a small tinted icon tile (14dp corners) and one line of text, read as one node. */
@Composable
private fun PointRow(icon: Painter, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = OffkitDimens.touchTarget).semantics(mergeDescendants = true) {},
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(OffkitDimens.iconSizeSmall))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** Page 2: the four tools as a compact 2x2 grid of white cards (tool glyph on its accent tile, name, short benefit). */
@Composable
private fun ToolGrid() {
    val tools = listOf(
        Triple(OffkitTool.IMAGES, R.string.tool_images_to_pdf_title, R.string.onboarding_tools_images),
        Triple(OffkitTool.COMPRESS, R.string.tool_compress_title, R.string.onboarding_tools_compress),
        Triple(OffkitTool.MERGE, R.string.tool_merge_title, R.string.onboarding_tools_merge),
        Triple(OffkitTool.ADD_PAGES, R.string.tool_add_images_title, R.string.onboarding_tools_add),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (tool, name, benefit) ->
                    ToolCard(
                        tool = tool,
                        name = stringResource(name),
                        benefit = stringResource(benefit),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: OffkitTool, name: String, benefit: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .offkitCard()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(tool.container, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(tool.icon), contentDescription = null, tint = tool.onContainer, modifier = Modifier.size(OffkitDimens.iconSize))
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(benefit, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Preview(widthDp = 412, heightDp = 860)
@Composable
private fun OnboardingPreview() {
    OffkitTheme { OnboardingScreen(onFinished = {}) }
}

@Preview(widthDp = 860, heightDp = 412)
@Composable
private fun OnboardingLandscapePreview() {
    OffkitTheme { OnboardingScreen(onFinished = {}) }
}

@Preview(widthDp = 412, heightDp = 640, fontScale = 2f)
@Composable
private fun OnboardingLargeFontPreview() {
    OffkitTheme { OnboardingScreen(onFinished = {}) }
}
