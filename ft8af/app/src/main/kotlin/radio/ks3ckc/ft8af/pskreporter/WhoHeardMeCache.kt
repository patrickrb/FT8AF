package radio.ks3ckc.ft8af.pskreporter

/**
 * Process-wide holder for the latest "who heard me" PSKReporter reception reports
 * (stations that spotted our transmission). [PskReporterClient.fetchSpotsForMe]
 * has its own cooldown/rate-limit, so a single shared cache lets multiple screens
 * (the Android Auto car map, and potentially the phone map) plot the same spots
 * without each hammering the server.
 *
 * The car map's fetch loop writes [spots]; the map renderer reads it once per
 * frame. A plain volatile list is enough — readers already redraw on their own
 * 1 Hz tick, so they don't need change notifications.
 */
object WhoHeardMeCache {
    @Volatile
    var spots: List<PskReporterSpot> = emptyList()

    /** Clears the cache — used by tests and when the operator callsign changes. */
    fun clear() {
        spots = emptyList()
    }
}
