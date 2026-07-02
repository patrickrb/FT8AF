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
}
