package radio.ks3ckc.ft8af.ui.rateprompt

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Side-effecting hand-offs for the rating prompt: the Play In-App Review flow and
 * its store-listing fallback. The URI/intent construction is split out so it can
 * be unit-tested; the Play review task itself only runs on a device.
 */

internal fun playStoreMarketUri(applicationId: String): Uri =
    Uri.parse("market://details?id=$applicationId")

internal fun playStoreWebUri(applicationId: String): Uri =
    Uri.parse("https://play.google.com/store/apps/details?id=$applicationId")

/** ACTION_VIEW for [uri], as a new task so it works from a non-Activity context. */
internal fun playStoreIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Unwrap [ContextWrapper]s (locale wrappers etc.) down to the hosting Activity. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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

/**
 * Launch the Play In-App Review sheet. If the review info request fails (no Play
 * services, sideloaded build, quota), or there's no Activity to host the sheet,
 * fall back to the store listing. Play gives no signal when it silently
 * throttles the sheet, so that case can't be detected here.
 */
internal fun launchPlayReview(context: Context) {
    val activity = context.findActivity()
    if (activity == null) {
        openPlayStoreListing(context)
        return
    }
    val manager = ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (request.isSuccessful) {
            manager.launchReviewFlow(activity, request.result).addOnFailureListener {
                openPlayStoreListing(activity)
            }
        } else {
            openPlayStoreListing(activity)
        }
    }
}
