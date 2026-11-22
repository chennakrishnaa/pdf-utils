package com.offlinepdf.utils.feature.addimages

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.ui.components.ToolScaffold

@Composable
fun AddImagesScreen(onBack: () -> Unit) {
    ToolScaffold(title = stringResource(R.string.tool_add_images_title), onBack = onBack) { padding ->
        Text(
            text = stringResource(R.string.coming_soon),
            modifier = Modifier.padding(padding).padding(16.dp).testTag("stub_add_images"),
        )
    }
}
