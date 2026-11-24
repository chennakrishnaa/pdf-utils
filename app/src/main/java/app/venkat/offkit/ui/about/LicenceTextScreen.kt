package app.venkat.offkit.ui.about

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitLazyScaffold
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

const val TAG_LICENCE_TEXT = "licence_text"

/**
 * Full-screen, scrollable and selectable view of a bundled plain-text [asset] (licence texts).
 * The asset is read off the main thread, reflowed for the screen width ([reflowLicenceText]) and shown block by block on one white panel in a
 * [LazyColumn] (tag [TAG_LICENCE_TEXT]) inside [OffkitLazyScaffold], so only the visible
 * paragraphs of a long text (the notices are ~200 KB) are laid out. The navigation-bar inset is
 * part of the list's content padding.
 */
@Composable
fun LicenceTextScreen(title: String, asset: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<LicenceBlock>?>(initialValue = null, asset) {
        value = withContext(Dispatchers.IO) { readParagraphs(context, asset) }
    }
    LicenceTextContent(title = title, paragraphs = paragraphs, onBack = onBack)
}

/** The frame of [LicenceTextScreen]: a spinner while [paragraphs] is null, then the text. */
@Composable
private fun LicenceTextContent(title: String, paragraphs: List<LicenceBlock>?, onBack: () -> Unit) {
    OffkitLazyScaffold(title = title, navIcon = OffkitNavIcon.Back, onNavigate = onBack) { padding ->
        if (paragraphs == null) {
            Box(Modifier.fillMaxWidth().padding(padding).padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(TAG_LICENCE_TEXT),
                    contentPadding = padding,
                ) {
                    itemsIndexed(paragraphs) { index, paragraph ->
                        val first = index == 0
                        val last = index == paragraphs.lastIndex
                        Text(
                            text = paragraph.text,
                            textAlign = if (paragraph.centred) TextAlign.Center else TextAlign.Start,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, paragraphShape(first, last))
                                .padding(start = 16.dp, end = 16.dp, top = if (first) 16.dp else 0.dp, bottom = if (last) 16.dp else 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Shape of one paragraph's slice of the white panel: rounded at the panel's top and bottom only. */
@Composable
private fun paragraphShape(first: Boolean, last: Boolean): Shape {
    val r = OffkitDimens.cardRadius
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
}

/** Reads [asset] and reflows it into display blocks ([reflowLicenceText]); an unreadable asset yields an empty list. */
internal fun readParagraphs(context: Context, asset: String): List<LicenceBlock> = try {
    reflowLicenceText(context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() })
} catch (_: IOException) {
    emptyList()
}

@Preview(widthDp = 412, heightDp = 700)
@Composable
private fun LicenceTextPreview() {
    OffkitTheme {
        LicenceTextContent(
            title = "GNU AGPL v3",
            paragraphs = listOf(
                LicenceBlock("GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3, 19 November 2007", centred = true),
                LicenceBlock("Everyone is permitted to copy and distribute verbatim copies of this license document, but changing it is not allowed."),
            ),
            onBack = {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 400)
@Composable
private fun LicenceTextLoadingPreview() {
    OffkitTheme { LicenceTextContent(title = "Third-party notices", paragraphs = null, onBack = {}) }
}
