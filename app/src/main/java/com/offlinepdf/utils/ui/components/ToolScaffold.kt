package com.offlinepdf.utils.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.offlinepdf.utils.R

/** Test tag of the back button in [ToolScaffold]. */
const val TAG_ACTION_BACK = "action_back"

/**
 * Standard screen frame for a tool: a top app bar with [title] and a back arrow
 * (tag [TAG_ACTION_BACK]) that calls [onBack].
 *
 * [content] receives the inner padding, which already includes the app bar and the system bar
 * insets; apply it to the root of the content. The content area itself does not scroll, so the
 * caller chooses: `Column(Modifier.padding(it).verticalScroll(...))` or a `LazyColumn` with
 * `contentPadding = it`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_ACTION_BACK)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        content = content,
    )
}
