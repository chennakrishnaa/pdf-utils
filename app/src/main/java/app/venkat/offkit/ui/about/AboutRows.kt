package app.venkat.offkit.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitLink
import app.venkat.offkit.ui.theme.OffkitTheme

/** A white card with a 1dp `outlineVariant` border and 20dp corners (SPEC §0 "Cards and lists"). */
@Composable
internal fun Modifier.offkitCard(): Modifier = this
    .clip(MaterialTheme.shapes.medium)
    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.medium)

/** A grouped list: one [offkitCard] holding rows; separate the rows with [GroupDivider]. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth().offkitCard(), content = content)
}

/** The 1dp `outlineVariant` divider between the rows of a [GroupCard]. */
@Composable
internal fun GroupDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A 48dp icon tile with 14dp corners holding a 24dp decorative [icon]. */
@Composable
internal fun IconTile(
    icon: Painter,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .size(OffkitDimens.iconContainer)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OffkitDimens.iconSize))
    }
}

/**
 * A tappable list row of a [GroupCard]: at least 72dp, [IconTile], an optional small [label]
 * above [title] (`titleMedium`; `offkit_link` when [link]), an optional [detail] (`bodyMedium`
 * `onSurfaceVariant`) and a trailing chevron. The whole row is one button whose texts merge;
 * [testTag] goes on it.
 */
@Composable
internal fun ListRow(
    icon: Painter,
    title: String,
    testTag: String,
    onClick: () -> Unit,
    detail: String? = null,
    label: String? = null,
    link: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OffkitDimens.listRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (link) OffkitLink else MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = AppIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(OffkitDimens.iconSizeSmall),
        )
    }
}

@Preview(widthDp = 412)
@Composable
private fun ListRowPreview() {
    OffkitTheme {
        GroupCard(Modifier.padding(16.dp)) {
            ListRow(AppIcons.info, "Licence text", "a", {}, detail = "GNU AGPL v3")
            GroupDivider()
            ListRow(AppIcons.open, "https://example.org", "b", {}, link = true)
        }
    }
}
