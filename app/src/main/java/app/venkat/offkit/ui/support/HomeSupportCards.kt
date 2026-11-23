package app.venkat.offkit.ui.support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.openLink
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.IconBadge

/** Test tag of the home "Enjoying Offkit? Support the app" card. */
const val TAG_SUPPORT_CARD = "support_card"

/** Test tag of the hide (×) button of the home support card. */
const val TAG_SUPPORT_CARD_HIDE = "support_card_hide"

/** Test tag of the home card of house promo [id]. */
fun promoCardTag(id: String): String = "promo_card_$id"

/** Test tag of the hide (×) button of the home card of house promo [id]. */
fun promoHideTag(id: String): String = "promo_hide_$id"

/**
 * The bottom-of-home cards, emitted straight into the caller's column (so nothing is laid out,
 * and no spacing is added, for a hidden card): at most one house promo card (the first of
 * [promos] the user has not hidden), then the support card that opens the Support page through
 * [onOpenSupport]. Each card has a "Hide" button whose choice is remembered in [SupportStore].
 */
@Composable
fun HomeSupportCards(onOpenSupport: () -> Unit, promos: List<HousePromo> = HOUSE_PROMOS) {
    val context = LocalContext.current
    val store = remember(context) { SupportStore(context) }
    var cardHidden by remember(store) { mutableStateOf(store.cardHidden) }
    var hiddenPromos by remember(store) { mutableStateOf(store.hiddenPromos) }

    val promo = promos.firstOrNull { it.id !in hiddenPromos }
    if (promo != null) {
        PromoCard(
            promo = promo,
            onOpen = { openLink(context, promo.url) },
            onHide = {
                store.hidePromo(promo.id)
                hiddenPromos = hiddenPromos + promo.id
            },
        )
    }
    if (!cardHidden) {
        SupportCard(
            onOpen = onOpenSupport,
            onHide = {
                store.hideCard()
                cardHidden = true
            },
        )
    }
}

@Composable
private fun SupportCard(onOpen: () -> Unit, onHide: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(TAG_SUPPORT_CARD),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.heart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.support_card_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                AppIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            HideButton(onHide, Modifier.testTag(TAG_SUPPORT_CARD_HIDE))
        }
    }
}

@Composable
private fun PromoCard(promo: HousePromo, onOpen: () -> Unit, onHide: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(promoCardTag(promo.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(promo.icon?.let { painterResource(it) } ?: AppIcons.open)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                PromotedLabel(stringResource(R.string.support_promoted))
                Text(stringResource(promo.title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(promo.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HideButton(onHide, Modifier.testTag(promoHideTag(promo.id)))
        }
    }
}

/** The × of a home card: a standard 48dp icon button labelled "Hide". */
@Composable
private fun HideButton(onClick: () -> Unit, modifier: Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            AppIcons.close,
            contentDescription = stringResource(R.string.support_hide),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
