package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the Yaesu gen-3 / Kenwood-style ASCII CAT parser and
 * its meter (ALC/SWR) helpers for the FT-991 (38), FT-891 (39) and TS-590.
 *
 * Frames are {@code <2-letter ID><data>}; frequency replies are FA/FB with an
 * integer Hz string. Meter replies are RM frames whose first data char selects
 * the meter (6=SWR, 4=ALC for 38/39; index 2 = 3 ALC / 1 SWR for the 590).
 */
public class Yaesu3CommandTest {

    @Test
    public void getCommand_splitsIdAndData() {
        Yaesu3Command c = Yaesu3Command.getCommand("FA00014074000");
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo("FA");
        assertThat(c.getData()).isEqualTo("00014074000");
    }

    @Test
    public void getCommand_tooShortOrNonAlphaReturnsNull() {
        assertThat(Yaesu3Command.getCommand("F")).isNull();
        assertThat(Yaesu3Command.getCommand("99x")).isNull();
    }

    @Test
    public void getFrequency_parsesFaFb_elseZero() {
        assertThat(Yaesu3Command.getFrequency(Yaesu3Command.getCommand("FA00014074000")))
                .isEqualTo(14_074_000L);
        assertThat(Yaesu3Command.getFrequency(Yaesu3Command.getCommand("FB00007074000")))
                .isEqualTo(7_074_000L);
        assertThat(Yaesu3Command.getFrequency(Yaesu3Command.getCommand("MD02")))
                .isEqualTo(0L);
    }

    @Test
    public void getFrequency_unparseableReturnsZero() {
        assertThat(Yaesu3Command.getFrequency(Yaesu3Command.getCommand("FAabcd")))
                .isEqualTo(0L);
    }

    // ---------- FT-991 (38) ----------

    @Test
    public void ft38_meterSelectorsAndValue() {
        // data = "6125000": selector '6' = SWR; value = substring(1,4) = "125".
        Yaesu3Command swr = Yaesu3Command.getCommand("RM6125000");
        assertThat(Yaesu3Command.isSWRMeter38(swr)).isTrue();
        assertThat(Yaesu3Command.isALCMeter38(swr)).isFalse();
        assertThat(Yaesu3Command.getALCOrSWR38(swr)).isEqualTo(125);

        // selector '4' = ALC.
        Yaesu3Command alc = Yaesu3Command.getCommand("RM4090000");
        assertThat(Yaesu3Command.isALCMeter38(alc)).isTrue();
        assertThat(Yaesu3Command.isSWRMeter38(alc)).isFalse();
        assertThat(Yaesu3Command.getALCOrSWR38(alc)).isEqualTo(90);
    }

    @Test
    public void ft38_shortDataReturnsDefaults() {
        // data length < 7 -> value 0, both selectors false.
        Yaesu3Command c = Yaesu3Command.getCommand("RM6125"); // data "6125" len 4
        assertThat(Yaesu3Command.getALCOrSWR38(c)).isEqualTo(0);
        assertThat(Yaesu3Command.isSWRMeter38(c)).isFalse();
        assertThat(Yaesu3Command.isALCMeter38(c)).isFalse();
    }

    // ---------- FT-891 (39) ----------

    @Test
    public void ft39_meterSelectorsAndValue() {
        // data = "6125": selector '6' = SWR; value = substring(1,4) = "125".
        Yaesu3Command swr = Yaesu3Command.getCommand("RM6125");
        assertThat(Yaesu3Command.isSWRMeter39(swr)).isTrue();
        assertThat(Yaesu3Command.isALCMeter39(swr)).isFalse();
        assertThat(Yaesu3Command.getSWROrALC39(swr)).isEqualTo(125);

        Yaesu3Command alc = Yaesu3Command.getCommand("RM4090");
        assertThat(Yaesu3Command.isALCMeter39(alc)).isTrue();
        assertThat(Yaesu3Command.isSWRMeter39(alc)).isFalse();
        assertThat(Yaesu3Command.getSWROrALC39(alc)).isEqualTo(90);
    }

    @Test
    public void ft39_shortDataReturnsDefaults() {
        Yaesu3Command c = Yaesu3Command.getCommand("RM6"); // data "6" len 1
        assertThat(Yaesu3Command.getSWROrALC39(c)).isEqualTo(0);
        assertThat(Yaesu3Command.isSWRMeter39(c)).isFalse();
        assertThat(Yaesu3Command.isALCMeter39(c)).isFalse();
    }

    // ---------- TS-590 ----------

    @Test
    public void ts590_alcSelectorAndValue() {
        // data "01300": index2 == '3' -> ALC; value = substring(1,5) = "1300".
        Yaesu3Command alc = Yaesu3Command.getCommand("RM01300");
        assertThat(Yaesu3Command.is590MeterALC(alc)).isTrue();
        assertThat(Yaesu3Command.is590MeterSWR(alc)).isFalse();
        assertThat(Yaesu3Command.get590ALCOrSWR(alc)).isEqualTo(1300);
    }

    @Test
    public void ts590_swrSelector() {
        // data "01100": index2 == '1' -> SWR.
        Yaesu3Command swr = Yaesu3Command.getCommand("RM01100");
        assertThat(Yaesu3Command.is590MeterSWR(swr)).isTrue();
        assertThat(Yaesu3Command.is590MeterALC(swr)).isFalse();
        assertThat(Yaesu3Command.get590ALCOrSWR(swr)).isEqualTo(1100);
    }

    @Test
    public void ts590_shortDataSelectorsFalse() {
        Yaesu3Command c = Yaesu3Command.getCommand("RM01"); // data "01" len 2
        assertThat(Yaesu3Command.is590MeterALC(c)).isFalse();
        assertThat(Yaesu3Command.is590MeterSWR(c)).isFalse();
    }
}
