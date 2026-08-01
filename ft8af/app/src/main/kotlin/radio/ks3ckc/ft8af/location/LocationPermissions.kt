package radio.ks3ckc.ft8af.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Whether the app may read the device's location at all.
 *
 * One definition, deliberately, because the interesting case is the one two
 * copies get to disagree about. Since Android 12 the permission dialog offers
 * "Precise" and "Approximate" as a user choice, so `ACCESS_COARSE_LOCATION`
 * alone is a perfectly ordinary outcome of asking for both. Code that tests only
 * for `ACCESS_FINE_LOCATION` reads that grant as a denial — and a screen that
 * gates on the strict check while the tracker underneath it accepts the loose one
 * refuses to start a trip it could have recorded fine, re-prompting for a
 * permission the user already granted.
 *
 * Trip mode is happy with approximate: SmartBeaconing samples a route measured in
 * miles, and a QSO is pinned to the road rather than to a lane.
 */
fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
