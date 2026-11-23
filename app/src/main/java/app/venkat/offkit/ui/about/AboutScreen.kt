package app.venkat.offkit.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.IconBadge
import app.venkat.offkit.ui.components.InfoNote
import app.venkat.offkit.ui.components.SectionHeader
import app.venkat.offkit.ui.components.ToolScaffold
import app.venkat.offkit.ui.onboarding.OnboardingScreen

const val TAG_ABOUT_VERSION = "about_version"
const val TAG_ABOUT_LICENSE = "about_license"
const val TAG_ABOUT_LICENSE_NOTICE = "about_license_notice"
const val TAG_ABOUT_MUPDF = "about_mupdf"
const val TAG_ABOUT_SOURCE_URL = "about_source_url"
const val TAG_ABOUT_AGPL_TEXT = "about_agpl_text"
const val TAG_ABOUT_NOTICES = "about_notices"
const val TAG_ABOUT_PRIVACY = "about_privacy"
const val TAG_ABOUT_PRIVACY_URL = "about_privacy_url"
const val TAG_ABOUT_NO_DATA = "about_no_data"
const val TAG_ABOUT_INTRO = "about_intro"

/** Asset holding a verbatim copy of the repository's `LICENSE` (GNU AGPL v3). */
const val ASSET_LICENSE = "LICENSE"

/** Asset holding a verbatim copy of the repository's `THIRD_PARTY_NOTICES.md`. */
const val ASSET_NOTICES = "THIRD_PARTY_NOTICES.md"

/** Asset holding a verbatim copy of the repository's `PRIVACY.md`. */
const val ASSET_PRIVACY = "PRIVACY.md"

/**
 * About screen: app name and version, offline statement, licence, MuPDF credit, the full AGPL
 * text, third-party notices and privacy policy (shown offline from bundled assets), the source
 * code URL and the online privacy policy URL.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    // Whether the introduction is open (shown over About; finishing it returns here).
    var introduction by rememberSaveable { mutableStateOf(false) }
    if (introduction) {
        OnboardingScreen(onFinished = { introduction = false })
        return
    }
    // Which bundled text is open, if any (an asset name); survives rotation.
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val open = document
    if (open != null) {
        BackHandler { document = null }
        LicenceTextScreen(
            title = stringResource(
                when (open) {
                    ASSET_LICENSE -> R.string.about_agpl_title
                    ASSET_PRIVACY -> R.string.about_privacy_title
                    else -> R.string.about_notices_title
                },
            ),
            asset = open,
            onBack = { document = null },
        )
        return
    }

    val context = LocalContext.current
    val version = remember(context) { appVersionName(context) }
    ToolScaffold(title = stringResource(R.string.about_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppHeader(version)
            InfoNote(stringResource(R.string.about_offline))
            Text(
                text = stringResource(R.string.about_no_data),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(TAG_ABOUT_NO_DATA),
            )
            LinkRow(
                icon = AppIcons.info,
                text = stringResource(R.string.about_intro_row),
                testTag = TAG_ABOUT_INTRO,
                onClick = { introduction = true },
            )

            SectionHeader(stringResource(R.string.about_license_header))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.about_license),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TAG_ABOUT_LICENSE),
                    )
                    Text(
                        text = stringResource(R.string.about_license_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_ABOUT_LICENSE_NOTICE),
                    )
                    Text(
                        text = stringResource(R.string.about_mupdf),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TAG_ABOUT_MUPDF),
                    )
                }
            }
            LinkRow(
                icon = AppIcons.info,
                text = stringResource(R.string.about_agpl_row),
                testTag = TAG_ABOUT_AGPL_TEXT,
                onClick = { document = ASSET_LICENSE },
            )
            LinkRow(
                icon = AppIcons.info,
                text = stringResource(R.string.about_notices_row),
                detail = stringResource(R.string.about_notices_detail),
                testTag = TAG_ABOUT_NOTICES,
                onClick = { document = ASSET_NOTICES },
            )

            SectionHeader(stringResource(R.string.about_source_header))
            val sourceUrl = stringResource(R.string.source_code_url)
            LinkRow(
                icon = AppIcons.open,
                text = sourceUrl,
                testTag = TAG_ABOUT_SOURCE_URL,
                onClick = { openLink(context, sourceUrl) },
                linkStyle = true,
            )

            SectionHeader(stringResource(R.string.about_privacy_header))
            LinkRow(
                icon = AppIcons.info,
                text = stringResource(R.string.about_privacy_row),
                detail = stringResource(R.string.about_privacy_detail),
                testTag = TAG_ABOUT_PRIVACY,
                onClick = { document = ASSET_PRIVACY },
            )
            val privacyUrl = stringResource(R.string.privacy_policy_url)
            LinkRow(
                icon = AppIcons.open,
                text = privacyUrl,
                testTag = TAG_ABOUT_PRIVACY_URL,
                onClick = { openLink(context, privacyUrl) },
                linkStyle = true,
            )
        }
    }
}

@Composable
private fun AppHeader(version: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(
            icon = AppIcons.pdf,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 52.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            if (version != null) {
                Text(
                    text = stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_ABOUT_VERSION),
                )
            }
        }
    }
}

/** A tappable card row: icon badge, [text] (and optional [detail]) and a chevron. Its texts merge into one node. */
@Composable
private fun LinkRow(
    icon: Painter,
    text: String,
    testTag: String,
    onClick: () -> Unit,
    detail: String? = null,
    linkStyle: Boolean = false,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (linkStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painter = AppIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Opens [url] in whatever app handles it (a plain `ACTION_VIEW`; the app itself never goes
 * online). Shows a short message instead of crashing when no app can open links.
 */
internal fun openLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
    }
}

private fun appVersionName(context: Context): String? = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
} catch (_: PackageManager.NameNotFoundException) {
    null
}
