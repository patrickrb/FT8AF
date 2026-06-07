package com.bg7yoz.ft8cn;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercise the constructors and simple formatters on {@link Ft8Message}.
 * The complex copy-constructor (which touches the native libft8cn hash
 * helpers via FT8Package.getHashNN) is deliberately out of scope; the JNI
 * side belongs to a separate test layer.
 */
@RunWith(RobolectricTestRunner.class)
public class Ft8MessageTest {

    @Test
    public void singleArgConstructor_setsSignalFormat() {
        Ft8Message msg = new Ft8Message(FT8Common.FT4_MODE);
        assertThat(msg.signalFormat).isEqualTo(FT8Common.FT4_MODE);
    }

    @Test
    public void threeArgConstructor_uppercasesAllStrings() {
        Ft8Message msg = new Ft8Message("cq", "k1abc", "fn42");
        assertThat(msg.callsignTo).isEqualTo("CQ");
        assertThat(msg.callsignFrom).isEqualTo("K1ABC");
        assertThat(msg.extraInfo).isEqualTo("FN42");
    }

    @Test
    public void threeArgConstructor_preservesAlreadyUppercase() {
        Ft8Message msg = new Ft8Message("CQ", "VE3XY", "EN85");
        assertThat(msg.callsignTo).isEqualTo("CQ");
        assertThat(msg.callsignFrom).isEqualTo("VE3XY");
        assertThat(msg.extraInfo).isEqualTo("EN85");
    }

    @Test
    public void defaultReport_isSentinelMinus100() {
        // -100 is the "no signal report" sentinel checked throughout the
        // decode UI; assert the field initialiser matches what callers expect.
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        assertThat(msg.report).isEqualTo(-100);
    }

    @Test
    public void getFreq_hz_formatsWithLeadingZeros() {
        // %04.0f for an audio-frequency display; rounds to integer and pads
        // to a minimum of four characters (e.g. 750 Hz -> "0750").
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.freq_hz = 750.4f;
        assertThat(msg.getFreq_hz()).isEqualTo("0750");
    }

    @Test
    public void getFreq_hz_handlesFourDigitFrequencies() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.freq_hz = 2375.0f;
        assertThat(msg.getFreq_hz()).isEqualTo("2375");
    }

    @Test
    public void defaultsForOtherFlagFields() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        assertThat(msg.isValid).isFalse();
        assertThat(msg.isQSL_Callsign).isFalse();
        assertThat(msg.isWeakSignal).isFalse();
        assertThat(msg.snr).isEqualTo(0);
        assertThat(msg.score).isEqualTo(0);
    }

    @Test
    public void checkIsCQ_trueForCallingTokens() {
        // checkIsCQ looks at the first whitespace-delimited token of callsignTo.
        assertThat(new Ft8Message("CQ", "K1ABC", "FN42").checkIsCQ()).isTrue();
        assertThat(new Ft8Message("DE", "K1ABC", "FN42").checkIsCQ()).isTrue();
        assertThat(new Ft8Message("QRZ", "K1ABC", "FN42").checkIsCQ()).isTrue();
        // "CQ DX" -> first token "CQ" still counts as a CQ.
        assertThat(new Ft8Message("CQ DX", "K1ABC", "FN42").checkIsCQ()).isTrue();
    }

    @Test
    public void checkIsCQ_falseWhenAddressedToCallsign() {
        assertThat(new Ft8Message("K1ABC", "W1AW", "FN42").checkIsCQ()).isFalse();
    }

    @Test
    public void getMessageText_freeTextPadsToThirteen() {
        // Default i3/n3 == 0 selects the free-text branch, which upper-cases and
        // left-pads the payload to 13 chars.
        Ft8Message msg = new Ft8Message("cq", "k1abc", "test");
        assertThat(msg.getMessageText()).isEqualTo("TEST         ");
    }

    @Test
    public void getMessageText_weakSignalPrefixHonoursFlag() {
        Ft8Message msg = new Ft8Message("cq", "k1abc", "test");
        msg.isWeakSignal = true;
        // showWeekSignal=true prefixes a "*"; false leaves the text untouched.
        assertThat(msg.getMessageText(true)).startsWith("*");
        assertThat(msg.getMessageText(false)).doesNotContain("*");
    }

    // ---- getMessageText: structured (non-free-text) branches ----------------

    @Test
    public void getMessageText_freeTextTruncatesToThirteen() {
        // Free text longer than 13 chars is truncated to exactly 13.
        Ft8Message msg = new Ft8Message("cq", "k1abc", "ABCDEFGHIJKLMNOP");
        assertThat(msg.getMessageText()).isEqualTo("ABCDEFGHIJKLM");
    }

    @Test
    public void getMessageText_fieldDay_noRFlag() {
        // i3=0, n3=3 selects the Field Day formatter:
        //   "%s %s %s%d%s %s" with r_flag==0 emitting no "R ".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 0;
        msg.n3 = 3;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 0;
        msg.eu_serial = 17;
        msg.arrl_class = "B";
        msg.arrl_rac = "EMA";
        assertThat(msg.getMessageText()).isEqualTo("K1ABC W9XYZ 17B EMA");
    }

    @Test
    public void getMessageText_fieldDay_withRFlag() {
        // r_flag != 0 prepends "R " before the serial.
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 0;
        msg.n3 = 4;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 1;
        msg.eu_serial = 17;
        msg.arrl_class = "B";
        msg.arrl_rac = "EMA";
        assertThat(msg.getMessageText()).isEqualTo("K1ABC W9XYZ R 17B EMA");
    }

    @Test
    public void getMessageText_rttyRu_withoutTuPrefix() {
        // i3=3 RTTY RU: "%s%s %s %s%d %s" with rtty_tu==0 emitting no "TU; ".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 3;
        msg.rtty_tu = 0;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 0;
        msg.report = 579;
        msg.rtty_state = "WI";
        assertThat(msg.getMessageText()).isEqualTo("K1ABC W9XYZ 579 WI");
    }

    @Test
    public void getMessageText_rttyRu_withTuPrefix() {
        // rtty_tu != 0 prepends "TU; ".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 3;
        msg.rtty_tu = 1;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 0;
        msg.report = 579;
        msg.rtty_state = "WI";
        assertThat(msg.getMessageText()).isEqualTo("TU; K1ABC W9XYZ 579 WI");
    }

    @Test
    public void getMessageText_euVhf_zeroPadsSerial() {
        // i3=5 EU VHF: "%s %s %s%d%04d %s" .trim(); report 57 + serial 7 -> "570007".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 5;
        msg.callsignTo = "<G4ABC>";
        msg.callsignFrom = "<PA9XYZ>";
        msg.r_flag = 1;
        msg.report = 57;
        msg.eu_serial = 7;
        msg.maidenGrid = "JO22DB";
        assertThat(msg.getMessageText()).isEqualTo("<G4ABC> <PA9XYZ> R 570007 JO22DB");
    }

    // ---- getCallsignFrom / getCallsignTo ------------------------------------

    @Test
    public void getCallsignFrom_nullReturnsEmpty() {
        assertThat(new Ft8Message(FT8Common.FT8_MODE).getCallsignFrom()).isEqualTo("");
    }

    @Test
    public void getCallsignFrom_stripsAngleBrackets() {
        Ft8Message msg = new Ft8Message("CQ", "<W9XYZ>", "");
        assertThat(msg.getCallsignFrom()).isEqualTo("W9XYZ");
    }

    @Test
    public void getCallsignTo_nullReturnsEmpty() {
        assertThat(new Ft8Message(FT8Common.FT8_MODE).getCallsignTo()).isEqualTo("");
    }

    @Test
    public void getCallsignTo_callingTokensReturnEmpty() {
        // CQ / DE / QRZ are calling tokens, not addressable callsigns.
        assertThat(new Ft8Message("CQ", "K1ABC", "FN42").getCallsignTo()).isEqualTo("");
        assertThat(new Ft8Message("DE", "K1ABC", "FN42").getCallsignTo()).isEqualTo("");
        assertThat(new Ft8Message("QRZ", "K1ABC", "FN42").getCallsignTo()).isEqualTo("");
    }

    @Test
    public void getCallsignTo_realCallsign_stripsBrackets() {
        Ft8Message msg = new Ft8Message("<K1ABC>", "W9XYZ", "FN42");
        assertThat(msg.getCallsignTo()).isEqualTo("K1ABC");
    }

    // ---- simple formatters: getDt / getdB / getFreq_hz ----------------------

    @Test
    public void getDt_formatsOneDecimal() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.time_sec = 2.34f;
        assertThat(msg.getDt()).isEqualTo("2.3");
    }

    @Test
    public void getdB_isPlainSnrString() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.snr = -15;
        assertThat(msg.getdB()).isEqualTo("-15");
    }

    // ---- sequence math ------------------------------------------------------

    @Test
    public void isEvenSequence_ft8_trueAtCycleBoundary() {
        // FT8 mode: even when (utcTime/1000) % 15 == 0.
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.utcTime = 15000;
        assertThat(msg.isEvenSequence()).isTrue();
        msg.utcTime = 30000;
        assertThat(msg.isEvenSequence()).isTrue();
        msg.utcTime = 7000;
        assertThat(msg.isEvenSequence()).isFalse();
    }

    @Test
    public void getSequence_ft8_alternatesEachFifteenSeconds() {
        // ((utcTime + 750) / 1000 / 15) % 2
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.utcTime = 0;
        assertThat(msg.getSequence()).isEqualTo(0);
        msg.utcTime = 15000;
        assertThat(msg.getSequence()).isEqualTo(1);
        msg.utcTime = 30000;
        assertThat(msg.getSequence()).isEqualTo(0);
    }

    @Test
    public void getSequence4_ft8_cyclesModuloFour() {
        // ((utcTime + 750) / 1000 / 15) % 4
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.utcTime = 45000;
        assertThat(msg.getSequence4()).isEqualTo(3);
        msg.utcTime = 60000;
        assertThat(msg.getSequence4()).isEqualTo(0);
    }

    // ---- TransmitCallsign factory methods -----------------------------------

    @Test
    public void getFromCallTransmitCallsign_carriesSenderFieldsAndSnr() {
        Ft8Message msg = new Ft8Message("CQ", "K1ABC", "FN42");
        msg.utcTime = 0; // getSequence() -> 0
        msg.snr = 12;
        msg.freq_hz = 1500f;
        com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign tc = msg.getFromCallTransmitCallsign();
        assertThat(tc.callsign).isEqualTo("K1ABC");
        assertThat(tc.snr).isEqualTo(12);
        assertThat(tc.sequential).isEqualTo(0);
    }

    @Test
    public void getToCallTransmitCallsign_usesSnrWhenNoReport_andFlipsSequence() {
        // report == -100 (default) -> falls back to snr; sequence is the opposite
        // of the sender's: (getSequence() + 1) % 2.
        Ft8Message msg = new Ft8Message("K1ABC", "W9XYZ", "FN42");
        msg.utcTime = 0; // sender getSequence() -> 0, so toCall sequence -> 1
        msg.snr = -8;
        com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign tc = msg.getToCallTransmitCallsign();
        assertThat(tc.callsign).isEqualTo("K1ABC");
        assertThat(tc.snr).isEqualTo(-8);
        assertThat(tc.sequential).isEqualTo(1);
    }

    @Test
    public void getToCallTransmitCallsign_usesReportWhenPresent() {
        // report != -100 -> the report value is carried as the snr field.
        Ft8Message msg = new Ft8Message("K1ABC", "W9XYZ", "FN42");
        msg.utcTime = 15000; // sender getSequence() -> 1, so toCall sequence -> 0
        msg.snr = -8;
        msg.report = 5;
        com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign tc = msg.getToCallTransmitCallsign();
        assertThat(tc.snr).isEqualTo(5);
        assertThat(tc.sequential).isEqualTo(0);
    }
}
