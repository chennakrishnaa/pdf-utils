package app.venkat.offkit.ui.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import app.venkat.offkit.R
import app.venkat.offkit.ui.about.openLink

/** The Play Store app link for [packageName] (handled by the Play Store app, if installed). */
fun playMarketUri(packageName: String): String = "market://details?id=$packageName"

/** The Play Store web link for [packageName] (used when no app handles `market://`). */
fun playWebUrl(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"

/** `ACTION_VIEW` of the Play Store app page of this app. */
fun rateIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(playMarketUri(context.packageName)))

/** A share-sheet chooser for a short `text/plain` message carrying the Play Store web link. */
fun shareAppIntent(context: Context): Intent {
    val text = context.getString(R.string.support_share_message, playWebUrl(context.packageName))
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return Intent.createChooser(send, context.getString(R.string.support_share_chooser))
}

/** `ACTION_SENDTO` of a `mailto:` to the developer, subject "Offkit <versionName>". */
fun emailIntent(context: Context): Intent {
    val version = appVersionName(context)
    val subject = if (version.isNullOrBlank()) {
        context.getString(R.string.app_name)
    } else {
        context.getString(R.string.support_email_subject, version)
    }
    return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + context.getString(R.string.support_email_address)))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
}

/** Opens the Play Store page of this app; falls back to the web page when no app handles `market://`. */
fun openRating(context: Context) {
    if (!tryStart(context, rateIntent(context))) openLink(context, playWebUrl(context.packageName))
}

/** Starts [intent]; shows [failMessage] instead of crashing when no app handles it. */
fun startOrToast(context: Context, intent: Intent, @StringRes failMessage: Int) {
    if (!tryStart(context, intent)) Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** This app's version name, or null if it cannot be read. */
internal fun appVersionName(context: Context): String? = try {
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
