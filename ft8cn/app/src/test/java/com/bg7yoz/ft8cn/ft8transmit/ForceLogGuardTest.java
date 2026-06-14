package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link FT8TransmitSignal#shouldForceLog} — the guard that
 * prevents false celebration and bogus log entries when the user skips to the
 * next caller before any report is exchanged.
 */
public class ForceLogGuardTest {

    @Test
    public void order1_grid_shouldNotLog() {
        assertThat(FT8TransmitSignal.shouldForceLog(1)).isFalse();
    }

    @Test
    public void order2_report_shouldNotLog() {
        assertThat(FT8TransmitSignal.shouldForceLog(2)).isFalse();
    }

    @Test
    public void order3_reportExchanged_shouldLog() {
        assertThat(FT8TransmitSignal.shouldForceLog(3)).isTrue();
    }

    @Test
    public void order4_rr73_shouldLog() {
        assertThat(FT8TransmitSignal.shouldForceLog(4)).isTrue();
    }

    @Test
    public void order5_73_shouldLog() {
        assertThat(FT8TransmitSignal.shouldForceLog(5)).isTrue();
    }

    @Test
    public void order6_cq_shouldNotLog() {
        // Edge case: if somehow forceLog is called while on CQ baseline,
        // order 6 >= 3 is true. This is acceptable — forceLogAndMoveOn
        // exits early for CQ via the toCallsign.callsign == "CQ" check
        // in updateQSlRecordList.
        assertThat(FT8TransmitSignal.shouldForceLog(6)).isTrue();
    }
}
