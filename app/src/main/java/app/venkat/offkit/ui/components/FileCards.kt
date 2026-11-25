package app.venkat.offkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme

/**
 * The dashed outline of the "add" affordances ([OffkitFileSlot], the Add tile of
 * [OffkitThumbnailRow], the Add row of [OffkitSelectableFileList]): a 2dp ([OffkitDimens.controlBorder])
 * [color] stroke, 6dp dashes with 4dp gaps, drawn inside the bounds with corner [radius]. Clip to
 * the same radius before it.
 */
internal fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val w = OffkitDimens.controlBorder.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/**
 * Empty file slot (SPEC §2): a dashed card (2dp `outline` dash, 20dp radius, white) at least
 * 120dp tall, centring a 48dp `primary` circle with `ic_add`, [title] (`titleSmall`, e.g.
 * `choose_pdf`) and [hint] (`bodyMedium` `onSurfaceVariant`, e.g. `choose_pdf_hint`).
 *
 * The whole card is clickable with `Role.Button` and calls [onClick] (open the picker there);
 * its texts merge into the button's node. [testTag] goes on the card.
 */
@Composable
fun OffkitFileSlot(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val dash = colors.outline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(shape)
            .background(colors.surfaceContainerLowest)
            .dashedBorder(dash, OffkitDimens.cardRadius)
            .clickable(role = Role.Button, onClick = onClick)
            .optionalTestTag(testTag)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier.size(OffkitDimens.iconContainer).background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.add, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(OffkitDimens.iconSize))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A chosen file (SPEC §3 "File card"): white card (1dp `outlineVariant` border, 20dp radius)
 * with a 48dp `secondaryContainer` tile holding [icon] (default `ic_pdf_file`), [name]
 * (`titleMedium`, one line, ellipsised) and an optional [detail] line (`bodyMedium`
 * `onSurfaceVariant`, e.g. the size), then an optional trailing [OffkitTonalButton]
 * ([actionLabel], e.g. `change`; [onAction]; tag [actionTestTag]).
 *
 * Semantics: the name and detail merge into one node (not clickable); the action is a separate
 * button. [testTag] goes on the card.
 */
@Composable
fun OffkitFileCard(
    name: String,
    detail: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String? = null,
    icon: Painter = AppIcons.pdfFile,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth().optionalTestTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(OffkitDimens.iconContainer).background(colors.secondaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(OffkitDimens.iconSize))
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (actionLabel != null && onAction != null) {
                OffkitTonalButton(text = actionLabel, onClick = onAction, testTag = actionTestTag)
            }
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitFileSlotPreview() {
    OffkitTheme {
        OffkitFileSlot(stringResource(R.string.choose_pdf), stringResource(R.string.choose_pdf_hint), onClick = {}, modifier = Modifier.padding(16.dp))
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitFileCardPreview() {
    OffkitTheme {
        OffkitFileCard(
            name = stringResource(R.string.screen_compress),
            detail = formatBytes(1_500_000),
            actionLabel = stringResource(R.string.change),
            onAction = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
