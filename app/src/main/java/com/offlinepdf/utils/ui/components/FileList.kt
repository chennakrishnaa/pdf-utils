package com.offlinepdf.utils.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinepdf.utils.R

/** One entry in a [FileList]. [key] must be unique within the list and stable across moves. */
data class FileRow(val key: String, val title: String, val subtitle: String? = null)

/** Test tag of the empty-state text of [FileList]. */
const val TAG_FILE_LIST_EMPTY = "file_list_empty"

/** Test tag of row [index] (0-based) in a [FileList]. */
fun fileRowTag(index: Int): String = "row_$index"

/** Test tag of the move-up button of row [index]. */
fun fileRowUpTag(index: Int): String = "row_${index}_up"

/** Test tag of the move-down button of row [index]. */
fun fileRowDownTag(index: Int): String = "row_${index}_down"

/** Test tag of the remove button of row [index]. */
fun fileRowRemoveTag(index: Int): String = "row_${index}_remove"

/**
 * Numbered, non-lazy list of files with optional move up / move down / remove buttons.
 *
 * Stateless: the caller owns [rows] and reorders/removes in the callbacks, which receive the
 * 0-based row index. Passing null for a callback hides that control on every row. Move up is
 * disabled on the first row and move down on the last. Shows [emptyText] when [rows] is empty.
 *
 * Built from a plain [Column], so it can be placed inside a vertically scrolling parent.
 */
@Composable
fun FileList(
    rows: List<FileRow>,
    onMoveUp: ((index: Int) -> Unit)?,
    onMoveDown: ((index: Int) -> Unit)?,
    onRemove: ((index: Int) -> Unit)?,
    modifier: Modifier = Modifier,
    emptyText: String,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp).testTag(TAG_FILE_LIST_EMPTY),
            )
        }
        val last = rows.lastIndex
        rows.forEachIndexed { index, row ->
            key(row.key) {
                OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(fileRowTag(index))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.file_list_number, index + 1),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.subtitle != null) {
                                Text(
                                    text = row.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (onMoveUp != null) {
                            IconButton(
                                onClick = { onMoveUp(index) },
                                enabled = index > 0,
                                modifier = Modifier.testTag(fileRowUpTag(index)),
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.file_list_move_up))
                            }
                        }
                        if (onMoveDown != null) {
                            IconButton(
                                onClick = { onMoveDown(index) },
                                enabled = index < last,
                                modifier = Modifier.testTag(fileRowDownTag(index)),
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.file_list_move_down))
                            }
                        }
                        if (onRemove != null) {
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.testTag(fileRowRemoveTag(index)),
                            ) {
                                Icon(Icons.Filled.Close, stringResource(R.string.file_list_remove))
                            }
                        }
                    }
                }
            }
        }
    }
}
