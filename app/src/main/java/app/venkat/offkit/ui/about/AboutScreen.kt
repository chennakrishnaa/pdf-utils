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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.OffkitInfoNote
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.onboarding.OnboardingScreen
import app.venkat.offkit.ui.theme.OffkitTheme

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
const val TAG_ABOUT_SUPPORT = "about_support"

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
fun AboutScreen(onBack: () -> Unit, onOpenSupport: () -> Unit = {}) {
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
    AboutContent(
        version = version,
        onBack = onBack,
        onOpenIntroduction = { introduction = true },
        onOpenSupport = onOpenSupport,
        onOpenDocument = { document = it },
        onOpenLink = { openLink(context, it) },
    )
}

/** The About page itself (no navigation state), so it can be previewed. */
@Composable
private fun AboutContent(
    version: String?,
    onBack: () -> Unit,
    onOpenIntroduction: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenDocument: (asset: String) -> Unit,
    onOpenLink: (url: String) -> Unit,
) {
    OffkitScaffold(title = stringResource(R.string.about_title), navIcon = OffkitNavIcon.Back, onNavigate = onBack) {
        AppHeader(version)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_no_data),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp).testTag(TAG_ABOUT_NO_DATA),
        )
        Spacer(Modifier.height(8.dp))
        OffkitInfoNote(stringResource(R.string.about_offline))
        Spacer(Modifier.height(16.dp))
        GroupCard {
            ListRow(
                icon = AppIcons.info,
                title = stringResource(R.string.about_intro_row),
                testTag = TAG_ABOUT_INTRO,
                onClick = onOpenIntroduction,
            )
            GroupDivider()
            ListRow(
                icon = AppIcons.heart,
                title = stringResource(R.string.about_support_row),
                testTag = TAG_ABOUT_SUPPORT,
                onClick = onOpenSupport,
            )
        }

        OffkitSectionHeader(stringResource(R.string.about_license_header))
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(TAG_ABOUT_MUPDF),
                )
            }
            GroupDivider()
            ListRow(
                icon = painterResource(R.drawable.ic_shield),
                title = stringResource(R.string.about_agpl_row),
                testTag = TAG_ABOUT_AGPL_TEXT,
                onClick = { onOpenDocument(ASSET_LICENSE) },
            )
            GroupDivider()
            ListRow(
                icon = AppIcons.info,
                title = stringResource(R.string.about_notices_row),
                detail = stringResource(R.string.about_notices_detail),
                testTag = TAG_ABOUT_NOTICES,
                onClick = { onOpenDocument(ASSET_NOTICES) },
            )
        }

        OffkitSectionHeader(stringResource(R.string.about_source_header))
        val sourceUrl = stringResource(R.string.source_code_url)
        GroupCard {
            ListRow(
                icon = AppIcons.code,
                title = sourceUrl,
                testTag = TAG_ABOUT_SOURCE_URL,
                onClick = { onOpenLink(sourceUrl) },
                link = true,
            )
        }

        OffkitSectionHeader(stringResource(R.string.about_privacy_header))
        val privacyUrl = stringResource(R.string.privacy_policy_url)
        GroupCard {
            ListRow(
                icon = painterResource(R.drawable.ic_lock),
                title = stringResource(R.string.about_privacy_row),
                detail = stringResource(R.string.about_privacy_detail),
                testTag = TAG_ABOUT_PRIVACY,
                onClick = { onOpenDocument(ASSET_PRIVACY) },
            )
            GroupDivider()
            ListRow(
                icon = AppIcons.open,
                title = privacyUrl,
                testTag = TAG_ABOUT_PRIVACY_URL,
                onClick = { onOpenLink(privacyUrl) },
                link = true,
            )
        }
    }
}

/** App tile, name and version in one white card. The version stays its own node (tested). */
@Composable
private fun AppHeader(version: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().offkitCard().padding(16.dp),
    ) {
        IconTile(
            icon = AppIcons.pdfFile,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
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

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun AboutPreview() {
    OffkitTheme {
        AboutContent(version = "1.0.0", onBack = {}, onOpenIntroduction = {}, onOpenSupport = {}, onOpenDocument = {}, onOpenLink = {})
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
