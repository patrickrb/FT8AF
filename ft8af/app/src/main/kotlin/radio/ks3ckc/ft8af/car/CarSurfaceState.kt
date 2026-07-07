package radio.ks3ckc.ft8af.car

/**
 * A single decoded station to plot on the car map surface. Carries only the
 * projected coordinates and colour — all Maidenhead → lat/lon conversion
 * happens when the snapshot is built, keeping the renderer free of domain logic.
 */
internal data class CarStationMarker(
    val lat: Double,
    val lon: Double,
    /** ARGB colour int matching the phone map's station dot scheme. */
    val colorInt: Int,
)

/**
 * Immutable snapshot of everything the [CarMapSurfaceRenderer] needs to draw one
 * map frame. Built on the main thread by [QsoStatusScreen], then handed to the
 * renderer which reads it on whatever thread holds the surface lock.
 *
 * Only map geometry lives here — the status text (headline, slot, band, POTA) is
 * shown in the MapWithContentTemplate content card, not drawn on the surface.
 */
internal data class CarSurfaceState(
    /** Operator's latitude (from grid), or NaN when unknown. */
    val opLat: Double,
    /** Operator's longitude (from grid), or NaN when unknown. */
    val opLon: Double,
    /** Current QSO partner's latitude (from grid), or NaN when there is no target. */
    val partnerLat: Double,
    /** Current QSO partner's longitude (from grid), or NaN when there is no target. */
    val partnerLon: Double,
    /** Decoded stations with known grids, for map markers (stations we heard). */
    val stations: List<CarStationMarker>,
    /** PSKReporter "who heard me" spots, drawn as hollow rings (stations that heard us). */
    val pskStations: List<CarStationMarker>,
    /** "Calling CQ" / "QSOing with W1XYZ · -12 dB" — the resolved headline. */
    val headlineText: String,
    /** "TX slot · 7 s" or "RX slot · 7 s". */
    val slotText: String,
    /** "14.074 MHz · 20m · FT8". */
    val bandText: String,
    /** "POTA K-1234 · 3 QSOs", or null when no activation is running. */
    val potaText: String?,
)
