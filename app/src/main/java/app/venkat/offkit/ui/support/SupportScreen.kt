package app.venkat.offkit.ui.support

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.openLink
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.IconBadge
import app.venkat.offkit.ui.components.SectionHeader
import app.venkat.offkit.ui.components.ToolScaffold

const val TAG_SUPPORT_INTRO = "support_intro"
const val TAG_SUPPORT_RATE = "support_rate"
const val TAG_SUPPORT_SHARE = "support_share"
const val TAG_SUPPORT_ISSUES = "support_issues"
const val TAG_SUPPORT_SOURCE = "support_source"
const val TAG_SUPPORT_EMAIL = "support_email"
const val TAG_SUPPORT_DONATE = "support_donate"

/** Test tag of the Support-page row of house promo [id]. */
fun supportPromoTag(id: String): String = "support_promo_$id"

/**
 * Support page: a short intro, then rows that each hand over to another app (Play Store, share
 * sheet, browser, email app). The Donate row appears only when [donateUrl] is not blank (the
 * `support_donate_url` resource, shipped empty). [promos] (default [HOUSE_PROMOS]) are listed
 * under "More from the developer"; with no promos there is no header either.
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    donateUrl: String = stringResource(R.string.support_donate_url),
    promos: List<HousePromo> = HOUSE_PROMOS,
) {
    val context = LocalContext.current
    ToolScaffold(title = stringResource(R.string.support_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.support_intro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TAG_SUPPORT_INTRO),
            )
            SupportRow(
                icon = AppIcons.star,
                title = stringResource(R.string.support_rate_title),
                detail = stringResource(R.string.support_rate_detail),
                testTag = TAG_SUPPORT_RATE,
                onClick = { openRating(context) },
            )
            SupportRow(
                icon = AppIcons.share,
                title = stringResource(R.string.support_share_title),
                detail = stringResource(R.string.support_share_detail),
                testTag = TAG_SUPPORT_SHARE,
                onClick = { startOrToast(context, shareAppIntent(context), R.string.support_no_share) },
            )
            val issuesUrl = stringResource(R.string.support_issues_url)
            SupportRow(
                icon = AppIcons.feedback,
                title = stringResource(R.string.support_issues_title),
                detail = stringResource(R.string.support_issues_detail),
                testTag = TAG_SUPPORT_ISSUES,
                onClick = { openLink(context, issuesUrl) },
            )
            val sourceUrl = stringResource(R.string.source_code_url)
            SupportRow(
                icon = AppIcons.code,
                title = stringResource(R.string.support_source_title),
                detail = stringResource(R.string.support_source_detail),
                testTag = TAG_SUPPORT_SOURCE,
                onClick = { openLink(context, sourceUrl) },
            )
            SupportRow(
                icon = AppIcons.mail,
                title = stringResource(R.string.support_email_title),
                detail = stringResource(R.string.support_email_detail),
                testTag = TAG_SUPPORT_EMAIL,
                onClick = { startOrToast(context, emailIntent(context), R.string.support_no_email) },
            )
            if (donateUrl.isNotBlank()) {
                SupportRow(
                    icon = AppIcons.heart,
                    title = stringResource(R.string.support_donate_title),
                    detail = stringResource(R.string.support_donate_detail),
                    testTag = TAG_SUPPORT_DONATE,
                    onClick = { openLink(context, donateUrl.trim()) },
                )
            }
            if (promos.isNotEmpty()) {
                SectionHeader(stringResource(R.string.support_more_header))
                promos.forEach { promo ->
                    SupportRow(
                        icon = promo.icon?.let { painterResource(it) } ?: AppIcons.open,
                        title = stringResource(promo.title),
                        detail = stringResource(promo.text),
                        testTag = supportPromoTag(promo.id),
                        onClick = { openLink(context, promo.url) },
                        label = stringResource(R.string.support_promoted),
                    )
                }
            }
        }
    }
}

/** A tappable card row: icon badge, title, one-line detail and a chevron; its texts merge into one node. */
@Composable
private fun SupportRow(
    icon: Painter,
    title: String,
    detail: String,
    testTag: String,
    onClick: () -> Unit,
    label: String? = null,
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
                if (label != null) PromotedLabel(label)
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** The small "Promoted" label of a house promo, so it is never mistaken for a tool. */
@Composable
internal fun PromotedLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
