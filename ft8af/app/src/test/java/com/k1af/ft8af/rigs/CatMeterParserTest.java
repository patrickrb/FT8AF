package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the numeric CAT meter parsers on {@link Yaesu3Command} and
 * {@link ElecraftCommand}.
 *
 * These parsers run per-CAT-poll on {@code onReceiveData}, which on the
 * Bluetooth SPP path executes on the main thread with no surrounding try/catch
 * ({@code BluetoothSerialService} -> {@code BluetoothRigConnector.onSerialRead}
 * -> {@code BaseRig.onReceiveData}). A garbled or non-numeric meter reply from
 * the rig therefore used to throw {@link NumberFormatException} (or, for
 * {@code get590ALCOrSWR}, {@link StringIndexOutOfBoundsException}) and hard-crash
 * the app. The parsers must instead treat malformed data as "no reading" (0).
 *
 * Plain JUnit: the tested code only touches {@code android.util.Log}, which is
 * stubbed by {@code testOptions.unitTests.returnDefaultValues}.
 */
public class CatMeterParserTest {

    // ---- Yaesu3Command.getALCOrSWR38 (FTDX / FT-950 family, data length >= 7) ----

    @Test
    public void getALCOrSWR38_parsesNumericValue() {
        // "RM6100000;" -> commandID "RM", data "6100000"; substring(1,4) = "100".
        Yaesu3Command c = new Yaesu3Command("RM", "6100000");
        assertThat(Yaesu3Command.getALCOrSWR38(c)).isEqualTo(100);
    }

    @Test
    public void getALCOrSWR38_returnsZeroOnNonNumericData() {
        Yaesu3Command c = new Yaesu3Command("RM", "6xy0000");
        assertThat(Yaesu3Command.getALCOrSWR38(c)).isEqualTo(0);
    }

    @Test
    public void getALCOrSWR38_returnsZeroOnShortData() {
        Yaesu3Command c = new Yaesu3Command("RM", "61");
        assertThat(Yaesu3Command.getALCOrSWR38(c)).isEqualTo(0);
    }

    // ---- Yaesu3Command.getSWROrALC39 (FT-891 family, data length >= 4) ----

    @Test
    public void getSWROrALC39_parsesNumericValue() {
        Yaesu3Command c = new Yaesu3Command("RM", "6100");
        assertThat(Yaesu3Command.getSWROrALC39(c)).isEqualTo(100);
    }

    @Test
    public void getSWROrALC39_returnsZeroOnNonNumericData() {
        Yaesu3Command c = new Yaesu3Command("RM", "6xyz");
        assertThat(Yaesu3Command.getSWROrALC39(c)).isEqualTo(0);
    }

    @Test
    public void getSWROrALC39_returnsZeroOnShortData() {
        Yaesu3Command c = new Yaesu3Command("RM", "61");
        assertThat(Yaesu3Command.getSWROrALC39(c)).isEqualTo(0);
    }

    // ---- Yaesu3Command.get590ALCOrSWR (Kenwood TS-590/2000/570, substring(1,5)) ----

    @Test
    public void get590ALCOrSWR_parsesNumericValue() {
        // data "01300" -> substring(1,5) = "1300".
        Yaesu3Command c = new Yaesu3Command("RM", "01300");
        assertThat(Yaesu3Command.get590ALCOrSWR(c)).isEqualTo(1300);
    }

    @Test
    public void get590ALCOrSWR_returnsZeroOnNonNumericData() {
        Yaesu3Command c = new Yaesu3Command("RM", "0abcd");
        assertThat(Yaesu3Command.get590ALCOrSWR(c)).isEqualTo(0);
    }

    @Test
    public void get590ALCOrSWR_returnsZeroOnShortData() {
        // Shorter than 5 chars: substring(1,5) would previously throw
        // StringIndexOutOfBoundsException.
        Yaesu3Command c = new Yaesu3Command("RM", "013");
        assertThat(Yaesu3Command.get590ALCOrSWR(c)).isEqualTo(0);
    }

    // ---- ElecraftCommand.getSWRMeter (substring(0,3)) ----

    @Test
    public void elecraftGetSWRMeter_parsesNumericValue() {
        ElecraftCommand c = new ElecraftCommand("SW", "012");
        assertThat(ElecraftCommand.getSWRMeter(c)).isEqualTo(12);
    }

    @Test
    public void elecraftGetSWRMeter_returnsZeroOnNonNumericData() {
        ElecraftCommand c = new ElecraftCommand("SW", "abc");
        assertThat(ElecraftCommand.getSWRMeter(c)).isEqualTo(0);
    }

    @Test
    public void elecraftGetSWRMeter_returnsZeroOnShortData() {
        ElecraftCommand c = new ElecraftCommand("SW", "ab");
        assertThat(ElecraftCommand.getSWRMeter(c)).isEqualTo(0);
    }
}
