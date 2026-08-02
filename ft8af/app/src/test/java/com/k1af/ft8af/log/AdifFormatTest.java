package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link AdifFormat}: hash-marker angle brackets must be stripped from the
 * ADIF CALL field, with the declared length matching the bare value (ARRL LoTW rejects
 * bracketed callsigns). Pure JUnit.
 */
public class AdifFormatTest {

    @Test
    public void sanitize_stripsBracketsFromResolvedHashCall() {
        assertThat(AdifFormat.sanitizeCallsign("<DK4RH>")).isEqualTo("DK4RH");
        assertThat(AdifFormat.sanitizeCallsign("DK4RH")).isEqualTo("DK4RH");
    }

    @Test
    public void sanitize_handlesNullAndBlank() {
        assertThat(AdifFormat.sanitizeCallsign(null)).isEqualTo("");
        assertThat(AdifFormat.sanitizeCallsign("  ")).isEqualTo("");
    }

    @Test
    public void callField_usesBareCallAndCorrectLength() {
        // The bug: "<call:7><DK4RH>" — brackets in the value, length 7 not 5. Fixed:
        assertThat(AdifFormat.callField("<DK4RH>")).isEqualTo("<call:5>DK4RH ");
        assertThat(AdifFormat.callField("K1ABC")).isEqualTo("<call:5>K1ABC ");
    }

    @Test
    public void callField_nullBecomesEmptyField() {
        assertThat(AdifFormat.callField(null)).isEqualTo("<call:0> ");
    }

    @Test
    public void callField_lengthAlwaysMatchesValue() {
        for (String c : new String[] { "<DK4RH>", "W1AW", "<VP2E/W1ABC>", "", null }) {
            String field = AdifFormat.callField(c);
            // Parse "<call:LEN>VALUE " and assert LEN == VALUE.length().
            int gt = field.indexOf('>');
            int len = Integer.parseInt(field.substring("<call:".length(), gt));
            String value = field.substring(gt + 1).trim();
            assertThat(value.length()).isEqualTo(len);
            assertThat(value).doesNotContain("<");
            assertThat(value).doesNotContain(">");
        }
    }

    @Test
    public void mfskSubmode_ft4AndFt2AreMfskSubmodes() {
        // The bug: exporting a bare <mode>FT2 — pota.app rejects it. FT4/FT2 are
        // ADIF submodes of MFSK, so the caller must emit MODE=MFSK + SUBMODE.
        assertThat(AdifFormat.mfskSubmode("FT2")).isEqualTo("FT2");
        assertThat(AdifFormat.mfskSubmode("FT4")).isEqualTo("FT4");
    }

    @Test
    public void mfskSubmode_standaloneModesReturnNull() {
        // FT8 is a first-class ADIF MODE; these pass through verbatim (null result).
        assertThat(AdifFormat.mfskSubmode("FT8")).isNull();
        assertThat(AdifFormat.mfskSubmode("SSB")).isNull();
        assertThat(AdifFormat.mfskSubmode("CW")).isNull();
    }

    @Test
    public void mfskSubmode_isCaseInsensitiveAndTrimmedAndUpperCased() {
        assertThat(AdifFormat.mfskSubmode(" ft2 ")).isEqualTo("FT2");
        assertThat(AdifFormat.mfskSubmode("Ft4")).isEqualTo("FT4");
    }

    @Test
    public void mfskSubmode_nullReturnsNull() {
        assertThat(AdifFormat.mfskSubmode(null)).isNull();
    }

    // ---- resolveImportMode: the reader-side inverse of mfskSubmode ----

    @Test
    public void resolveImportMode_mfskSubmodeBecomesTheSubmode() {
        // The bug: an FT4/FT2 QSO is written as MODE=MFSK + SUBMODE=FT4; reading only
        // MODE stored "MFSK", losing FT4/FT2 and breaking export->import round-trip.
        assertThat(AdifFormat.resolveImportMode("MFSK", "FT4")).isEqualTo("FT4");
        assertThat(AdifFormat.resolveImportMode("MFSK", "FT2")).isEqualTo("FT2");
    }

    @Test
    public void resolveImportMode_isCaseInsensitiveAndTrimmedAndUpperCased() {
        assertThat(AdifFormat.resolveImportMode(" mfsk ", " ft4 ")).isEqualTo("FT4");
        assertThat(AdifFormat.resolveImportMode("Mfsk", "Ft2")).isEqualTo("FT2");
    }

    @Test
    public void resolveImportMode_mfskWithoutSubmodeStaysMfsk() {
        // Plain MFSK (no SUBMODE) is a real mode on its own; leave it untouched.
        assertThat(AdifFormat.resolveImportMode("MFSK", null)).isEqualTo("MFSK");
        assertThat(AdifFormat.resolveImportMode("MFSK", "")).isEqualTo("MFSK");
        assertThat(AdifFormat.resolveImportMode("MFSK", "   ")).isEqualTo("MFSK");
    }

    @Test
    public void resolveImportMode_standaloneModesPassThroughVerbatim() {
        // Non-MFSK modes are returned exactly as stored (no case change, no trim) so
        // existing imports are byte-for-byte unchanged, even with a stray SUBMODE.
        assertThat(AdifFormat.resolveImportMode("FT8", null)).isEqualTo("FT8");
        assertThat(AdifFormat.resolveImportMode("FT4", null)).isEqualTo("FT4"); // bare FT4
        assertThat(AdifFormat.resolveImportMode("SSB", "USB")).isEqualTo("SSB");
        assertThat(AdifFormat.resolveImportMode("CW", null)).isEqualTo("CW");
    }

    @Test
    public void resolveImportMode_nullModeReturnedVerbatim() {
        assertThat(AdifFormat.resolveImportMode(null, "FT4")).isNull();
    }

    @Test
    public void resolveImportMode_isTheInverseOfMfskSubmode() {
        // Every mode that exports as MODE=MFSK + SUBMODE must import back to itself.
        for (String mode : new String[] { "FT4", "FT2" }) {
            String submode = AdifFormat.mfskSubmode(mode);
            assertThat(submode).isNotNull();
            assertThat(AdifFormat.resolveImportMode("MFSK", submode)).isEqualTo(mode);
        }
    }

    @Test
    public void formatReport_alwaysSignedAndTwoDigits() {
        // The bug: bare String.valueOf(int) gave "5"/"-5"/"0" — no sign on positives, no padding.
        assertThat(AdifFormat.formatReport(5)).isEqualTo("+05");
        assertThat(AdifFormat.formatReport(-3)).isEqualTo("-03");
        assertThat(AdifFormat.formatReport(0)).isEqualTo("+00");
        assertThat(AdifFormat.formatReport(20)).isEqualTo("+20");
        assertThat(AdifFormat.formatReport(-12)).isEqualTo("-12");
        assertThat(AdifFormat.formatReport(30)).isEqualTo("+30");
        assertThat(AdifFormat.formatReport(-30)).isEqualTo("-30");
    }

    @Test
    public void formatReport_everyValueHasSignAndAtLeastTwoDigits() {
        for (int n = -30; n <= 30; n++) {
            String s = AdifFormat.formatReport(n);
            assertThat(s.charAt(0)).isAnyOf('+', '-');
            // At least two digits after the sign.
            assertThat(s.substring(1).length()).isAtLeast(2);
            assertThat(Integer.parseInt(s)).isEqualTo(n);
        }
    }

    @Test
    public void formatReport_leavesNoReportSentinelsUnchanged() {
        // -100/-120 mean "no report" and the logbook compares against those exact strings.
        assertThat(AdifFormat.formatReport(-100)).isEqualTo("-100");
        assertThat(AdifFormat.formatReport(-120)).isEqualTo("-120");
    }

    @Test
    public void utf8Length_asciiEqualsCharCount() {
        // Pure ASCII: byte length == char length, so existing records are byte-identical.
        assertThat(AdifFormat.utf8Length("")).isEqualTo(0);
        assertThat(AdifFormat.utf8Length("K1ABC")).isEqualTo(5);
        assertThat(AdifFormat.utf8Length("Distance: 99 km, QSO by FT8AF")).isEqualTo(29);
    }

    @Test
    public void utf8Length_countsBytesNotChars() {
        // The bug: an ADIF <field:len> declared String.length() (chars). Non-ASCII
        // content has more UTF-8 bytes than chars, so the declared length undercounted
        // and the receiving logger mis-parsed the record.
        assertThat("Café".length()).isEqualTo(4);        // é is one UTF-16 char
        assertThat(AdifFormat.utf8Length("Café")).isEqualTo(5); // but two UTF-8 bytes

        assertThat("naïve".length()).isEqualTo(5);
        assertThat(AdifFormat.utf8Length("naïve")).isEqualTo(6);

        // Three CJK ideographs: 3 chars, 9 bytes (3 bytes each).
        assertThat("中文注".length()).isEqualTo(3);
        assertThat(AdifFormat.utf8Length("中文注")).isEqualTo(9);
    }

    @Test
    public void utf8Length_nullIsZero() {
        assertThat(AdifFormat.utf8Length(null)).isEqualTo(0);
    }

    // ---- sliceByUtf8Length: the reader-side counterpart ----

    @Test
    public void sliceByUtf8Length_asciiBehavesLikeSubstring() {
        assertThat(AdifFormat.sliceByUtf8Length("K1ABC extra", 5)).isEqualTo("K1ABC");
    }

    @Test
    public void sliceByUtf8Length_countsBytesNotChars() {
        // "Café QSO" is 8 chars but 9 UTF-8 bytes; a correct writer declares LEN=9.
        // Slicing 9 CHARS from "Café QSO <eor..." would keep the trailing space.
        assertThat(AdifFormat.sliceByUtf8Length("Café QSO ", 9)).isEqualTo("Café QSO");
    }

    @Test
    public void sliceByUtf8Length_neverSplitsACodePoint() {
        // LEN=5 covers "café" exactly (5 bytes); LEN=4 would end mid-'é' and must
        // keep only the complete chars that fit.
        assertThat(AdifFormat.sliceByUtf8Length("caféX", 5)).isEqualTo("café");
        assertThat(AdifFormat.sliceByUtf8Length("caféX", 4)).isEqualTo("caf");
    }

    @Test
    public void sliceByUtf8Length_clampsToAvailableText() {
        assertThat(AdifFormat.sliceByUtf8Length("FN31", 10)).isEqualTo("FN31");
        assertThat(AdifFormat.sliceByUtf8Length("", 3)).isEqualTo("");
        assertThat(AdifFormat.sliceByUtf8Length(null, 3)).isEqualTo("");
        assertThat(AdifFormat.sliceByUtf8Length("abc", 0)).isEqualTo("");
    }

    @Test
    public void sliceByUtf8Length_roundTripsUtf8LengthForAstralChars() {
        // 4-byte (astral, surrogate-pair) chars: slicing by the declared utf8Length
        // must return the whole value.
        String emoji = "hi\uD83D\uDE00";  // "hi" + U+1F600
        assertThat(AdifFormat.sliceByUtf8Length(emoji, AdifFormat.utf8Length(emoji)))
                .isEqualTo(emoji);
    }

    // ---- stripHeader: the ADIF header/record split (optional-header handling) ----

    @Test
    public void stripHeader_headedFile_returnsBodyAfterEoh() {
        // A header (does not begin with '<') is terminated by <eoh>; records follow it.
        String body = AdifFormat.stripHeader(
                "Generated by FT8AF<adif_ver:5>3.1.0<eoh><call:5>K1ABC<eor>");
        assertThat(body).isEqualTo("<call:5>K1ABC<eor>");
        assertThat(body).doesNotContain("adif_ver");
    }

    @Test
    public void stripHeader_headerlessFile_returnsWholeContent() {
        // Regression: the file begins with '<' so per the ADI spec it has NO header and is
        // entirely records. The old importers split on <eoh>, found none, and returned "" \u2014
        // dropping every QSO. The whole content must survive.
        String content = "<call:5>K1ABC<gridsquare:4>FN42<eor>\n<call:4>W1AW<eor>";
        assertThat(AdifFormat.stripHeader(content)).isEqualTo(content);
    }

    @Test
    public void stripHeader_headerlessFile_skipsLeadingWhitespaceAndBom() {
        // A leading BOM or newline before the first field must not be mistaken for a header.
        String content = "<call:5>K1ABC<eor>";
        assertThat(AdifFormat.stripHeader("\n  " + content)).isEqualTo("\n  " + content);
        assertThat(AdifFormat.stripHeader("\uFEFF" + content)).isEqualTo("\uFEFF" + content);
    }

    @Test
    public void stripHeader_eohMarkerIsCaseInsensitive() {
        assertThat(AdifFormat.stripHeader("hdr<EOH><call:5>K1ABC<eor>"))
                .isEqualTo("<call:5>K1ABC<eor>");
        assertThat(AdifFormat.stripHeader("hdr<Eoh><call:5>K1ABC<eor>"))
                .isEqualTo("<call:5>K1ABC<eor>");
    }

    @Test
    public void stripHeader_splitsAtFirstEohOnly() {
        // Only the first <eoh> terminates the header; a later literal one (however unlikely)
        // stays in the body rather than truncating it.
        assertThat(AdifFormat.stripHeader("hdr<eoh>a<eoh>b"))
                .isEqualTo("a<eoh>b");
    }

    @Test
    public void stripHeader_headerPresentButNoEoh_returnsEmpty() {
        // Header present (does not begin with '<') but unterminated: no parseable body.
        assertThat(AdifFormat.stripHeader("header only, no eoh marker")).isEmpty();
    }

    @Test
    public void stripHeader_nullOrEmpty_returnsEmpty() {
        assertThat(AdifFormat.stripHeader(null)).isEmpty();
        assertThat(AdifFormat.stripHeader("")).isEmpty();
        assertThat(AdifFormat.stripHeader("   ")).isEmpty();
    }
}
