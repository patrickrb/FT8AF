package com.k1af.ft8af.ft8transmit;

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
        // Order 6 is the CQ baseline (idle state) — not a real contact.
        assertThat(FT8TransmitSignal.shouldForceLog(6)).isFalse();
    }
}
