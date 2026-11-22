package com.offlinepdf.utils.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.ui.nav.Routes

/** Test tag of the About action in the home app bar. */
const val TAG_ACTION_ABOUT = "action_about"

private data class Tool(
    val route: String,
    val testTag: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
)

private val tools = listOf(
    Tool(Routes.IMAGES_TO_PDF, "tool_images_to_pdf", R.string.tool_images_to_pdf_title, R.string.tool_images_to_pdf_desc),
    Tool(Routes.COMPRESS, "tool_compress", R.string.tool_compress_title, R.string.tool_compress_desc),
    Tool(Routes.MERGE, "tool_merge", R.string.tool_merge_title, R.string.tool_merge_desc),
    Tool(Routes.ADD_IMAGES, "tool_add_images", R.string.tool_add_images_title, R.string.tool_add_images_desc),
)

/** Home screen: one card per tool. [onOpenTool] receives the tool's route from [Routes]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenTool: (route: String) -> Unit, onOpenAbout: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenAbout, modifier = Modifier.testTag(TAG_ACTION_ABOUT)) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.home_about))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tools.forEach { tool ->
                ElevatedCard(
                    onClick = { onOpenTool(tool.route) },
                    modifier = Modifier.fillMaxWidth().testTag(tool.testTag),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(tool.title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(tool.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.home_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("home_footer"),
            )
        }
    }
}
