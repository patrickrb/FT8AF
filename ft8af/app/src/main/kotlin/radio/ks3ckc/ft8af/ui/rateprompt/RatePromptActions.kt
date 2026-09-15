package radio.ks3ckc.ft8af.ui.rateprompt

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The rating prompt's Play hand-off: open this app's Play Store listing. It
 * deliberately does not call the In-App Review API — Google's guidelines say not to
 * trigger the review card from a call-to-action button, because once the user's
 * review quota is spent the card silently doesn't appear. URI/intent construction is
 * split out so it can be unit-tested.
 */

internal fun playStoreMarketUri(applicationId: String): Uri =
    Uri.parse("market://details?id=$applicationId")

internal fun playStoreWebUri(applicationId: String): Uri =
    Uri.parse("https://play.google.com/store/apps/details?id=$applicationId")

/** ACTION_VIEW for [uri], as a new task so it works from a non-Activity context. */
internal fun playStoreIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Open this app's Play listing: the Play Store app via `market://`, else the
 * web listing. Returns false only when neither can be opened.
 */
internal fun openPlayStoreListing(context: Context): Boolean {
    val appId = context.packageName
    for (uri in listOf(playStoreMarketUri(appId), playStoreWebUri(appId))) {
        try {
            context.startActivity(playStoreIntent(uri))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next fallback.
        }
    }
    return false
}
