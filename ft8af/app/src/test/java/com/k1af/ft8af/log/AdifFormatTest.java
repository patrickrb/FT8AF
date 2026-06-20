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
}
