package radio.ks3ckc.ft8af.ui.settings

import androidx.annotation.StringRes
import com.k1af.ft8af.R
import com.k1af.ft8af.log.ThirdPartyService

/**
 * Does the Station ID in the Cloudlog / Wavelog dialog stand a chance of being accepted?
 *
 * Test Connection only proves the API key (`api/auth`), and the station list it fetches
 * afterwards is scoped to that key's owner. Wavelog rejects every QSO whose
 * `station_profile_id` is blank or belongs to a different user with
 * "station id does not belong to the API key owner." A blank Station ID is certain to
 * fail. One missing from the fetched list only probably will: Wavelog's `station_info` is
 * limited to the active logbook when the user has `active_log_only` set, while uploads
 * check ownership against all of the user's stations, so that case is advisory. Pure so it
 * is unit-testable; the composable just shows [stationIdWarning].
 */
enum class StationIdCheck {
    /** The ID is one of the key owner's stations. */
    OK,

    /** No ID entered at all. */
    BLANK,

    /**
     * The server listed stations for this key and this ID is not among them. Advisory: the
     * list may be scoped to the active logbook (see the class doc).
     */
    NOT_ON_SERVER,

    /** No station list to judge against (not fetched yet, or the key owner has none). */
    UNKNOWN,
}

internal fun checkStationId(
    stationId: String,
    stations: List<ThirdPartyService.StationProfile>,
): StationIdCheck {
    val id = stationId.trim()
    if (id.isEmpty()) return StationIdCheck.BLANK
    if (stations.isEmpty()) return StationIdCheck.UNKNOWN
    return if (stations.any { it.stationId == id }) StationIdCheck.OK else StationIdCheck.NOT_ON_SERVER
}

/** A warning line under Test Connection: its text and whether it is advisory (vs. certain). */
internal data class StationIdWarning(
    @StringRes val messageRes: Int,
    val advisory: Boolean,
)

/** The warning to show for [check], or null when there is nothing to say. */
internal fun stationIdWarning(check: StationIdCheck): StationIdWarning? =
    when (check) {
        StationIdCheck.BLANK -> StationIdWarning(R.string.settings_station_id_missing, advisory = false)
        StationIdCheck.NOT_ON_SERVER -> StationIdWarning(R.string.settings_station_id_not_on_server, advisory = true)
        StationIdCheck.OK, StationIdCheck.UNKNOWN -> null
    }
