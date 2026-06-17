package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link QSLCallsignRecord}, the grouped-by-callsign logbook model.
 * String setters null-coalesce to ""; flags/ids carry documented defaults.
 * Pure POJO — plain JUnit.
 */
public class QSLCallsignRecordTest {

    @Test
    public void defaults_areZeroAndFalse() {
        QSLCallsignRecord r = new QSLCallsignRecord();
        assertThat(r.id).isEqualTo(0);
        assertThat(r.where).isNull();
        assertThat(r.dxccStr).isEmpty();
        assertThat(r.isQSL).isFalse();
        assertThat(r.isLotW_QSL).isFalse();
        assertThat(r.syncedCloudlog).isFalse();
        assertThat(r.syncedQrz).isFalse();
    }

    @Test
    public void setters_roundTripValues() {
        QSLCallsignRecord r = new QSLCallsignRecord();
        r.setCallsign("W1AW");
        r.setMode("FT8");
        r.setGrid("FN31");
        r.setBand("20m");
        r.setLastTime("20231114 221320");

        assertThat(r.getCallsign()).isEqualTo("W1AW");
        assertThat(r.getMode()).isEqualTo("FT8");
        assertThat(r.getGrid()).isEqualTo("FN31");
        assertThat(r.getBand()).isEqualTo("20m");
        assertThat(r.getLastTime()).isEqualTo("20231114 221320");
    }

    @Test
    public void setters_nullCoalesceToEmpty() {
        QSLCallsignRecord r = new QSLCallsignRecord();
        r.setCallsign(null);
        r.setMode(null);
        r.setGrid(null);
        r.setBand(null);
        r.setLastTime(null);

        assertThat(r.getCallsign()).isEmpty();
        assertThat(r.getMode()).isEmpty();
        assertThat(r.getGrid()).isEmpty();
        assertThat(r.getBand()).isEmpty();
        assertThat(r.getLastTime()).isEmpty();
    }

    @Test
    public void publicFlags_areMutable() {
        QSLCallsignRecord r = new QSLCallsignRecord();
        r.id = 42;
        r.isQSL = true;
        r.syncedQrz = true;
        r.dxccStr = "United States";
        assertThat(r.id).isEqualTo(42);
        assertThat(r.isQSL).isTrue();
        assertThat(r.syncedQrz).isTrue();
        assertThat(r.dxccStr).isEqualTo("United States");
    }
}
