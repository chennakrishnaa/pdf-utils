package app.venkat.offkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme

/** Test tag of thumbnail tile [index] (0-based) of [OffkitThumbnailRow]. */
fun thumbTag(index: Int): String = "thumb_$index"

/** Test tag of the "Move earlier" button of the [OffkitThumbnailRow] toolbar. */
const val TAG_THUMB_MOVE_EARLIER = "thumb_move_earlier"

/** Test tag of the "Move later" button of the [OffkitThumbnailRow] toolbar. */
const val TAG_THUMB_MOVE_LATER = "thumb_move_later"

/** Test tag of the "Remove" button of the [OffkitThumbnailRow] toolbar. */
const val TAG_THUMB_REMOVE = "thumb_remove"

private val ToolbarShape = RoundedCornerShape(28.dp)
private val TileShape = RoundedCornerShape(16.dp)
private val TileContentShape = RoundedCornerShape(12.dp)

/**
 * Selectable, reorderable image strip (SPEC §7, also §9): a horizontally scrolling row of
 * [count] tiles, each 96×128dp (white, 16dp radius, 1dp `outlineVariant` border; 3dp
 * `primary` border when selected) with 4dp inner padding. [thumbnail] draws tile `index`'s
 * picture inside a box clipped to 12dp radius (use `ContentScale.Crop` and fill the box). A page
 * number badge (`index + 1`; min 24dp, `onSurface`, white `labelSmall`) sits bottom-left.
 *
 * Tapping a tile calls [onSelect] with its index, or with null when it is already selected
 * (deselect). While [selectedIndex] is a valid index, a dark toolbar (`onSurface`, 28dp radius)
 * appears under the row: "Image N" (`cd_image_n`), then 48dp white icon buttons
 * `move_earlier` ([onMoveEarlier], disabled on the first tile), `move_later` ([onMoveLater],
 * disabled on the last) and `remove` ([onRemove]); each receives the selected index. The caller
 * owns the list and the selection and must update [selectedIndex] after a move (to the new
 * position, to keep it selected) or a remove (usually to null).
 *
 * The row and the toolbar get [horizontalPadding] on both sides (pass 0.dp inside
 * [OffkitScaffold]'s padded column). [key] gives each tile a stable identity across moves.
 * Built from a plain Row, so every tile is in the tree (call `performScrollTo()` in tests).
 *
 * Semantics: each tile is `selectable` (sets `selected`), its content description is
 * `cd_image_n`, wrapped in `cd_selected` when selected; the picture and the badge have no
 * semantics of their own. Tags: tile [thumbTag] (`thumb_$index`), toolbar buttons
 * [TAG_THUMB_MOVE_EARLIER], [TAG_THUMB_MOVE_LATER], [TAG_THUMB_REMOVE].
 */
@Composable
fun OffkitThumbnailRow(
    count: Int,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMoveEarlier: (Int) -> Unit,
    onMoveLater: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    key: ((Int) -> Any)? = null,
    thumbnail: @Composable BoxScope.(index: Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selected = selectedIndex?.takeIf { it in 0 until count }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(OffkitDimens.itemGap),
        ) {
            for (index in 0 until count) {
                key(key?.invoke(index) ?: index) {
                    val isSelected = index == selected
                    val imageLabel = stringResource(R.string.cd_image_n, index + 1)
                    val description = if (isSelected) stringResource(R.string.cd_selected, imageLabel) else imageLabel
                    Box(
                        modifier = Modifier
                            .size(OffkitDimens.thumbWidth, OffkitDimens.thumbHeight)
                            .clip(TileShape)
                            .background(colors.surfaceContainerLowest)
                            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) colors.primary else colors.outlineVariant, TileShape)
                            .selectable(selected = isSelected, onClick = { onSelect(if (isSelected) null else index) })
                            .semantics { contentDescription = description }
                            .testTag(thumbTag(index))
                            .padding(4.dp),
                    ) {
                        Box(Modifier.fillMaxSize().clip(TileContentShape).clearAndSetSemantics {}) { thumbnail(index) }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                                .background(colors.onSurface, CircleShape)
                                .padding(horizontal = 6.dp)
                                .clearAndSetSemantics {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = colors.surfaceContainerLowest,
                            )
                        }
                    }
                }
            }
        }
        if (selected != null) {
            DarkToolbar(Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp)) {
                Text(
                    text = stringResource(R.string.cd_image_n, selected + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.surfaceContainerLowest,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                ToolbarIconButton(AppIcons.moveLeft, stringResource(R.string.move_earlier), selected > 0, TAG_THUMB_MOVE_EARLIER, mirror = true) {
                    onMoveEarlier(selected)
                }
                ToolbarIconButton(AppIcons.moveRight, stringResource(R.string.move_later), selected < count - 1, TAG_THUMB_MOVE_LATER, mirror = true) {
                    onMoveLater(selected)
                }
                ToolbarIconButton(AppIcons.delete, stringResource(R.string.remove), true, TAG_THUMB_REMOVE) { onRemove(selected) }
            }
        }
    }
}

/** The dark (`onSurface`, 28dp radius, 4dp padding) toolbar shown under a selected item. */
@Composable
private fun DarkToolbar(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ToolbarShape)
            .background(MaterialTheme.colorScheme.onSurface)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        content = content,
    )
}

/** 48dp white icon button of a [DarkToolbar]; dimmed when disabled. */
@Composable
private fun ToolbarIconButton(
    icon: Painter,
    description: String,
    enabled: Boolean,
    tag: String,
    mirror: Boolean = false,
    onClick: () -> Unit,
) {
    val white = MaterialTheme.colorScheme.surfaceContainerLowest
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = 0.38f)),
        modifier = Modifier.testTag(tag),
    ) {
        Icon(icon, description, modifier = Modifier.size(OffkitDimens.iconSizeSmall).then(if (mirror) Modifier.mirrorInRtl() else Modifier))
    }
}

/** Icon + text button of a [DarkToolbar]; white, dimmed when disabled, at least 48dp tall. */
@Composable
private fun ToolbarTextButton(icon: Painter, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val white = MaterialTheme.colorScheme.surfaceContainerLowest
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = 0.38f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Selectable, reorderable file list (SPEC §8): a column of white cards 8dp apart, one per
 * [rows] entry (existing [FileRow]: `key`, `title`, `subtitle`). Each card is at least 72dp,
 * 20dp radius, 1dp `outlineVariant` border (3dp `primary` when selected), with a 32dp
 * `onSurface` order badge (`index + 1`), the title (`titleMedium`, one line, ellipsised), the
 * subtitle (`bodyMedium` `onSurfaceVariant`, e.g. "3 pages · 1.2 MB") and a decorative
 * `ic_drag_handle` (no semantics; dragging is not implemented).
 *
 * Tapping a card calls [onSelect] with its index, or null when it is already selected. Under the
 * selected card ([selectedIndex]) a dark toolbar shows icon + text buttons `move_up`
 * ([onMoveUp], disabled on the first row), `move_down` ([onMoveDown], disabled on the last) and
 * `remove` ([onRemove]); each receives the selected index. The caller owns the list and the
 * selection and must update [selectedIndex] after a move or a remove.
 *
 * Semantics: each card is `selectable` (sets `selected`), with its texts merged and a content
 * description of the title, wrapped in `cd_selected` when selected, plus the subtitle. Tags (the
 * existing ones of [FileList]): card [fileRowTag], toolbar buttons [fileRowUpTag],
 * [fileRowDownTag], [fileRowRemoveTag] (only present for the selected row). Built from a plain
 * Column, so it can sit in [OffkitScaffold]'s scrolling content.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun OffkitSelectableFileList(
    rows: List<FileRow>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val last = rows.lastIndex
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OffkitDimens.itemGap)) {
        rows.forEachIndexed { index, row ->
            key(row.key) {
                val isSelected = index == selectedIndex
                val name = if (isSelected) stringResource(R.string.cd_selected, row.title) else row.title
                val description = listOfNotNull(name, row.subtitle).joinToString(", ")
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OffkitDimens.listRowMinHeight)
                            .clip(shape)
                            .background(colors.surfaceContainerLowest)
                            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) colors.primary else colors.outlineVariant, shape)
                            .selectable(selected = isSelected, onClick = { onSelect(if (isSelected) null else index) })
                            .semantics { contentDescription = description }
                            .testTag(fileRowTag(index))
                            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.defaultMinSize(32.dp, 32.dp).background(colors.onSurface, CircleShape).padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = colors.surfaceContainerLowest,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.subtitle != null) {
                                Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                        Icon(
                            AppIcons.dragHandle,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp).size(OffkitDimens.iconSizeSmall).clearAndSetSemantics {},
                        )
                    }
                    if (isSelected) {
                        FlowRow(
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(ToolbarShape).background(colors.onSurface).padding(4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ToolbarTextButton(AppIcons.moveUp, stringResource(R.string.move_up), index > 0, fileRowUpTag(index)) { onMoveUp(index) }
                            ToolbarTextButton(AppIcons.moveDown, stringResource(R.string.move_down), index < last, fileRowDownTag(index)) { onMoveDown(index) }
                            ToolbarTextButton(AppIcons.delete, stringResource(R.string.remove), true, fileRowRemoveTag(index)) { onRemove(index) }
                        }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitThumbnailRowPreview() {
    OffkitTheme {
        OffkitThumbnailRow(
            count = 5,
            selectedIndex = 1,
            onSelect = {},
            onMoveEarlier = {},
            onMoveLater = {},
            onRemove = {},
            horizontalPadding = 16.dp,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(AppIcons.image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitSelectableFileListPreview() {
    OffkitTheme {
        OffkitSelectableFileList(
            rows = listOf(
                FileRow("a", stringResource(R.string.screen_merge), formatBytes(1_200_000)),
                FileRow("b", stringResource(R.string.screen_compress), formatBytes(800_000)),
                FileRow("c", stringResource(R.string.screen_images), formatBytes(2_400_000)),
            ),
            selectedIndex = 1,
            onSelect = {},
            onMoveUp = {},
            onMoveDown = {},
            onRemove = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
        )
    }
}
