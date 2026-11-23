package app.venkat.offkit.ui.onboarding

import android.content.Context

/**
 * Remembers whether the first-run introduction has been finished or skipped: one boolean in the
 * private `onboarding` preferences file (excluded from backup like all app data). Reading it is
 * a cheap synchronous preferences read, done before the first frame so neither screen flashes.
 */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once the user has finished or skipped the introduction. */
    val seen: Boolean get() = prefs.getBoolean(KEY_SEEN, false)

    /** Records that the introduction has been finished or skipped; it is not shown automatically again. */
    fun markSeen() {
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }

    companion object {
        /** Name of the private preferences file. */
        const val PREFS_NAME = "onboarding"

        /** Key of the "introduction seen" flag. */
        const val KEY_SEEN = "seen"
    }
}
