package app.venkat.offkit.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.IconBadge
import app.venkat.offkit.ui.components.PrimaryActionButton
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

/**
 * The introduction: four swipeable pages with page dots, "Skip" (top end, not on the last page),
 * "Next" and, on the last page, "Get started". Skip, Get started and system Back on the first page
 * call [onFinished]; Back on a later page goes to the previous page. The current page survives
 * rotation and process death (the pager state is saveable).
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { ONBOARDING_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val last = ONBOARDING_PAGE_COUNT - 1
    val onLastPage = pager.currentPage == last

    BackHandler {
        if (pager.targetPage > 0) {
            scope.launch { pager.animateScrollToPage(pager.targetPage - 1) }
        } else {
            onFinished()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize().testTag(TAG_ONBOARDING_ROOT),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Keeps its height when Skip is hidden, so the pages do not jump on the last page.
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (!onLastPage) {
                    TextButton(
                        onClick = onFinished,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_ONBOARDING_SKIP),
                    ) {
                        Text(stringResource(R.string.onboarding_skip), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) { index ->
                OnboardingPage(number = index + 1)
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PageIndicator(pager)
                if (onLastPage) {
                    PrimaryActionButton(
                        text = stringResource(R.string.onboarding_done),
                        onClick = onFinished,
                        enabled = true,
                        testTag = TAG_ONBOARDING_DONE,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                } else {
                    PrimaryActionButton(
                        text = stringResource(R.string.onboarding_next),
                        onClick = {
                            scope.launch { pager.animateScrollToPage((pager.targetPage + 1).coerceAtMost(last)) }
                        },
                        enabled = true,
                        testTag = TAG_ONBOARDING_NEXT,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                }
            }
        }
    }
}

/** Dots for the pages; the current one is a wider pill. Announced as "Page x of 4". */
@Composable
private fun PageIndicator(pager: PagerState) {
    val description = stringResource(R.string.onboarding_page_of, pager.currentPage + 1, ONBOARDING_PAGE_COUNT)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag(TAG_ONBOARDING_INDICATOR)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(ONBOARDING_PAGE_COUNT) { index ->
            val selected = index == pager.currentPage
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotWidth")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor",
            )
            Box(Modifier.size(width = width, height = 8.dp).clip(CircleShape).background(color))
        }
    }
}

/** One page: illustration, heading and body; scrolls vertically when it does not fit. */
@Composable
private fun OnboardingPage(number: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag(onboardingPageTag(number))
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                when (number) {
                    1 -> WelcomePage()
                    2 -> ToolsPage()
                    3 -> PrivacyPage()
                    else -> ControlPage()
                }
            }
        }
    }
}

@Composable
private fun PageTitle(number: Int, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { heading() }
            .testTag(onboardingTitleTag(number)),
    )
}

@Composable
private fun PageIllustration(icon: Painter, container: Color, content: Color) {
    IconBadge(icon = icon, container = container, content = content, size = 96.dp)
}

@Composable
private fun WelcomePage() {
    // The launcher icon, drawn from its own two layers in a rounded square.
    Box(Modifier.size(112.dp).clip(RoundedCornerShape(28.dp))) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.45f),
        )
    }
    PageTitle(1, stringResource(R.string.onboarding_welcome_title))
    Text(
        text = stringResource(R.string.onboarding_welcome_text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToolsPage() {
    val colors = MaterialTheme.colorScheme
    PageIllustration(AppIcons.pdf, colors.primaryContainer, colors.onPrimaryContainer)
    PageTitle(2, stringResource(R.string.onboarding_tools_title))
    FeatureList {
        FeatureRow(AppIcons.image, stringResource(R.string.onboarding_tools_images), stringResource(R.string.tool_images_to_pdf_title))
        FeatureRow(AppIcons.compress, stringResource(R.string.onboarding_tools_compress), stringResource(R.string.tool_compress_title))
        FeatureRow(AppIcons.merge, stringResource(R.string.onboarding_tools_merge), stringResource(R.string.tool_merge_title))
        FeatureRow(AppIcons.addToPdf, stringResource(R.string.onboarding_tools_add), stringResource(R.string.tool_add_images_title))
    }
}

@Composable
private fun PrivacyPage() {
    val colors = MaterialTheme.colorScheme
    PageIllustration(AppIcons.check, colors.tertiaryContainer, colors.onTertiaryContainer)
    PageTitle(3, stringResource(R.string.onboarding_private_title))
    FeatureList {
        FeatureRow(AppIcons.check, stringResource(R.string.onboarding_private_offline))
        FeatureRow(AppIcons.check, stringResource(R.string.onboarding_private_accounts))
        FeatureRow(AppIcons.check, stringResource(R.string.onboarding_private_files))
        FeatureRow(AppIcons.check, stringResource(R.string.onboarding_private_metadata))
    }
}

@Composable
private fun ControlPage() {
    val colors = MaterialTheme.colorScheme
    PageIllustration(AppIcons.save, colors.secondaryContainer, colors.onSecondaryContainer)
    PageTitle(4, stringResource(R.string.onboarding_control_title))
    FeatureList {
        FeatureRow(AppIcons.open, stringResource(R.string.onboarding_control_choose))
        FeatureRow(AppIcons.history, stringResource(R.string.onboarding_control_recent))
        FeatureRow(AppIcons.check, stringResource(R.string.onboarding_control_nothing))
    }
}

@Composable
private fun FeatureList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** An icon badge and [text], with an optional bold [title] above it. Its texts merge into one node. */
@Composable
private fun FeatureRow(icon: Painter, text: String, title: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (title != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
