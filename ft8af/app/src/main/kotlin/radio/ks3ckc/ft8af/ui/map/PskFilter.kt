package radio.ks3ckc.ft8af.ui.map

/**
 * PSK Reporter overlay filters (band / mode / time window / direction). The map
 * holds one [PskFilter] as state; changing it re-runs the fetch (band is applied
 * client-side, the rest go into the query). Pure + unit-testable.
 */

internal enum class Band(val label: String, val lowHz: Long, val highHz: Long) {
    M160("160m", 1_800_000, 2_000_000),
    M80("80m", 3_500_000, 4_000_000),
    M60("60m", 5_250_000, 5_450_000),
    M40("40m", 7_000_000, 7_300_000),
    M30("30m", 10_100_000, 10_150_000),
    M20("20m", 14_000_000, 14_350_000),
    M17("17m", 18_068_000, 18_168_000),
    M15("15m", 21_000_000, 21_450_000),
    M12("12m", 24_890_000, 24_990_000),
    M10("10m", 28_000_000, 29_700_000),
    M6("6m", 50_000_000, 54_000_000),
}

/** Map a spot frequency (absolute Hz) to its amateur band, or null if out of band. */
internal fun freqHzToBand(freqHz: Long): Band? =
    Band.entries.firstOrNull { freqHz in it.lowHz..it.highHz }

internal enum class PskMode(val label: String) {
    CURRENT("Current"),  // follow the rig's current mode at fetch time
    FT8("FT8"),
    FT4("FT4"),
    FT2("FT2"),
}

internal enum class PskDirection(val label: String) {
    WHO_HEARD_ME("Heard me"),  // receivers that decoded us (senderCallsign=me)
    WHO_I_HEARD("I heard"),    // stations we decoded (receiverCallsign=me)
}

/** Time-window presets in seconds, shown as 15m / 1h / 3h / 6h / 24h. */
internal val PSK_TIME_PRESETS = listOf(900, 3600, 10800, 21600, 86400)

internal fun pskTimeLabel(sec: Int): String = when (sec) {
    900 -> "15m"
    3600 -> "1h"
    10800 -> "3h"
    21600 -> "6h"
    86400 -> "24h"
    else -> "${sec / 60}m"
}

internal data class PskFilter(
    val timeWindowSec: Int = 3600,
    val band: Band? = null,                              // null = all bands
    val mode: PskMode = PskMode.CURRENT,
    val direction: PskDirection = PskDirection.WHO_HEARD_ME,
)

/** Anything carrying an absolute frequency the band filter can read. */
internal interface HasFrequencyHz {
    val frequencyHz: Long
}

/** Client-side band filter — the retrieve API returns all bands, so we cull here. */
internal fun <T : HasFrequencyHz> applyPskBandFilter(spots: List<T>, band: Band?): List<T> =
    if (band == null) spots else spots.filter { freqHzToBand(it.frequencyHz) == band }
