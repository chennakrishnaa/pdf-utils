package app.venkat.offkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme

/** Applies [tag] as a test tag when it is not null. */
internal fun Modifier.optionalTestTag(tag: String?): Modifier = if (tag != null) this.testTag(tag) else this

/** Optional leading icon + label, shared by the Offkit buttons. */
@Composable
private fun OffkitButtonContent(text: String, icon: Painter?, style: TextStyle, iconSize: Dp, gap: Dp) {
    if (icon != null) {
        Icon(painter = icon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(gap))
    }
    Text(text = text, style = style, textAlign = TextAlign.Center)
}

/**
 * The screen's one main call to action (SPEC §0 "Primary button"): at least 56dp tall, fully
 * rounded, flat, `primary` / `onPrimary`, [text] in `labelLarge` with an optional 20dp leading
 * [icon] (decorative).
 *
 * It does not fill the width by itself: pass `Modifier.fillMaxWidth()` for a full-width button,
 * or put it in [OffkitBottomSummary]. When [enabled] is false it shows `offkit_disabled_container`
 * / `offkit_on_disabled`, does not click, and the caller passes a [text] that explains why
 * (e.g. `choose_pdf_first`). The label wraps rather than clips at large font scales.
 *
 * Semantics: a Button with the label as its text, disabled when [enabled] is false.
 * [testTag] goes on the button.
 */
@Composable
fun OffkitPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colorResource(R.color.offkit_disabled_container),
            disabledContentColor = colorResource(R.color.offkit_on_disabled),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = OffkitDimens.buttonHeight).optionalTestTag(testTag),
    ) {
        OffkitButtonContent(text, icon, MaterialTheme.typography.labelLarge, OffkitDimens.iconSizeSmall, 8.dp)
    }
}

/**
 * Filled tonal button (SPEC §0 "Tonal button"): `secondaryContainer` / `onSurface`, fully
 * rounded, at least 48dp tall, [text] in `labelMedium`, optional 20dp leading [icon]. Used for
 * secondary actions such as `change` on [OffkitFileCard]. Semantics: a Button; [testTag] goes on it.
 */
@Composable
fun OffkitTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    testTag: String? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = OffkitDimens.touchTarget).optionalTestTag(testTag),
    ) {
        OffkitButtonContent(text, icon, MaterialTheme.typography.labelMedium, OffkitDimens.iconSizeSmall, 8.dp)
    }
}

/**
 * Outlined button: 2dp `outline` border, transparent, `onSurface` content, fully rounded, at
 * least 56dp tall, [text] in `labelLarge`, optional 20dp leading [icon]. For a second
 * full-size action next to an [OffkitPrimaryButton]. Semantics: a Button; [testTag] goes on it.
 */
@Composable
fun OffkitOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    testTag: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(OffkitDimens.controlBorder, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = OffkitDimens.buttonHeight).optionalTestTag(testTag),
    ) {
        OffkitButtonContent(text, icon, MaterialTheme.typography.labelLarge, OffkitDimens.iconSizeSmall, 8.dp)
    }
}

/**
 * Text button (SPEC §0 "Text button"): `offkit_link` colour, [text] in `labelMedium`, at least
 * 48dp tall, 8dp side padding, optional 18dp leading [icon] with a 6dp gap (e.g. `ic_add` +
 * `add`). Semantics: a Button; [testTag] goes on it.
 */
@Composable
fun OffkitTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    testTag: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.offkit_link)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = modifier.heightIn(min = OffkitDimens.touchTarget).optionalTestTag(testTag),
    ) {
        OffkitButtonContent(text, icon, MaterialTheme.typography.labelMedium, 18.dp, 6.dp)
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitPrimaryButtonPreview() {
    OffkitTheme {
        Column(
            Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OffkitPrimaryButton(stringResource(R.string.action_compress), {}, Modifier.fillMaxWidth(), icon = AppIcons.compress)
            OffkitPrimaryButton(stringResource(R.string.choose_pdf_first), {}, Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitTonalButtonPreview() {
    OffkitTheme { OffkitTonalButton(stringResource(R.string.change), {}, Modifier.padding(16.dp)) }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitOutlinedButtonPreview() {
    OffkitTheme { OffkitOutlinedButton(stringResource(R.string.action_share), {}, Modifier.padding(16.dp), icon = AppIcons.share) }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitTextButtonPreview() {
    OffkitTheme { OffkitTextButton(stringResource(R.string.add), {}, Modifier.padding(16.dp), icon = AppIcons.add) }
}
