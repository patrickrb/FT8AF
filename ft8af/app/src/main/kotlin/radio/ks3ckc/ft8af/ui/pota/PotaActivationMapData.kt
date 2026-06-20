package radio.ks3ckc.ft8af.ui.pota

import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.pota.model.PotaQso

/** A single contact projected onto the activation map. */
internal data class MapContact(
    val callsign: String,
    val lat: Double,
    val lon: Double,
)

/** Operator location plus the plottable contacts for an activation map. */
internal data class ActivationMapData(
    val operatorLat: Double,
    val operatorLon: Double,
    val contacts: List<MapContact>,
)

/** Parse a Maidenhead grid to (lat, lon), or null if blank/unparseable. */
private fun gridLatLon(grid: String?): Pair<Double, Double>? {
    if (grid.isNullOrBlank()) return null
    return try {
        val ll = MaidenheadGrid.gridToLatLng(grid.trim()) ?: return null
        Pair(ll.latitude, ll.longitude)
    } catch (_: Exception) {
        null
    }
}

/**
 * Build the inputs for [PotaActivationMap] from an activation's QSOs.
 *
 * - Contacts whose [PotaQso.grid] is blank or unparseable are dropped (they
 *   can't be placed on the map).
 * - The operator location is taken from the first QSO with a usable
 *   [PotaQso.myGrid] (so historical activations are placed where the operator
 *   actually was), falling back to [fallbackOperatorGrid] (the live activation's
 *   current grid).
 * - Returns null when the operator location is unknown or there are no plottable
 *   contacts — callers hide the map in that case.
 */
internal fun buildActivationMapData(
    qsos: List<PotaQso>,
    fallbackOperatorGrid: String?,
): ActivationMapData? {
    val operator = qsos.firstNotNullOfOrNull { gridLatLon(it.myGrid) }
        ?: gridLatLon(fallbackOperatorGrid)
        ?: return null

    val contacts = qsos.mapNotNull { qso ->
        val ll = gridLatLon(qso.grid) ?: return@mapNotNull null
        MapContact(callsign = qso.callsign, lat = ll.first, lon = ll.second)
    }
    if (contacts.isEmpty()) return null

    return ActivationMapData(
        operatorLat = operator.first,
        operatorLon = operator.second,
        contacts = contacts,
    )
}
