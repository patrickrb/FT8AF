package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the WSJT-X-style worked-station policy — the [isWorkedForScope]
 * decision and the [WorkedStationMode] / [WorkedStationScope] ordinal mapping. No
 * Android or GeneralVariables state is touched (the scope membership tests are
 * injected as lambdas), so no Robolectric runner is needed.
 */
class WorkedStationsTest {

    /**
     * Build [isWorkedForScope] over a fixed set of membership fixtures so each test
     * only has to pick the scope. "ONBAND" is worked on band, "OTHER" on another
     * band, "TODAY" today, "LISTED" in the from-list.
     */
    private fun worked(callsign: String?, scope: WorkedStationScope): Boolean =
        isWorkedForScope(
            callsign,
            scope,
            workedOnBand = { it == "ONBAND" },
            workedAnyBand = { it == "OTHER" },
            workedToday = { it == "TODAY" },
            inWorkedList = { it == "LISTED" },
        )

    @Test
    fun onBandScope_matchesOnlyCurrentBand() {
        assertThat(worked("ONBAND", WorkedStationScope.ON_BAND)).isTrue()
        assertThat(worked("OTHER", WorkedStationScope.ON_BAND)).isFalse()
        assertThat(worked("TODAY", WorkedStationScope.ON_BAND)).isFalse()
        assertThat(worked("LISTED", WorkedStationScope.ON_BAND)).isFalse()
    }

    @Test
    fun beforeScope_matchesCurrentOrOtherBand() {
        assertThat(worked("ONBAND", WorkedStationScope.BEFORE)).isTrue()
        assertThat(worked("OTHER", WorkedStationScope.BEFORE)).isTrue()
        assertThat(worked("TODAY", WorkedStationScope.BEFORE)).isFalse()
        assertThat(worked("LISTED", WorkedStationScope.BEFORE)).isFalse()
    }

    @Test
    fun todayScope_matchesOnlyToday() {
        assertThat(worked("TODAY", WorkedStationScope.TODAY)).isTrue()
        assertThat(worked("ONBAND", WorkedStationScope.TODAY)).isFalse()
        assertThat(worked("OTHER", WorkedStationScope.TODAY)).isFalse()
        assertThat(worked("LISTED", WorkedStationScope.TODAY)).isFalse()
    }

    @Test
    fun fromListScope_matchesOnlyList() {
        assertThat(worked("LISTED", WorkedStationScope.FROM_LIST)).isTrue()
        assertThat(worked("ONBAND", WorkedStationScope.FROM_LIST)).isFalse()
        assertThat(worked("OTHER", WorkedStationScope.FROM_LIST)).isFalse()
        assertThat(worked("TODAY", WorkedStationScope.FROM_LIST)).isFalse()
    }

    @Test
    fun blankOrNullCallsign_isNeverWorked() {
        assertThat(worked(null, WorkedStationScope.ON_BAND)).isFalse()
        assertThat(worked("", WorkedStationScope.BEFORE)).isFalse()
        assertThat(worked("   ", WorkedStationScope.FROM_LIST)).isFalse()
    }

    @Test
    fun callsignIsTrimmedBeforeLookup() {
        // The predicates match the exact token, so trimming must happen first.
        assertThat(worked("  ONBAND  ", WorkedStationScope.ON_BAND)).isTrue()
    }

    @Test
    fun mode_fromInt_mapsOrdinalsAndDefaultsToHighlight() {
        assertThat(WorkedStationMode.fromInt(0)).isEqualTo(WorkedStationMode.HIGHLIGHT)
        assertThat(WorkedStationMode.fromInt(1)).isEqualTo(WorkedStationMode.IGNORE)
        assertThat(WorkedStationMode.fromInt(2)).isEqualTo(WorkedStationMode.HIDE)
        // Out-of-range persisted values (stale/hand-edited config) fall back safely.
        assertThat(WorkedStationMode.fromInt(-1)).isEqualTo(WorkedStationMode.HIGHLIGHT)
        assertThat(WorkedStationMode.fromInt(99)).isEqualTo(WorkedStationMode.HIGHLIGHT)
    }

    @Test
    fun scope_fromInt_mapsOrdinalsAndDefaultsToOnBand() {
        assertThat(WorkedStationScope.fromInt(0)).isEqualTo(WorkedStationScope.ON_BAND)
        assertThat(WorkedStationScope.fromInt(1)).isEqualTo(WorkedStationScope.BEFORE)
        assertThat(WorkedStationScope.fromInt(2)).isEqualTo(WorkedStationScope.TODAY)
        assertThat(WorkedStationScope.fromInt(3)).isEqualTo(WorkedStationScope.FROM_LIST)
        assertThat(WorkedStationScope.fromInt(7)).isEqualTo(WorkedStationScope.ON_BAND)
    }
}
