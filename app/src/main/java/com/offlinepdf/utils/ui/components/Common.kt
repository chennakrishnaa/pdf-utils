package com.offlinepdf.utils.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small section title used to group controls within a tool screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Full-width filled button used for a screen's main action; [testTag] goes on the button. */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag(testTag),
    ) {
        Text(text)
    }
}
