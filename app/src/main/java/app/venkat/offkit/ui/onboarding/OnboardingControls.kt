package app.venkat.offkit.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitTextButton
import kotlin.math.abs

/**
 * Pill page indicator: every segment's width and colour follow the pager position continuously,
 * so the active pill stretches across while swiping. Announced as "Page x of 4".
 */
@Composable
internal fun PillIndicator(pager: PagerState, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_page_of, pager.currentPage + 1, ONBOARDING_PAGE_COUNT)
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag(TAG_ONBOARDING_INDICATOR)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(ONBOARDING_PAGE_COUNT) { index ->
            val selected = (1f - abs(position - index)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(width = lerp(8.dp, 28.dp, selected), height = 8.dp)
                    .clip(CircleShape)
                    .background(lerp(idle, active, selected)),
            )
        }
    }
}

/**
 * The primary action, an [OffkitPrimaryButton]: "Next", which widens into "Get started" on the
 * last page (the width change is animated unless [animate] is false). Tagged
 * [TAG_ONBOARDING_NEXT] or [TAG_ONBOARDING_DONE].
 */
@Composable
internal fun PrimaryPagerButton(last: Boolean, animate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OffkitPrimaryButton(
        text = stringResource(if (last) R.string.onboarding_done else R.string.onboarding_next),
        onClick = onClick,
        modifier = modifier.then(if (animate) Modifier.animateContentSize(tween(260)) else Modifier),
        testTag = if (last) TAG_ONBOARDING_DONE else TAG_ONBOARDING_NEXT,
    )
}

/** "Skip": an [OffkitTextButton] (link colour, at least 48dp), tagged [TAG_ONBOARDING_SKIP]. */
@Composable
internal fun SkipButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OffkitTextButton(
        text = stringResource(R.string.onboarding_skip),
        onClick = onClick,
        modifier = modifier,
        testTag = TAG_ONBOARDING_SKIP,
    )
}
