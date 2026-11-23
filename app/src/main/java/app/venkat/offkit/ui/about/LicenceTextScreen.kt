package app.venkat.offkit.ui.about

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.venkat.offkit.ui.components.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

const val TAG_LICENCE_TEXT = "licence_text"

/**
 * Full-screen, scrollable and selectable view of a bundled plain-text [asset] (licence texts).
 * The asset is read off the main thread and shown paragraph by paragraph in a lazy list.
 */
@Composable
fun LicenceTextScreen(title: String, asset: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(initialValue = null, asset) {
        value = withContext(Dispatchers.IO) { readParagraphs(context, asset) }
    }
    ToolScaffold(title = title, onBack = onBack) { padding ->
        val loaded = paragraphs
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SelectionContainer(Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(TAG_LICENCE_TEXT),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(loaded) { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Reads [asset] and splits it at blank lines; an unreadable asset yields an empty list. */
internal fun readParagraphs(context: Context, asset: String): List<String> = try {
    context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        .replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
} catch (_: IOException) {
    emptyList()
}
