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
}
