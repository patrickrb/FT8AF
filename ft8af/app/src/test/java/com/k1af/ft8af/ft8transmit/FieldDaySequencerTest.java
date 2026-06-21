package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Coverage for {@link FT8TransmitSignal#buildFieldDayMessage(String, int)},
 * the helper that constructs Field Day exchange messages for the auto-sequencer.
 *
 * Plain JUnit: touches only static fields on GeneralVariables (no Android
 * framework dependency) and the pure-Java buildFieldDayMessage helper.
 */
public class FieldDaySequencerTest {

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "K1ABC";
        GeneralVariables.fieldDayClass = "A";
        GeneralVariables.fieldDayNumTx = 6;
        GeneralVariables.fieldDaySection = "WI";
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        GeneralVariables.fieldDayClass = "A";
        GeneralVariables.fieldDayNumTx = 1;
        GeneralVariables.fieldDaySection = "";
    }

    @Test
    public void buildFieldDayMessage_noR_setsI3Zero() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.i3).isEqualTo(0);
    }

    @Test
    public void buildFieldDayMessage_noR_setsN3Three() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.n3).isEqualTo(3);
    }

    @Test
    public void buildFieldDayMessage_withR_setsN3Four() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 1);
        assertThat(msg.n3).isEqualTo(4);
    }

    @Test
    public void buildFieldDayMessage_setsRFlag() {
        Ft8Message noR = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(noR.r_flag).isEqualTo(0);

        Ft8Message withR = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 1);
        assertThat(withR.r_flag).isEqualTo(1);
    }

    @Test
    public void buildFieldDayMessage_setsCallsigns() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.callsignTo).isEqualTo("W9XYZ");
        assertThat(msg.callsignFrom).isEqualTo("K1ABC");
    }

    @Test
    public void buildFieldDayMessage_setsClass() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.arrl_class).isEqualTo("A");
    }

    @Test
    public void buildFieldDayMessage_setsNumTx() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.eu_serial).isEqualTo(6);
    }

    @Test
    public void buildFieldDayMessage_setsSection() {
        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("W9XYZ", 0);
        assertThat(msg.arrl_rac).isEqualTo("WI");
    }

    @Test
    public void buildFieldDayMessage_picksUpChangedSettings() {
        GeneralVariables.fieldDayClass = "B";
        GeneralVariables.fieldDayNumTx = 16;
        GeneralVariables.fieldDaySection = "EMA";

        Ft8Message msg = FT8TransmitSignal.buildFieldDayMessage("N0CALL", 0);
        assertThat(msg.arrl_class).isEqualTo("B");
        assertThat(msg.eu_serial).isEqualTo(16);
        assertThat(msg.arrl_rac).isEqualTo("EMA");
    }
}
