package app.venkat.offkit.ui.theme

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.venkat.offkit.R

enum class OffkitTool(
    @DrawableRes val icon: Int,
    @DrawableRes val badge: Int,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val container: Color,
    val onContainer: Color,
) {
    IMAGES(R.drawable.ic_tool_images, R.drawable.ic_tool_images_badge, R.string.tool_images_title, R.string.tool_images_desc, Color(0xFFE7DEF3), Color(0xFF4A3274)),
    COMPRESS(R.drawable.ic_tool_compress, R.drawable.ic_tool_compress_badge, R.string.tool_compress_title, R.string.tool_compress_desc, Color(0xFFDDE7F7), Color(0xFF1F4A8A)),
    MERGE(R.drawable.ic_tool_merge, R.drawable.ic_tool_merge_badge, R.string.tool_merge_title, R.string.tool_merge_desc, Color(0xFFDCEFE2), Color(0xFF1E5A34)),
    ADD_PAGES(R.drawable.ic_tool_add_pages, R.drawable.ic_tool_add_pages_badge, R.string.tool_add_pages_title, R.string.tool_add_pages_desc, Color(0xFFF8E7C9), Color(0xFF7A4B05)),
}
