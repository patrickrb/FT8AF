package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables

/**
 * WSJT-X / JTDX-style "worked before" handling for the decode list.
 *
 * WSJT-X-improved and JTDX let the operator "hide, ignore, or highlight stations
 * worked before on the current band, worked today, or those present in a list".
 * This file models the same two orthogonal choices — a [WorkedStationMode]
 * (what to do) and a [WorkedStationScope] (which stations count as worked) — and
 * exposes them as small pure functions so the decode-list Composable and filter
 * stay thin wrappers that are covered by unit tests.
 */

/** What to do with a station that counts as worked. */
enum class WorkedStationMode {
    /** Tag it with the WORKED pill (the legacy FT8AF behavior). */
    HIGHLIGHT,

    /** Leave it visible in the list but don't call attention to it. */
    IGNORE,

    /** Drop it from the decode list (unless it is calling us). */
    HIDE,
    ;

    companion object {
        /** Map a persisted ordinal to a mode, defaulting to [HIGHLIGHT] for any stray value. */
        fun fromInt(value: Int): WorkedStationMode = entries.getOrElse(value) { HIGHLIGHT }
    }
}

/** Which stations count as "worked" for the purpose of [WorkedStationMode]. */
enum class WorkedStationScope {
    /** Worked before on the current band (the legacy FT8AF basis). */
    ON_BAND,

    /** Worked before at any time on any band. */
    BEFORE,

    /** Worked today or yesterday, any band. */
    TODAY,

    /** Present in the user-maintained worked-station list. */
    FROM_LIST,
    ;

    companion object {
        /** Map a persisted ordinal to a scope, defaulting to [ON_BAND] for any stray value. */
        fun fromInt(value: Int): WorkedStationScope = entries.getOrElse(value) { ON_BAND }
    }
}

/**
 * Pure worked-state decision: given the [scope] and per-scope membership tests,
 * decide whether [callsign] counts as worked. Kept free of Android/GeneralVariables
 * so it can be unit-tested directly with fake predicates.
 *
 * A blank/null callsign is never worked.
 */
internal fun isWorkedForScope(
    callsign: String?,
    scope: WorkedStationScope,
    workedOnBand: (String) -> Boolean,
    workedAnyBand: (String) -> Boolean,
    workedToday: (String) -> Boolean,
    inWorkedList: (String) -> Boolean,
): Boolean {
    val call = callsign?.trim().orEmpty()
    if (call.isEmpty()) return false
    return when (scope) {
        WorkedStationScope.ON_BAND -> workedOnBand(call)
        // "worked ever" is this band OR any other band.
        WorkedStationScope.BEFORE -> workedOnBand(call) || workedAnyBand(call)
        WorkedStationScope.TODAY -> workedToday(call)
        WorkedStationScope.FROM_LIST -> inWorkedList(call)
    }
}

/**
 * The active worked-station mode, or null when worked handling is disabled
 * (master toggle [GeneralVariables.highlightWorked] off).
 */
internal fun effectiveWorkedMode(): WorkedStationMode? =
    if (!GeneralVariables.highlightWorked) null
    else WorkedStationMode.fromInt(GeneralVariables.workedStationMode)

/**
 * Whether [message]'s sender counts as worked under the operator's configured
 * scope, resolving the per-scope membership tests against [GeneralVariables].
 */
internal fun isWorkedStation(message: Ft8Message): Boolean = isWorkedForScope(
    message.getCallsignFrom(),
    WorkedStationScope.fromInt(GeneralVariables.workedStationScope),
    // Fall back to the message's decode-time cached flag as well as the live list:
    // a QSO completed after decode leaves isQSL_Callsign false, so the live check
    // catches it; a stale-but-true flag still counts (see QslDisplayFreshnessTest).
    workedOnBand = { message.isQSL_Callsign || GeneralVariables.checkQSLCallsign(it) },
    workedAnyBand = { GeneralVariables.checkQSLCallsign_OtherBand(it) },
    workedToday = { GeneralVariables.checkQSLCallsignToday(it) },
    inWorkedList = { GeneralVariables.checkWorkedListCallsign(it) },
)

/**
 * Whether [message] should be dropped from the decode list because of the HIDE
 * worked-station mode. Stations calling the operator are always kept so a reply
 * to our own CQ is never hidden.
 */
internal fun isHiddenAsWorked(message: Ft8Message): Boolean {
    if (effectiveWorkedMode() != WorkedStationMode.HIDE) return false
    if (GeneralVariables.checkIsMyCallsign(message.callsignTo ?: "")) return false
    return isWorkedStation(message)
}
