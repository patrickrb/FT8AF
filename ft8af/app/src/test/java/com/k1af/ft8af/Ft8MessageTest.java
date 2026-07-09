package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
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
        assertThat(msg.snr).isEqualTo(Ft8Message.SNR_UNKNOWN);
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
    public void getMessageText_wwrof_withTuAndRFlagPositiveReport() {
        // i3=5 is a WWROF contest message: "[TU; ]<to> <from> [R]<+/-><rpt> <grid4>".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 5;
        msg.rtty_tu = 1;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 1;
        msg.report = 12;
        msg.maidenGrid = "FN20";
        assertThat(msg.getMessageText()).isEqualTo("TU; K1ABC W9XYZ R+12 FN20");
    }

    @Test
    public void getMessageText_wwrof_negativeReportSingleSign() {
        // No TU/R prefix; a negative report renders a single leading minus (no '+').
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 5;
        msg.rtty_tu = 0;
        msg.callsignTo = "K1ABC";
        msg.callsignFrom = "W9XYZ";
        msg.r_flag = 0;
        msg.report = -7;
        msg.maidenGrid = "FN20";
        assertThat(msg.getMessageText()).isEqualTo("K1ABC W9XYZ -7 FN20");
    }

    @Test
    public void getMessageText_dxpeditionCombo_negativeReportSingleSign() {
        // i3=0, n3=1 DXpedition combo: "<acked> RR73; <invited> <foxHash> <rpt>".
        // An unseeded Fox hash resolves to "<...>"; a negative report must render a
        // single minus (regression: the magnitude is formatted after the sign char).
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 0;
        msg.n3 = 1;
        msg.callsignTo = "K1ABC";
        msg.dx_call_to2 = "W9XYZ";
        msg.callFromHash10 = 0;
        msg.report = -18;
        assertThat(msg.getMessageText()).isEqualTo("K1ABC RR73; W9XYZ <...> -18");
    }

    @Test
    public void getMessageText_dxpeditionCombo_zeroReportIsPlusZero() {
        // A zero report formats as "+0" (>= 0), matching WSJT-X, not "-0".
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.i3 = 0;
        msg.n3 = 1;
        msg.callsignTo = "K1ABC";
        msg.dx_call_to2 = "W9XYZ";
        msg.callFromHash10 = 0;
        msg.report = 0;
        assertThat(msg.getMessageText()).isEqualTo("K1ABC RR73; W9XYZ <...> +0");
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

    @Test
    public void getCallsignTo_twoCharToken_doesNotCrash() {
        // Regression: the calling-token check ran substring(0, 3) (for "QRZ")
        // after only a length<2 guard, so a callsignTo of exactly length 2 that
        // is neither "CQ" nor "DE" threw StringIndexOutOfBoundsException. The
        // native decoder can render such a short junk token into callsignTo on a
        // CRC-collision false decode, and getCallsignTo() runs on essentially
        // every decoded message, so this crashed the decode-handling path.
        Ft8Message msg = new Ft8Message("W1", "K1ABC", "FN42");
        assertThat(msg.getCallsignTo()).isEqualTo("W1");
    }

    @Test
    public void getCallsignTo_singleCharToken_doesNotCrash() {
        // A length-1 field must also be handled without an index exception.
        Ft8Message msg = new Ft8Message("A", "K1ABC", "FN42");
        assertThat(msg.getCallsignTo()).isEqualTo("A");
    }

    // ---- isPlausibleCallsign / isJunkDecode ---------------------------------

    @Test
    public void isPlausibleCallsign_acceptsRealCallsigns() {
        assertThat(Ft8Message.isPlausibleCallsign("K1ABC")).isTrue();
        assertThat(Ft8Message.isPlausibleCallsign("PJ4/K1ABC")).isTrue();  // compound
        assertThat(Ft8Message.isPlausibleCallsign("3DA0RS")).isTrue();     // leading digit
        assertThat(Ft8Message.isPlausibleCallsign("<W9XYZ>")).isTrue();    // hashed wrapper
        assertThat(Ft8Message.isPlausibleCallsign("<...>")).isTrue();      // unresolved placeholder
        assertThat(Ft8Message.isPlausibleCallsign(" K1ABC ")).isTrue();    // trimmed
    }

    @Test
    public void isPlausibleCallsign_rejectsGarbageAndEmpty() {
        // The reported false decode: stray brackets, dots and a space.
        assertThat(Ft8Message.isPlausibleCallsign(".<. >")).isFalse();
        assertThat(Ft8Message.isPlausibleCallsign("<. .>")).isFalse();
        assertThat(Ft8Message.isPlausibleCallsign("A B")).isFalse();      // embedded space
        assertThat(Ft8Message.isPlausibleCallsign("")).isFalse();
        assertThat(Ft8Message.isPlausibleCallsign("   ")).isFalse();
        assertThat(Ft8Message.isPlausibleCallsign(null)).isFalse();
    }

    @Test
    public void isJunkDecode_structuredMessageWithGarbageSender_isJunk() {
        Ft8Message msg = new Ft8Message("CQ", ".<. >", "FN42");
        msg.i3 = 1; // standard message — sender must be a real callsign
        assertThat(msg.isJunkDecode()).isTrue();
    }

    @Test
    public void isJunkDecode_structuredMessageWithRealSender_isNotJunk() {
        Ft8Message msg = new Ft8Message("CQ", "K1ABC", "FN42");
        msg.i3 = 1;
        assertThat(msg.isJunkDecode()).isFalse();
    }

    @Test
    public void isJunkDecode_freeTextAndTelemetry_neverJunk() {
        // i3=0,n3=0 (free text) and i3=0,n3=5 (telemetry) legitimately carry
        // non-callsign text in the callsign fields, so they are exempt even when
        // the sender field is not a valid callsign.
        Ft8Message freeText = new Ft8Message(FT8Common.FT8_MODE);
        freeText.i3 = 0;
        freeText.n3 = 0;
        freeText.callsignFrom = "TNX 73 GL";
        assertThat(freeText.isJunkDecode()).isFalse();

        Ft8Message telemetry = new Ft8Message(FT8Common.FT8_MODE);
        telemetry.i3 = 0;
        telemetry.n3 = 5;
        telemetry.callsignFrom = "123456789ABCDEF012";
        assertThat(telemetry.isJunkDecode()).isFalse();
    }

    // ---- copy-constructor hash resolution (#392) ----------------------------

    @Before
    public void clearHashList() {
        // hashList is static; clear it so the resolution tests below are
        // order-independent and never match a hash seeded by another test.
        Ft8Message.hashList.clear();
    }

    @Test
    public void copyConstructor_resolvesHashedCompoundCallFromSeededHashList() {
        // Issue #392: a station answering a compound call (SV8/DM5HF) sends a
        // standard frame in which that call is carried only as a 22-bit hash.
        // The decoder can't resolve it and returns "<...>", but the JNI now
        // preserves the raw hash in callToHash22 (ft8_call_hash.h) so the copy
        // constructor can resolve it against the hash list the app seeds with
        // the operator's own call. i3=1 keeps this off the native getHashNN
        // path, which is exercised host-side in test_call_hash.c.
        long h22 = 0x2ABCDEL; // stand-in for FT8Package.getHash22("SV8/DM5HF")
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF");

        Ft8Message src = new Ft8Message(FT8Common.FT8_MODE);
        src.i3 = 1; // standard frame
        src.callsignTo = "<...>";
        src.callsignFrom = "K1ABC";
        src.extraInfo = "JN35";
        src.callToHash22 = h22;

        Ft8Message copy = new Ft8Message(src);
        assertThat(copy.callsignTo).isEqualTo("<SV8/DM5HF>");
        assertThat(copy.callsignFrom).isEqualTo("K1ABC");
    }

    @Test
    public void copyConstructor_leavesUnknownHashAsPlaceholder() {
        // A hash the list has never seen stays "<...>" — no false resolution.
        Ft8Message src = new Ft8Message(FT8Common.FT8_MODE);
        src.i3 = 1;
        src.callsignTo = "<...>";
        src.callsignFrom = "K1ABC";
        src.extraInfo = "JN35";
        src.callToHash22 = 0x155555L; // not seeded

        Ft8Message copy = new Ft8Message(src);
        assertThat(copy.callsignTo).isEqualTo("<...>");
    }

    @Test
    public void copyConstructor_resolvesFromH12_whenOnlyH12Received() {
        // Over the air this is the Type-4 (i3=4) case: "<SV8/DM5HF> K1ABC RR73"
        // carries only the 12-bit hash, and the JNI leaves the other hash
        // fields 0 (zero keys are never stored in the list, so they can't
        // match anything). The message here uses i3=1 because the copy
        // constructor's i3==4 branch calls the native FT8Package.getHash*
        // helpers, which aren't loadable in a JVM test — the resolution path
        // under test is identical for both frame types.
        long h22 = 0x2ABCDEL;
        long h12 = h22 >> 10;
        Ft8Message.hashList.addHash(h12, "SV8/DM5HF");

        Ft8Message src = new Ft8Message(FT8Common.FT8_MODE);
        src.i3 = 1; // keep off the native getHashNN path (see comment above)
        src.callsignTo = "<...>";
        src.callsignFrom = "K1ABC";
        src.extraInfo = "RR73";
        src.callToHash12 = h12;

        Ft8Message copy = new Ft8Message(src);
        assertThat(copy.callsignTo).isEqualTo("<SV8/DM5HF>");
    }

    @Test
    public void copyConstructor_prefersWidestHash_overColliding10BitEntry() {
        // A 10-bit hash has only 1024 buckets, so an unrelated callsign can
        // legitimately occupy this message's h10 value. Resolution must check
        // the widest hash first (h22 -> h12 -> h10) so the 22-bit match wins.
        long h22 = 0x2ABCDEL;
        Ft8Message.hashList.addHash(h22 >> 12, "K1COLLIDE"); // unrelated call at our h10
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF");

        Ft8Message src = new Ft8Message(FT8Common.FT8_MODE);
        src.i3 = 1;
        src.callsignFrom = "<...>";
        src.callsignTo = "K1ABC";
        src.callFromHash22 = h22;
        src.callFromHash12 = h22 >> 10;
        src.callFromHash10 = h22 >> 12;

        Ft8Message copy = new Ft8Message(src);
        assertThat(copy.callsignFrom).isEqualTo("<SV8/DM5HF>");
    }

    @Test
    public void resolvedCompoundCall_isRecognizedAsMyCallsign() {
        // The full issue-#392 chain: once the reply resolves, the app must
        // recognize it as addressed to the operator so a QSO can proceed.
        // myCallsign is a global static — restore it so no state leaks into
        // other tests.
        String previousMyCallsign = GeneralVariables.myCallsign;
        try {
            GeneralVariables.myCallsign = "SV8/DM5HF";
            long h22 = 0x2ABCDEL;
            Ft8Message.hashList.addHash(h22, "SV8/DM5HF");

            Ft8Message src = new Ft8Message(FT8Common.FT8_MODE);
            src.i3 = 1;
            src.callsignTo = "<...>";
            src.callsignFrom = "K1ABC";
            src.extraInfo = "R-10";
            src.callToHash22 = h22;

            Ft8Message copy = new Ft8Message(src);
            assertThat(GeneralVariables.checkIsMyCallsign(copy.getCallsignTo())).isTrue();
        } finally {
            GeneralVariables.myCallsign = previousMyCallsign;
        }
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

    @Test
    public void getSequence_ft4_alternatesEachSevenAndHalfSeconds() {
        // Unchanged FT4 path: 7.5s slot.
        Ft8Message msg = new Ft8Message(FT8Common.FT4_MODE);
        msg.utcTime = 0;
        assertThat(msg.getSequence()).isEqualTo(0);
        msg.utcTime = 7500;
        assertThat(msg.getSequence()).isEqualTo(1);
        msg.utcTime = 15000;
        assertThat(msg.getSequence()).isEqualTo(0);
    }

    @Test
    public void getSequence_ft2_alternatesEachShortSlot() {
        // FT2's 3.75s slot — must alternate twice as fast as FT4's 7.5s.
        Ft8Message msg = new Ft8Message(FT8Common.FT2_MODE);
        msg.utcTime = 0;
        assertThat(msg.getSequence()).isEqualTo(0);
        msg.utcTime = 3750;
        assertThat(msg.getSequence()).isEqualTo(1);
        msg.utcTime = 7500;
        assertThat(msg.getSequence()).isEqualTo(0);
        msg.utcTime = 11250;
        assertThat(msg.getSequence()).isEqualTo(1);
    }

    @Test
    public void getSequence_ft2_adjacentSlotsHaveDistinctParity() {
        // Regression for #205: the old code reused FT4's 7.5s period for FT2, so
        // two adjacent 3.75s slots (0ms and 3750ms) both mapped to sequence 0.
        // The QSO sequencer then saw the other station's reply as being in our
        // own slot and skipped it, stalling on the report instead of sending RR73.
        Ft8Message slotA = new Ft8Message(FT8Common.FT2_MODE);
        slotA.utcTime = 0;
        Ft8Message slotB = new Ft8Message(FT8Common.FT2_MODE);
        slotB.utcTime = 3750;
        assertThat(slotA.getSequence()).isNotEqualTo(slotB.getSequence());
    }

    @Test
    public void getSequence_ft2_toleratesSlightEarlySkew() {
        // utcTime is slot-aligned; the +slot/20 nudge keeps a timestamp a touch
        // early (clock skew) in the correct slot rather than the previous one.
        Ft8Message msg = new Ft8Message(FT8Common.FT2_MODE);
        msg.utcTime = 3750 - 50; // 50ms before slot 1's boundary
        assertThat(msg.getSequence()).isEqualTo(1);
    }

    @Test
    public void getSequence4_ft2_cyclesModuloFourOnShortSlot() {
        Ft8Message msg = new Ft8Message(FT8Common.FT2_MODE);
        msg.utcTime = 0;
        assertThat(msg.getSequence4()).isEqualTo(0);
        msg.utcTime = 3750;
        assertThat(msg.getSequence4()).isEqualTo(1);
        msg.utcTime = 7500;
        assertThat(msg.getSequence4()).isEqualTo(2);
        msg.utcTime = 11250;
        assertThat(msg.getSequence4()).isEqualTo(3);
        msg.utcTime = 15000;
        assertThat(msg.getSequence4()).isEqualTo(0);
    }

    // ---- TransmitCallsign factory methods -----------------------------------

    @Test
    public void getFromCallTransmitCallsign_carriesSenderFieldsAndSnr() {
        Ft8Message msg = new Ft8Message("CQ", "K1ABC", "FN42");
        msg.utcTime = 0; // getSequence() -> 0
        msg.snr = 12;
        msg.freq_hz = 1500f;
        com.k1af.ft8af.ft8transmit.TransmitCallsign tc = msg.getFromCallTransmitCallsign();
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
        com.k1af.ft8af.ft8transmit.TransmitCallsign tc = msg.getToCallTransmitCallsign();
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
        com.k1af.ft8af.ft8transmit.TransmitCallsign tc = msg.getToCallTransmitCallsign();
        assertThat(tc.snr).isEqualTo(5);
        assertThat(tc.sequential).isEqualTo(0);
    }

    // ---- SNR_UNKNOWN sentinel tests -----------------------------------------

    @Test
    public void defaultSnr_isUnknownSentinel() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        assertThat(msg.snr).isEqualTo(Ft8Message.SNR_UNKNOWN);
        assertThat(msg.hasSnr()).isFalse();
    }

    @Test
    public void hasSnr_trueForZero() {
        // 0 dB is a legitimate SNR value — not the sentinel.
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.snr = 0;
        assertThat(msg.hasSnr()).isTrue();
    }

    @Test
    public void hasSnr_trueForNegative() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.snr = -24;
        assertThat(msg.hasSnr()).isTrue();
    }

    @Test
    public void hasSnr_falseForSentinel() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.snr = Ft8Message.SNR_UNKNOWN;
        assertThat(msg.hasSnr()).isFalse();
    }

    @Test
    public void getdB_unknownReturnsPlaceholder() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        // snr defaults to SNR_UNKNOWN
        assertThat(msg.getdB()).isEqualTo("--");
    }

    @Test
    public void getdB_knownReturnsNumericString() {
        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.snr = -15;
        assertThat(msg.getdB()).isEqualTo("-15");
        msg.snr = 0;
        assertThat(msg.getdB()).isEqualTo("0");
    }

    @Test
    public void getFromCallTransmitCallsign_unknownSnr_fallsBackToZero() {
        Ft8Message msg = new Ft8Message("CQ", "K1ABC", "FN42");
        msg.utcTime = 0;
        // snr is SNR_UNKNOWN by default
        msg.freq_hz = 1500f;
        com.k1af.ft8af.ft8transmit.TransmitCallsign tc = msg.getFromCallTransmitCallsign();
        assertThat(tc.snr).isEqualTo(0);
    }

    @Test
    public void getToCallTransmitCallsign_unknownSnr_fallsBackToZero() {
        Ft8Message msg = new Ft8Message("K1ABC", "W9XYZ", "FN42");
        msg.utcTime = 0;
        // snr is SNR_UNKNOWN by default, report is -100 (no report) -> falls back to SNR
        msg.freq_hz = 1500f;
        com.k1af.ft8af.ft8transmit.TransmitCallsign tc = msg.getToCallTransmitCallsign();
        assertThat(tc.snr).isEqualTo(0);
    }
}
