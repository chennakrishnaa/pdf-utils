package app.venkat.offkit.ui.support

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.GroupCard
import app.venkat.offkit.ui.about.GroupDivider
import app.venkat.offkit.ui.about.ListRow
import app.venkat.offkit.ui.about.openLink
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.theme.OffkitTheme

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
 * Support page: a short intro, then one white grouped card whose rows each hand over to another
 * app (Play Store, share sheet, browser, email app). The Donate row appears only when
 * [donateUrl] is not blank (the `support_donate_url` resource, shipped empty). [promos] (default
 * [HOUSE_PROMOS]) are listed in their own card under "More from the developer"; with no promos
 * there is no header either.
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    donateUrl: String = stringResource(R.string.support_donate_url),
    promos: List<HousePromo> = HOUSE_PROMOS,
) {
    val context = LocalContext.current
    OffkitScaffold(title = stringResource(R.string.support_title), navIcon = OffkitNavIcon.Back, onNavigate = onBack) {
        Text(
            text = stringResource(R.string.support_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp).testTag(TAG_SUPPORT_INTRO),
        )
        Spacer(Modifier.height(16.dp))
        GroupCard {
            ListRow(
                icon = AppIcons.star,
                title = stringResource(R.string.support_rate_title),
                detail = stringResource(R.string.support_rate_detail),
                testTag = TAG_SUPPORT_RATE,
                onClick = { openRating(context) },
            )
            GroupDivider()
            ListRow(
                icon = AppIcons.share,
                title = stringResource(R.string.support_share_title),
                detail = stringResource(R.string.support_share_detail),
                testTag = TAG_SUPPORT_SHARE,
                onClick = { startOrToast(context, shareAppIntent(context), R.string.support_no_share) },
            )
            GroupDivider()
            val issuesUrl = stringResource(R.string.support_issues_url)
            ListRow(
                icon = AppIcons.feedback,
                title = stringResource(R.string.support_issues_title),
                detail = stringResource(R.string.support_issues_detail),
                testTag = TAG_SUPPORT_ISSUES,
                onClick = { openLink(context, issuesUrl) },
            )
            GroupDivider()
            val sourceUrl = stringResource(R.string.source_code_url)
            ListRow(
                icon = AppIcons.code,
                title = stringResource(R.string.support_source_title),
                detail = stringResource(R.string.support_source_detail),
                testTag = TAG_SUPPORT_SOURCE,
                onClick = { openLink(context, sourceUrl) },
            )
            GroupDivider()
            ListRow(
                icon = AppIcons.mail,
                title = stringResource(R.string.support_email_title),
                detail = stringResource(R.string.support_email_detail),
                testTag = TAG_SUPPORT_EMAIL,
                onClick = { startOrToast(context, emailIntent(context), R.string.support_no_email) },
            )
            if (donateUrl.isNotBlank()) {
                GroupDivider()
                ListRow(
                    icon = AppIcons.heart,
                    title = stringResource(R.string.support_donate_title),
                    detail = stringResource(R.string.support_donate_detail),
                    testTag = TAG_SUPPORT_DONATE,
                    onClick = { openLink(context, donateUrl.trim()) },
                )
            }
        }
        if (promos.isNotEmpty()) {
            OffkitSectionHeader(stringResource(R.string.support_more_header))
            GroupCard {
                promos.forEachIndexed { index, promo ->
                    if (index > 0) GroupDivider()
                    ListRow(
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

/** The small "Promoted" label of a house promo, so it is never mistaken for a tool. */
@Composable
internal fun PromotedLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(widthDp = 412, heightDp = 1000)
@Composable
private fun SupportPreview() {
    OffkitTheme { SupportScreen(onBack = {}, donateUrl = "https://example.org/donate") }
}

@Preview(widthDp = 412, heightDp = 800)
@Composable
private fun SupportNoPromosPreview() {
    OffkitTheme { SupportScreen(onBack = {}, donateUrl = "", promos = emptyList()) }
}
