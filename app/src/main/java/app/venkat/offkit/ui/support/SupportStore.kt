package app.venkat.offkit.ui.support

import android.content.Context

/**
 * Remembers which home-screen cards the user has hidden: the "Support the app" card (one boolean)
 * and house promos (a set of promo ids), in the private `support` preferences file (excluded from
 * backup like all app data). Reads are cheap synchronous preferences reads.
 */
class SupportStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once the user has hidden the support card on the home screen. */
    val cardHidden: Boolean get() = prefs.getBoolean(KEY_CARD_HIDDEN, false)

    /** Hides the home support card for good (the Support page stays reachable from About). */
    fun hideCard() {
        prefs.edit().putBoolean(KEY_CARD_HIDDEN, true).apply()
    }

    /** Ids of the house promos the user has hidden on the home screen. */
    val hiddenPromos: Set<String> get() = prefs.getStringSet(KEY_HIDDEN_PROMOS, null).orEmpty().toSet()

    /** Hides the house promo [id] on the home screen for good. */
    fun hidePromo(id: String) {
        prefs.edit().putStringSet(KEY_HIDDEN_PROMOS, hiddenPromos + id).apply()
    }

    companion object {
        /** Name of the private preferences file. */
        const val PREFS_NAME = "support"

        /** Key of the "support card hidden" flag. */
        const val KEY_CARD_HIDDEN = "card_hidden"

        /** Key of the set of hidden promo ids. */
        const val KEY_HIDDEN_PROMOS = "hidden_promos"
    }
}
