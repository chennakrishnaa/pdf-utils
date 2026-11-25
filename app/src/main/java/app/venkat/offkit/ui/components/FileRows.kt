package app.venkat.offkit.ui.components

/** One entry in a file list (Merge). [key] must be unique within the list and stable across moves. */
data class FileRow(val key: String, val title: String, val subtitle: String? = null)

/** Test tag of the empty-state text of a file list. */
const val TAG_FILE_LIST_EMPTY = "file_list_empty"

/** Test tag of row [index] (0-based) in a file list. */
fun fileRowTag(index: Int): String = "row_$index"

/** Test tag of the move-up button of row [index]. */
fun fileRowUpTag(index: Int): String = "row_${index}_up"

/** Test tag of the move-down button of row [index]. */
fun fileRowDownTag(index: Int): String = "row_${index}_down"

/** Test tag of the remove button of row [index]. */
fun fileRowRemoveTag(index: Int): String = "row_${index}_remove"
