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
 * frame. Built on the main thread by [QsoStatusScreen], then handed to the
 * renderer which reads it on whatever thread holds the surface lock.
 *
 * Pre-resolved text strings avoid the renderer needing a `Context` to resolve
 * string resources (and avoid touching LiveData off the main thread).
 */
internal data class CarSurfaceState(
    /** Operator's latitude (from grid), or NaN when unknown. */
    val opLat: Double,
    /** Operator's longitude (from grid), or NaN when unknown. */
    val opLon: Double,
    /** Decoded stations with known grids, for map markers. */
    val stations: List<CarStationMarker>,
    /** "Calling CQ" / "QSOing with W1XYZ · -12 dB" — the resolved headline. */
    val headlineText: String,
    /** The TX message text, or null when not transmitting. */
    val txMessageText: String?,
    /** "Step RR73 (4/6)", or null when TX is off. */
    val seqText: String?,
    /** "TX slot · 7 s" or "RX slot · 7 s". */
    val slotText: String,
    /** "14.074 MHz · 20m · FT8". */
    val bandText: String,
    /** "POTA K-1234 · 3 QSOs", or null when no activation is running. */
    val potaText: String?,
    /** Current TX state — drives the indicator dot colour. */
    val txState: CarTxState,
)
