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
 * The status text (headline, slot, band, activation) is carried here too and drawn
 * as banners directly on the surface by [CarMapSurfaceRenderer] — the
 * NavigationTemplate has no host content card, so nothing is host-rendered.
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
    /**
     * The activation lines drawn under the band: a POTA and/or ROTA line while
     * activating ("POTA K-1234 · 12 QSOs", "ROTA Route 66 · 0 QSOs"), or a single
     * session line ("Session · 5 QSOs") when neither is active. Empty draws nothing.
     */
    val activationLines: List<String>,
)
