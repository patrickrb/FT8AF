package radio.ks3ckc.ft8af.location

import android.content.Context
import android.location.LocationManager
import android.util.Log
import radio.ks3ckc.ft8af.rtota.RtotaTripManager

/**
 * Where a position came from, in descending order of trustworthiness.
 *
 * Both entries are *observations* — an actual fix from an actual receiver. There
 * is deliberately no tier derived from the operator's configured grid: a
 * four-character grid is roughly 55 km across, and writing its centre into
 * MY_LAT/MY_LON would dress that up as a measurement. The ADIF already carries
 * MY_GRIDSQUARE, so anyone reading the log can make that approximation
 * themselves, knowing exactly what it is. When there is no fix, the QSO is
 * logged without coordinates and rtota.app infers a position from the breadcrumb
 * trail instead.
 */
enum class RoverPositionSource {
    /** A live GPS fix from an active road trip — the rover's actual position. */
    LIVE_FIX,

    /** The platform's last known location, from whatever asked for one last. */
    LAST_KNOWN,
}

/** A candidate position with enough context to judge whether it is worth using. */
data class RoverFix(
    val latitude: Double,
    val longitude: Double,
    /** How old the underlying observation is, in ms. A grid centre is never "old". */
    val ageMs: Long,
    val source: RoverPositionSource,
)

/**
 * A real fix older than this is not where the operator is now.
 *
 * Fifteen minutes is a compromise: long enough that a phone that has been sitting
 * in a parking lot still has a usable last-known location, short enough that at
 * highway speed the error stays in the tens of miles rather than the hundreds —
 * and below that threshold a stale fix is still far better than the ~55 km
 * ambiguity of a four-character grid.
 */
const val MAX_ROVER_FIX_AGE_MS = 15 * 60_000L

/**
 * Pick the position to stamp on a QSO from the candidates available.
 *
 * Pure, and the only decision worth testing here: take the highest-priority
 * candidate that isn't stale. Priority beats freshness on purpose — a live trip
 * fix from ten minutes ago is a better account of where the operator was than a
 * last-known location of unknown provenance.
 *
 * Returns null when nothing qualifies, and that is a real answer: the QSO is
 * logged with no coordinates rather than invented ones.
 */
fun chooseRoverFix(candidates: List<RoverFix?>): RoverFix? =
    candidates.filterNotNull().firstOrNull { it.ageMs <= MAX_ROVER_FIX_AGE_MS }

/**
 * The operator's position right now, for stamping onto a logged QSO.
 *
 * Deliberately independent of RTOTA trip mode: a contact made at home, at a park,
 * or parked at a scenic overlook is worth locating too, and the ADIF that carries
 * it is the copy that reaches every other logbook. Trip mode is simply the best
 * *source* when it happens to be running.
 *
 * Never throws and never blocks on the network — every path is either an in-memory
 * read or `getLastKnownLocation`, which returns whatever the OS already had.
 */
object RoverPosition {
    private const val TAG = "RoverPosition"

    fun current(context: Context?): RoverFix? {
        val ctx = context ?: return null
        return chooseRoverFix(listOf(liveTripFix(), lastKnownFix(ctx)))
    }

    /** The freshest fix the running trip has seen, if a trip is running at all. */
    private fun liveTripFix(): RoverFix? {
        val point = RtotaTripManager.latestFix() ?: return null
        return RoverFix(
            latitude = point.latitude,
            longitude = point.longitude,
            ageMs = (System.currentTimeMillis() - point.timestampMs).coerceAtLeast(0L),
            source = RoverPositionSource.LIVE_FIX,
        )
    }

    /**
     * The best last known location across providers.
     *
     * Unlike `MaidenheadGrid.getLocalLocation`, this keeps the fix's timestamp — a
     * last known location can be days old, and one from yesterday's drive would
     * put today's contact in the wrong state entirely.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun lastKnownFix(ctx: Context): RoverFix? {
        if (!hasLocationPermission(ctx)) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val now = System.currentTimeMillis()
            lm.getProviders(true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    lm.getLastKnownLocation(provider)
                }
                .minByOrNull { now - it.time }
                ?.let {
                    RoverFix(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        ageMs = (now - it.time).coerceAtLeast(0L),
                        source = RoverPositionSource.LAST_KNOWN,
                    )
                }
        } catch (e: Exception) {
            Log.d(TAG, "last-known lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }

}
