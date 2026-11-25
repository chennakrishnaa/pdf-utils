package app.venkat.offkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme

/**
 * One option of an [OffkitRadioGroup] or [OffkitChipRow]. [label] is the title; [description]
 * (second line) and [trailing] (right-aligned, e.g. `size_estimate`) are used by the radio group
 * only. [testTag] goes on the option's clickable row or chip.
 */
data class OffkitOption(
    val label: String,
    val description: String? = null,
    val trailing: String? = null,
    val testTag: String? = null,
)

/**
 * Section header (SPEC §0): [text] in `titleSmall`, inset 8dp from the content column, 24dp
 * above ([OffkitDimens.sectionGap]) and 8dp below, marked as a heading. Pass [first] = true for the
 * first header of a screen: it then has nothing above it (the scaffold's own top padding is the
 * gap under the app bar). With [actionLabel] and [onAction], a trailing [OffkitTextButton]
 * (optional leading [actionIcon], e.g. `ic_add`; tag [actionTestTag]) sits on the right, centred
 * on the title line; the header keeps the same space above and has none below (the 48dp button
 * already pads it). Example: "Images · 3" + "Add". [actionStyle] picks the action's look
 * ([OffkitHeaderActionStyle]; default the text button).
 */
@Composable
fun OffkitSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: Painter? = null,
    actionTestTag: String? = null,
    first: Boolean = false,
    actionStyle: OffkitHeaderActionStyle = OffkitHeaderActionStyle.Text,
) {
    val hasAction = actionLabel != null && onAction != null
    val tonal = hasAction && actionStyle == OffkitHeaderActionStyle.Tonal
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                top = if (first) 0.dp else OffkitDimens.sectionGap,
                bottom = when {
                    tonal -> 4.dp
                    hasAction -> 0.dp
                    else -> 8.dp
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (tonal) {
            OffkitTonalButton(text = actionLabel!!, onClick = onAction!!, icon = actionIcon, testTag = actionTestTag)
        } else if (hasAction) {
            OffkitTextButton(text = actionLabel!!, onClick = onAction!!, icon = actionIcon, testTag = actionTestTag)
        }
    }
}

/** Look of the trailing action of an [OffkitSectionHeader]. */
enum class OffkitHeaderActionStyle {
    /** Link-coloured [OffkitTextButton] (the default). */
    Text,

    /**
     * [OffkitTonalButton] pill (`secondaryContainer`, `onSurface` content, fully rounded, 48dp
     * min): the primary "Add" action of the list screens (Images to PDF, Add images, Merge), an
     * owner-approved deviation from the design pack so it is easy to spot. Keeps 4dp below it.
     */
    Tonal,
}

/**
 * Single-choice radio group (SPEC §0 "Radio row"): one white card (`surfaceContainerLowest`,
 * 1dp `outlineVariant` border, 20dp radius) with one row per [options] entry, separated by 1dp
 * `outlineVariant` dividers. Each row is at least 72dp: a 22dp ring (2dp `outline` border; when
 * selected, ring and 10dp dot are `primary`), the label (`titleMedium`) and description
 * (`bodyMedium`, `onSurfaceVariant`), and an optional trailing text (`labelMedium`, tabular
 * numbers). The selected row's background is `primaryContainer`.
 *
 * Semantics: the card is a `selectableGroup()`; every whole row is
 * `selectable(role = Role.RadioButton)` with its `selected` state and its texts merged, carrying
 * the option's `testTag`. Tapping a row calls [onSelect] with its index (also when already
 * selected). [enabled] = false dims and disables every row.
 */
@Composable
fun OffkitRadioGroup(
    options: List<OffkitOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                if (index > 0) HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.primaryContainer else colors.surfaceContainerLowest)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                        .optionalTestTag(option.testTag)
                        .heightIn(min = OffkitDimens.listRowMinHeight)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .alpha(if (enabled) 1f else 0.38f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioRing(selected)
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                        if (option.description != null) {
                            Text(option.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    if (option.trailing != null) {
                        Text(
                            option.trailing,
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** The 22dp ring (and 10dp dot when selected) of an [OffkitRadioGroup] row. */
@Composable
private fun RadioRing(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(OffkitDimens.controlBorder, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
    }
}

/**
 * Single-select chip row (SPEC §0 "Filter chip"): one fully rounded chip per [options] entry
 * (label only), at least 48dp tall, 16dp side padding, `labelMedium`. Unselected: white with a
 * 1.5dp `outline` border; selected: `onSurface` background, white label and a leading 18dp
 * `ic_check`. Chips are 8dp apart and wrap onto further lines when they do not fit (FlowRow).
 *
 * Semantics: the row is a `selectableGroup()`; each chip is `selectable(role = Role.RadioButton)`
 * with its `selected` state, its label as text and the option's `testTag`. Tapping calls
 * [onSelect] with the chip's index.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OffkitChipRow(
    options: List<OffkitOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .heightIn(min = OffkitDimens.touchTarget)
                    .clip(CircleShape)
                    .background(if (selected) colors.onSurface else colors.surfaceContainerLowest)
                    .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.outline, CircleShape))
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                    .optionalTestTag(option.testTag)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(if (enabled) 1f else 0.38f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val content = if (selected) colors.surfaceContainerLowest else colors.onSurface
                if (selected) {
                    Icon(AppIcons.check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(option.label, style = MaterialTheme.typography.labelMedium, color = content)
            }
        }
    }
}

/**
 * Quiet explanatory note (SPEC §0 "Info note"): an 18dp `ic_info` in `onSurfaceVariant`, a
 * 10dp gap, then [text] in `bodyMedium` `onSurfaceVariant`. No box, inset 8dp to line up with
 * section headers. Semantics: one merged node with [text]; [testTag] goes on it.
 */
@Composable
fun OffkitInfoNote(text: String, modifier: Modifier = Modifier, testTag: String? = null) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) {}
            .optionalTestTag(testTag),
    ) {
        Icon(AppIcons.info, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Font scale from which [OffkitBottomSummary] stacks its text above the button. */
const val BOTTOM_SUMMARY_STACK_FONT_SCALE = 1.5f

/**
 * Bottom-bar layout "two lines of text on the left + action on the right" (SPEC §7, §8): [title]
 * in `titleSmall` and an optional [subtitle] in `bodyMedium` `onSurfaceVariant`, inset 8dp, then
 * [action] (normally an [OffkitPrimaryButton]) with a 12dp gap. Put it in the `bottomBar` of
 * [OffkitScaffold].
 *
 * When `LocalDensity.current.fontScale` is at least [BOTTOM_SUMMARY_STACK_FONT_SCALE] it stacks
 * vertically instead: the text, then the action at full width. [action] receives the modifier
 * to apply to its root (`fillMaxWidth()` when stacked, nothing otherwise). The texts are merged
 * into one semantics node.
 */
@Composable
fun OffkitBottomSummary(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    action: @Composable (Modifier) -> Unit,
) {
    val stacked = LocalDensity.current.fontScale >= BOTTOM_SUMMARY_STACK_FONT_SCALE
    val texts = @Composable { textModifier: Modifier ->
        Column(textModifier.padding(start = 8.dp).semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            texts(Modifier.fillMaxWidth())
            action(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            texts(Modifier.weight(1f))
            action(Modifier)
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitSectionHeaderPreview() {
    OffkitTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            OffkitSectionHeader(stringResource(R.string.section_compression_level))
            OffkitSectionHeader(stringResource(R.string.screen_images), actionLabel = stringResource(R.string.add), onAction = {}, actionIcon = AppIcons.add, actionStyle = OffkitHeaderActionStyle.Tonal)
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitRadioGroupPreview() {
    OffkitTheme {
        OffkitRadioGroup(
            options = listOf(
                OffkitOption(stringResource(R.string.quality_original), stringResource(R.string.quality_original_desc), stringResource(R.string.size_estimate, formatBytes(4_200_000))),
                OffkitOption(stringResource(R.string.quality_low), stringResource(R.string.quality_low_desc), stringResource(R.string.size_estimate, formatBytes(2_100_000))),
                OffkitOption(stringResource(R.string.quality_medium), stringResource(R.string.quality_medium_desc), stringResource(R.string.size_estimate, formatBytes(1_300_000))),
            ),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitChipRowPreview() {
    OffkitTheme {
        OffkitChipRow(
            options = listOf(R.string.page_size_fit, R.string.page_size_a4, R.string.page_size_letter).map { OffkitOption(stringResource(it)) },
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
        )
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitInfoNotePreview() {
    OffkitTheme { OffkitInfoNote(stringResource(R.string.compress_note), Modifier.padding(16.dp)) }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitBottomSummaryPreview() {
    OffkitTheme {
        OffkitBottomSummary(
            title = pluralStringResource(R.plurals.files_count, 3, 3),
            subtitle = stringResource(R.string.merge_order_hint),
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(16.dp),
        ) { m -> OffkitPrimaryButton(stringResource(R.string.action_merge), {}, m, icon = AppIcons.merge) }
    }
}
