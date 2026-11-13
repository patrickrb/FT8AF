package radio.ks3ckc.ft8af.ui.map

/**
 * "Signal reach" summary — the glanceable answer to the single most-asked FT8
 * question: *is my signal getting out, and how far?*
 *
 * The map already fetches PSK Reporter reception reports for the operator's own
 * callsign (the "Heard me" direction) and plots each receiver as a dot. This
 * pure helper reduces that same set of spots to a one-card headline: how many
 * distinct stations heard us, the furthest one (great-circle from our grid),
 * and the receiver that copied us with the strongest signal.
 *
 * Kept free of Compose/Android types so it can be unit-tested directly; the
 * card composable in [MapScreen] is a thin renderer over [summarizeSignalReach].
 */

/** Minimal view of a reception report the reach summary needs. */
internal interface ReachSpot {
    val callsign: String
    val lat: Double
    val lon: Double
    /** SNR (dB) at which this receiver decoded us; higher == copied us stronger. */
    val snr: Int
}

/**
 * Headline reach numbers.
 *
 * @property receivers      distinct stations that heard us
 * @property furthestKm     great-circle distance to the furthest receiver (km);
 *                          0.0 when the operator grid is unknown (no distances)
 * @property furthestCall   callsign of the furthest receiver ("" when unknown)
 * @property strongestSnr   best SNR any receiver reported for us
 * @property strongestCall  callsign of that best-SNR receiver
 */
internal data class SignalReach(
    val receivers: Int,
    val furthestKm: Double,
    val furthestCall: String,
    val strongestSnr: Int,
    val strongestCall: String,
)

/**
 * Summarise [spots] (receivers that heard us) into a [SignalReach], or null when
 * there is nothing to report.
 *
 * Reports are de-duplicated by callsign — a single station often appears more
 * than once (multiple decodes / bands in the window), and "12 stations heard
 * you" should count stations, not lines. For a repeated callsign the strongest
 * SNR and the (identical) location are kept.
 *
 * [opLat]/[opLon] are the operator's position; when null (no grid configured)
 * distances can't be computed, so [SignalReach.furthestKm] is 0.0 and
 * [SignalReach.furthestCall] is empty. The strongest-signal figures never depend
 * on our own position, so they're always populated.
 */
internal fun summarizeSignalReach(
    spots: List<ReachSpot>,
    opLat: Double?,
    opLon: Double?,
): SignalReach? {
    if (spots.isEmpty()) return null

    // Collapse to one entry per callsign, keeping the strongest report we have.
    val byCall = LinkedHashMap<String, ReachSpot>()
    for (spot in spots) {
        val key = spot.callsign.trim().uppercase()
        if (key.isEmpty()) continue
        val existing = byCall[key]
        if (existing == null || spot.snr > existing.snr) {
            byCall[key] = spot
        }
    }
    if (byCall.isEmpty()) return null

    var furthestKm = 0.0
    var furthestCall = ""
    var strongestSnr = Int.MIN_VALUE
    var strongestCall = ""

    val haveOrigin = opLat != null && opLon != null
    for ((call, spot) in byCall) {
        if (haveOrigin) {
            val km = greatCircleKm(opLat!!, opLon!!, spot.lat, spot.lon)
            if (km > furthestKm) {
                furthestKm = km
                furthestCall = call
            }
        }
        if (spot.snr > strongestSnr) {
            strongestSnr = spot.snr
            strongestCall = call
        }
    }

    return SignalReach(
        receivers = byCall.size,
        furthestKm = furthestKm,
        furthestCall = furthestCall,
        strongestSnr = strongestSnr,
        strongestCall = strongestCall,
    )
}
