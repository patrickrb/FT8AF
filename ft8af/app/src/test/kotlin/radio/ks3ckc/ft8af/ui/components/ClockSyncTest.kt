package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM tests for the clock-sync classifier/formatter (no Compose/Android needed). */
class ClockSyncTest {

    @Test
    fun level_goodWhenNearZero() {
        assertThat(clockSyncLevel(0f)).isEqualTo(ClockSyncLevel.GOOD)
        assertThat(clockSyncLevel(0.1f)).isEqualTo(ClockSyncLevel.GOOD)
        assertThat(clockSyncLevel(-0.2f)).isEqualTo(ClockSyncLevel.GOOD)
        // Boundary: exactly the GOOD threshold is still GOOD.
        assertThat(clockSyncLevel(0.3f)).isEqualTo(ClockSyncLevel.GOOD)
        assertThat(clockSyncLevel(-0.3f)).isEqualTo(ClockSyncLevel.GOOD)
    }

    @Test
    fun level_fairInTheMiddleBand() {
        assertThat(clockSyncLevel(0.31f)).isEqualTo(ClockSyncLevel.FAIR)
        assertThat(clockSyncLevel(-0.7f)).isEqualTo(ClockSyncLevel.FAIR)
        // Boundary: exactly the FAIR threshold is still FAIR.
        assertThat(clockSyncLevel(1.0f)).isEqualTo(ClockSyncLevel.FAIR)
        assertThat(clockSyncLevel(-1.0f)).isEqualTo(ClockSyncLevel.FAIR)
    }

    @Test
    fun level_poorWhenFarFromZero() {
        assertThat(clockSyncLevel(1.01f)).isEqualTo(ClockSyncLevel.POOR)
        assertThat(clockSyncLevel(-2.4f)).isEqualTo(ClockSyncLevel.POOR)
    }

    @Test
    fun level_unknownForNullOrNonFinite() {
        assertThat(clockSyncLevel(null)).isEqualTo(ClockSyncLevel.UNKNOWN)
        assertThat(clockSyncLevel(Float.NaN)).isEqualTo(ClockSyncLevel.UNKNOWN)
        assertThat(clockSyncLevel(Float.POSITIVE_INFINITY)).isEqualTo(ClockSyncLevel.UNKNOWN)
    }

    @Test
    fun offsetLabel_signedToOneDecimalSecond() {
        assertThat(clockSyncOffsetLabel(0.14f)).isEqualTo("+0.1 s")
        assertThat(clockSyncOffsetLabel(-1.25f)).isEqualTo("-1.3 s")
    }

    @Test
    fun offsetLabel_zeroHasNoSign() {
        assertThat(clockSyncOffsetLabel(0f)).isEqualTo("0.0 s")
        // A value that rounds to zero should also render unsigned, not "-0.0 s".
        assertThat(clockSyncOffsetLabel(-0.02f)).isEqualTo("0.0 s")
    }

    @Test
    fun offsetLabel_dashWhenUnknown() {
        assertThat(clockSyncOffsetLabel(null)).isEqualTo("—")
        assertThat(clockSyncOffsetLabel(Float.NaN)).isEqualTo("—")
    }

    @Test
    fun autoBadge_noneWhenManualOnly() {
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = false, disciplineFromGps = false)).isNull()
    }

    @Test
    fun autoBadge_autoWhenSelfSyncOwnsTheClock() {
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = true, disciplineFromGps = false))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_auto)
    }

    @Test
    fun autoBadge_gpsWinsOverSelfSync() {
        // The estimator stands down while GPS disciplines the clock, so GPS is what
        // the operator is actually relying on.
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = true, disciplineFromGps = true))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_gps)
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = false, disciplineFromGps = true))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_gps)
    }

    @Test
    fun autoBadge_ntpWinsOverSelfSync() {
        // Same stand-down rule as GPS: while NTP disciplines the clock the estimator is
        // inert, so NTP is what the operator is actually relying on.
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = true, disciplineFromGps = false, disciplineFromNtp = true))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_ntp)
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = false, disciplineFromGps = false, disciplineFromNtp = true))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_ntp)
    }

    @Test
    fun autoBadge_gpsTieBreaksOverNtpIfBothSomehowOn() {
        assertThat(clockSyncAutoBadge(autoSyncFromDecodes = true, disciplineFromGps = true, disciplineFromNtp = true))
            .isEqualTo(com.k1af.ft8af.R.string.clock_sync_badge_gps)
    }
}
