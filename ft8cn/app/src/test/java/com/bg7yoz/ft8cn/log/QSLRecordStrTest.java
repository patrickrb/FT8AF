package com.bg7yoz.ft8cn.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link QSLRecordStr}, the adapter display model. Every String
 * setter null-coalesces to "" so the UI never binds a null; the getters round-trip
 * the stored value. Pure POJO — plain JUnit.
 */
public class QSLRecordStrTest {

    @Test
    public void stringFields_defaultToEmpty() {
        QSLRecordStr r = new QSLRecordStr();
        assertThat(r.getCall()).isEmpty();
        assertThat(r.getGridsquare()).isEmpty();
        assertThat(r.getMode()).isEmpty();
        assertThat(r.getRst_sent()).isEmpty();
        assertThat(r.getRst_rcvd()).isEmpty();
        assertThat(r.getTime_on()).isEmpty();
        assertThat(r.getTime_off()).isEmpty();
        assertThat(r.getBand()).isEmpty();
        assertThat(r.getFreq()).isEmpty();
        assertThat(r.getStation_callsign()).isEmpty();
        assertThat(r.getMy_gridsquare()).isEmpty();
    }

    @Test
    public void booleanFlags_defaultFalse() {
        QSLRecordStr r = new QSLRecordStr();
        assertThat(r.isQSL).isFalse();
        assertThat(r.isLotW_import).isFalse();
        assertThat(r.isLotW_QSL).isFalse();
        assertThat(r.where).isNull();
    }

    @Test
    public void setters_roundTripValues() {
        QSLRecordStr r = new QSLRecordStr();
        r.setCall("W1AW");
        r.setGridsquare("FN31");
        r.setMode("FT8");
        r.setRst_sent("-05");
        r.setRst_rcvd("-10");
        r.setTime_on("20231114 221320");
        r.setTime_off("20231114 221335");
        r.setBand("20m");
        r.setFreq("14.074");
        r.setStation_callsign("K1ABC");
        r.setMy_gridsquare("EM12");
        r.setComment("hi");

        assertThat(r.getCall()).isEqualTo("W1AW");
        assertThat(r.getGridsquare()).isEqualTo("FN31");
        assertThat(r.getMode()).isEqualTo("FT8");
        assertThat(r.getRst_sent()).isEqualTo("-05");
        assertThat(r.getRst_rcvd()).isEqualTo("-10");
        assertThat(r.getTime_on()).isEqualTo("20231114 221320");
        assertThat(r.getTime_off()).isEqualTo("20231114 221335");
        assertThat(r.getBand()).isEqualTo("20m");
        assertThat(r.getFreq()).isEqualTo("14.074");
        assertThat(r.getStation_callsign()).isEqualTo("K1ABC");
        assertThat(r.getMy_gridsquare()).isEqualTo("EM12");
        assertThat(r.getComment()).isEqualTo("hi");
    }

    @Test
    public void setters_nullCoalesceToEmpty() {
        QSLRecordStr r = new QSLRecordStr();
        r.setCall("W1AW");
        r.setCall(null);
        r.setMode(null);
        r.setComment(null);
        assertThat(r.getCall()).isEmpty();
        assertThat(r.getMode()).isEmpty();
        assertThat(r.getComment()).isEmpty();
    }
}
