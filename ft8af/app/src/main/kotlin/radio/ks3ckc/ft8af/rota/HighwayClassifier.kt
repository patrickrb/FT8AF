package radio.ks3ckc.ft8af.rota

import java.util.Locale

/**
 * Turns whatever a reverse geocoder calls the road into the short, stable label
 * the trip's `highway` field carries.
 *
 * The service de-duplicates these into a per-trip "highways traveled" roll-up,
 * so the value has to be *canonical*: "I-70", "I 70 E", "Interstate 70" and
 * "E I-70" are one road, and a roll-up that lists all four is worse than useless.
 * Everything here reduces to one of:
 *
 *  - `I-70`   — an Interstate
 *  - `US-285` — a US route
 *  - `CO-93`  — a state route, prefixed with the state the rover is in
 *  - [LOCAL_ROAD_LABEL] — any other named road
 *  - `null`   — nothing was resolved, which is not the same as "not a highway"
 *
 * The null case earns its keep: geocoding needs network, and this app is built
 * for the hours it doesn't have any. An unresolved fix must stay unlabelled
 * rather than claim the rover was in town, or a trip through a canyon would
 * report city driving across the whole dead zone.
 */

/**
 * Wire label for a road that isn't a numbered Interstate, US or state route.
 *
 * "Local roads" rather than anything city-flavoured: this bucket catches county
 * roads and rural two-lanes as readily as town streets, and a road trip spends
 * plenty of time on the former.
 *
 * Deliberately a hardcoded English constant and *not* a string resource: it
 * travels to the server and is grouped there, so a phone in a Spanish locale
 * must not contribute a second spelling of the same bucket to the roll-up.
 */
const val LOCAL_ROAD_LABEL = "Local roads"

/** Postal codes that may prefix a state route, e.g. "CO-93". */
private val STATE_CODES =
    setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
        "DC", "PR", "VI", "GU", "AS", "MP",
    )

// "I-70", "I 70", "Interstate 70" — the trailing \b keeps "I-7" out of "I-70".
private val INTERSTATE = Regex("""\b(?:I|INTERSTATE)[\s-]?(\d{1,3})\b""")

// "US-285", "US 285", "U.S. 6", "US HIGHWAY 285", "US ROUTE 6".
private val US_ROUTE = Regex("""\bU\.?S\.?[\s-]?(?:HIGHWAY|HWY|ROUTE|RTE|RT)?[\s-]?(\d{1,3})\b""")

// An explicit postal prefix: "CO-93", "TX 130". Validated against STATE_CODES
// below so "CR 73" (county road) and "SR 520" don't masquerade as states.
private val PREFIXED_STATE_ROUTE = Regex("""\b([A-Z]{2})[\s-](\d{1,3})\b""")

// "State Highway 7", "State Route 7", "SH 7", "SR 520", "State Road 7".
private val GENERIC_STATE_ROUTE =
    Regex("""\b(?:STATE[\s-](?:HIGHWAY|HWY|ROUTE|RTE|ROAD|RD)|SH|SR)[\s-]?(\d{1,3})\b""")

/**
 * Canonicalize [roadName] as reported by the geocoder.
 *
 * @param stateCode two-letter state the rover is currently in, used to name a
 *        route the geocoder described only as "State Highway 7". Without it such
 *        a route falls back to an `SR-` prefix rather than guessing a state.
 */
fun classifyHighway(
    roadName: String?,
    stateCode: String? = null,
): String? {
    val raw = roadName?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val name = raw.uppercase(Locale.US)

    INTERSTATE.find(name)?.let { return "I-${it.groupValues[1].trimStart('0').ifEmpty { "0" }}" }
    US_ROUTE.find(name)?.let { return "US-${it.groupValues[1].trimStart('0').ifEmpty { "0" }}" }

    PREFIXED_STATE_ROUTE.find(name)?.let { m ->
        val code = m.groupValues[1]
        if (code in STATE_CODES) {
            return "$code-${m.groupValues[2].trimStart('0').ifEmpty { "0" }}"
        }
    }

    GENERIC_STATE_ROUTE.find(name)?.let { m ->
        val prefix = stateCode?.trim()?.uppercase(Locale.US)?.takeIf { it in STATE_CODES } ?: "SR"
        return "$prefix-${m.groupValues[1].trimStart('0').ifEmpty { "0" }}"
    }

    // A named road we recognise as a road, but not as a numbered route.
    return LOCAL_ROAD_LABEL
}

// ---------------------------------------------------------------------------
// Caching policy (pure — the resolver just obeys it)
// ---------------------------------------------------------------------------

/** Don't re-geocode more often than this, however fast the fixes arrive. */
const val HIGHWAY_REFRESH_MIN_INTERVAL_MS = 45_000L

/** …or before the rover has moved this far, however long it has been. */
const val HIGHWAY_REFRESH_MIN_METERS = 1_500.0

/** A label older than this stops being attached to new points. */
const val HIGHWAY_STALE_AFTER_MS = 10 * 60_000L

/** …as does one earned this far back down the road. */
const val HIGHWAY_STALE_AFTER_METERS = 15_000.0

/**
 * Whether a fresh geocode is worth it.
 *
 * Fixes arrive about once a second and a geocode is a network round trip, so
 * the answer is almost always no. Both a time *and* a distance gate are needed
 * for opposite reasons: parked at a fuel stop, distance alone would never
 * refresh; at 80 mph, time alone would let the label lag miles behind a
 * junction.
 */
fun shouldRefreshHighway(
    lastResolvedAtMs: Long,
    lastResolvedLat: Double,
    lastResolvedLon: Double,
    nowMs: Long,
    lat: Double,
    lon: Double,
): Boolean {
    if (lastResolvedAtMs <= 0L) return true
    if (nowMs - lastResolvedAtMs >= HIGHWAY_REFRESH_MIN_INTERVAL_MS) return true
    return haversineMeters(lastResolvedLat, lastResolvedLon, lat, lon) >= HIGHWAY_REFRESH_MIN_METERS
}

/**
 * The label to attach to a point at ([lat], [lon]), or null when the cached one
 * has outlived its usefulness.
 *
 * Reusing a recent label is the whole reason this works offline-ish: a highway
 * persists for tens of miles, so one successful geocode legitimately covers many
 * points. But it has to expire — carrying "I-70" through a two-hour dead zone
 * would invent a route the rover may have left at the first exit.
 */
fun highwayLabelForPoint(
    cached: String?,
    cachedAtMs: Long,
    cachedLat: Double,
    cachedLon: Double,
    nowMs: Long,
    lat: Double,
    lon: Double,
): String? {
    if (cached.isNullOrEmpty() || cachedAtMs <= 0L) return null
    if (nowMs - cachedAtMs > HIGHWAY_STALE_AFTER_MS) return null
    if (haversineMeters(cachedLat, cachedLon, lat, lon) > HIGHWAY_STALE_AFTER_METERS) return null
    return cached
}
