package com.offlinepdf.utils.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlinepdf.utils.R
import com.offlinepdf.utils.ui.components.SectionHeader
import com.offlinepdf.utils.ui.components.ToolScaffold

const val TAG_ABOUT_VERSION = "about_version"
const val TAG_ABOUT_LICENSE = "about_license"
const val TAG_ABOUT_LICENSE_NOTICE = "about_license_notice"
const val TAG_ABOUT_MUPDF = "about_mupdf"
const val TAG_ABOUT_SOURCE_URL = "about_source_url"

/** About screen: version, offline statement, licence, MuPDF credit and source code URL. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            if (version != null) {
                Text(
                    text = stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_ABOUT_VERSION),
                )
            }
            Text(stringResource(R.string.about_offline), style = MaterialTheme.typography.bodyMedium)

            SectionHeader(stringResource(R.string.about_license_header))
            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TAG_ABOUT_LICENSE),
            )
            Text(
                text = stringResource(R.string.about_license_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TAG_ABOUT_LICENSE_NOTICE),
            )
            Text(
                text = stringResource(R.string.about_mupdf),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TAG_ABOUT_MUPDF),
            )

            SectionHeader(stringResource(R.string.about_source_header))
            SelectionContainer {
                Text(
                    text = stringResource(R.string.source_code_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TAG_ABOUT_SOURCE_URL),
                )
            }
        }
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
