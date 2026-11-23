package app.venkat.filetools.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Minimum height of [PrimaryActionButton]. */
private val PrimaryButtonMinHeight = 52.dp

/** Minimum height of [SecondaryActionButton] (also the minimum touch target). */
private val SecondaryButtonMinHeight = 48.dp

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

/** Small section title used to group controls within a tool screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** Leading icon + label used inside the action buttons. */
@Composable
private fun ButtonContent(text: String, icon: Painter?) {
    if (icon != null) {
        Icon(painter = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
}

/**
 * The screen's main call to action: a full-width filled button, at least 52dp tall, with a
 * large rounded shape and an optional leading [icon]. [testTag] goes on the button.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = ButtonPadding,
        modifier = modifier.fillMaxWidth().heightIn(min = PrimaryButtonMinHeight).testTag(testTag),
    ) {
        ButtonContent(text, icon)
    }
}

/**
 * A full-width filled-tonal button for supporting actions ("Add images", "Choose PDF"),
 * at least 48dp tall, with an optional leading [icon]. [testTag] goes on the button.
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = ButtonPadding,
        modifier = modifier.fillMaxWidth().heightIn(min = SecondaryButtonMinHeight).testTag(testTag),
    ) {
        ButtonContent(text, icon)
    }
}

/**
 * A selectable card for one option of a single choice (compression level, page size).
 *
 * Selected: primary border, tinted container and a filled check indicator. [subtitle] is shown
 * under [title]; [trailing] is right-aligned emphasised text (e.g. "≈ 1.2 MB"). The card itself
 * carries [testTag], the click, `Role.RadioButton` and the `selected` state. Place several in a
 * [ChoiceGroup].
 */
@Composable
fun ChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = if (selected) colors.primaryContainer.copy(alpha = 0.55f) else colors.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (enabled) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceIndicator(selected)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Round indicator of a [ChoiceCard]: a filled circle with a check when selected, a ring otherwise. */
@Composable
private fun ChoiceIndicator(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AppIcons.check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, colors.outline),
            modifier = Modifier.size(24.dp),
        ) {}
    }
}

/** Vertical group of [ChoiceCard]s with consistent spacing and `selectableGroup()` semantics. */
@Composable
fun ChoiceGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A quiet tinted note with an info icon, for explanatory lines. [testTag] (if given) goes on the
 * note, which merges its text into one node (so `assertTextEquals(text)` works on it).
 */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, testTag: String? = null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(
                painter = AppIcons.info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rounded tinted square holding an icon; used as the leading badge of rows and cards. */
@Composable
internal fun IconBadge(
    icon: Painter,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Surface(shape = MaterialTheme.shapes.small, color = container, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter = icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * Shows one chosen file: an icon badge ([icon], default [AppIcons.pdf]), the file [name] and an
 * optional [detail] line (e.g. its size). [testTag] (if given) goes on the card, which merges
 * its texts into one node.
 */
@Composable
fun SelectedFileCard(
    name: String,
    detail: String?,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    icon: Painter? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon ?: AppIcons.pdf)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
