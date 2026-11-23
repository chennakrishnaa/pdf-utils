package app.venkat.offkit.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.venkat.offkit.R
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
 * The primary action: "Next" with an arrow (auto-mirrored in RTL) that widens into
 * "Get started" on the last page. Tagged [TAG_ONBOARDING_NEXT] or [TAG_ONBOARDING_DONE].
 */
@Composable
internal fun PrimaryPagerButton(last: Boolean, animate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 20.dp),
        colors = ButtonDefaults.buttonColors(),
        modifier = modifier
            .heightIn(min = 56.dp)
            .then(if (animate) Modifier.animateContentSize(tween(260)) else Modifier)
            .testTag(if (last) TAG_ONBOARDING_DONE else TAG_ONBOARDING_NEXT),
    ) {
        if (animate) {
            AnimatedContent(
                targetState = last,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(200)) { -it / 2 })
                },
                label = "primaryLabel",
            ) { isLast -> ButtonLabel(isLast) }
        } else {
            ButtonLabel(last)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ButtonLabel(last: Boolean) {
    Text(
        text = stringResource(if (last) R.string.onboarding_done else R.string.onboarding_next),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** "Skip": a quiet text button, light over the hero gradient ([onHero]) or neutral over the sheet. */
@Composable
internal fun SkipButton(onHero: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (onHero) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag(TAG_ONBOARDING_SKIP),
    ) {
        Text(
            stringResource(R.string.onboarding_skip),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}
