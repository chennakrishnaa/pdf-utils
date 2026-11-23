package app.venkat.offkit.ui.support

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * A "house promo": a link to one of the developer's own other apps or pages. Shown on the Support
 * page (under "More from the developer") and, for the first one the user has not hidden, as one
 * compact card on the home screen marked "Promoted".
 *
 * @property id stable identifier; the user's "Hide" choice is remembered by it, so never reuse one.
 * @property title short title (string resource).
 * @property text one-line description (string resource).
 * @property url link opened outside the app with a plain `ACTION_VIEW` (e.g. a Play Store page).
 * @property icon optional 24dp single-colour vector drawable; a default icon is used when null.
 */
data class HousePromo(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val text: Int,
    val url: String,
    @param:DrawableRes val icon: Int? = null,
)

/**
 * The house promos shipped with this version of the app (none yet).
 *
 * To add one: put its title and text in `res/values/strings_support.xml`, then add
 * `HousePromo(id = "my-other-app", title = R.string.…, text = R.string.…, url = "https://…")`
 * here. The content ships inside the app: nothing is downloaded, the app makes no network
 * requests, and changing the list needs an app update. Keep it to at most 2 items, and only
 * promote the developer's own apps or pages. While the list is empty nothing is shown anywhere.
 */
val HOUSE_PROMOS: List<HousePromo> = emptyList()
