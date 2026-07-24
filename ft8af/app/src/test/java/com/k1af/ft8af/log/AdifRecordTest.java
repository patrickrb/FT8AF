package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit tests for {@link AdifRecord}, the shared ADIF record emitter used by both the bulk
 * {@link ShareLogs} export and the incremental {@link AdifLogFile} append. Pure JUnit.
 *
 * <p>The central regression guard is {@link #everyDeclaredLengthMatchesUtf8Bytes}: every
 * {@code <field:len>value } must declare the value's UTF-8 byte length, or ADIF consumers
 * (LoTW, LOG4OM) mis-parse the record.
 */
public class AdifRecordTest {

    /** Matches an ADIF field: name, declared length, then value up to the trailing space. */
    private static final Pattern FIELD =
            Pattern.compile("<([A-Za-z_]+):(\\d+)>([^<]*?) (?=<|$)");

    @Test
    public void oneQso_producesExactlyOneEorRecord() {
        String out = new AdifRecord()
                .call("W1AW")
                .gridsquare("FN31")
                .mode("FT8")
                .qsoDate("20231114")
                .timeOn("221320")
                .comment("QSO by FT8AF")
                .build();

        assertThat(out).endsWith("<eor>\n");
        assertThat(count(out, "<eor>")).isEqualTo(1);
        assertThat(out).contains("<call:4>W1AW ");
        assertThat(out).contains("<gridsquare:4>FN31 ");
        assertThat(out).contains("<mode:3>FT8 ");
        assertThat(out).contains("<comment:12>QSO by FT8AF <eor>\n");
    }

    @Test
    public void nonSwl_emitsQslRcvdAndManualPair() {
        String out = new AdifRecord().call("W1AW").lotwQsl(true).manualQsl(false).build();
        assertThat(out).contains("<QSL_RCVD:1>Y ");
        assertThat(out).contains("<QSL_MANUAL:1>N ");
        assertThat(out).doesNotContain("<swl:");
    }

    @Test
    public void swl_emitsSwlFlagInsteadOfQslPair() {
        String out = new AdifRecord().call("W1AW").swl(true).lotwQsl(true).build();
        assertThat(out).contains("<swl:1>Y ");
        assertThat(out).doesNotContain("<QSL_RCVD:");
        assertThat(out).doesNotContain("<QSL_MANUAL:");
    }

    @Test
    public void hashedCall_isStrippedToBareCallWithCorrectLength() {
        // Regression: "<DK4RH>" must become bare DK4RH with length 5, not 7 with brackets.
        String out = new AdifRecord().call("<DK4RH>").build();
        assertThat(out).contains("<call:5>DK4RH ");
        assertThat(out).doesNotContain("<DK4RH>");
    }

    @Test
    public void ft4AndFt2_emittedAsMfskSubmode() {
        // FT4/FT2 are ADIF submodes of MFSK; a bare <mode>FT2 is rejected by pota.app.
        assertThat(new AdifRecord().call("W1AW").mode("FT4").build())
                .contains("<mode:4>MFSK <submode:3>FT4 ");
        assertThat(new AdifRecord().call("W1AW").mode("FT2").build())
                .contains("<mode:4>MFSK <submode:3>FT2 ");
        // FT8 stays a first-class MODE.
        assertThat(new AdifRecord().call("W1AW").mode("FT8").build())
                .contains("<mode:3>FT8 ");
    }

    @Test
    public void potaFields_emittedOnlyWhenPopulated() {
        String plain = new AdifRecord().call("W1AW").build();
        assertThat(plain).doesNotContain("MY_SIG");
        assertThat(plain).doesNotContain("SIG_INFO");

        String pota = new AdifRecord().call("W1AW")
                .mySig("POTA").mySigInfo("K-1234")
                .sig("POTA").sigInfo("K-5678")
                .build();
        assertThat(pota).contains("<MY_SIG:4>POTA ");
        assertThat(pota).contains("<MY_SIG_INFO:6>K-1234 ");
        assertThat(pota).contains("<SIG:4>POTA ");
        assertThat(pota).contains("<SIG_INFO:6>K-5678 ");
    }

    @Test
    public void potaFields_emptyStringIsTreatedAsAbsent() {
        String out = new AdifRecord().call("W1AW").mySig("").mySigInfo(null).build();
        assertThat(out).doesNotContain("MY_SIG");
    }

    @Test
    public void nullFieldsAreOmittedButEmptyStringsAreEmitted() {
        // gridsquare == null -> omitted entirely.
        assertThat(new AdifRecord().call("W1AW").gridsquare(null).build())
                .doesNotContain("gridsquare");
        // gridsquare == "" -> still emitted as a zero-length field (historical behaviour).
        assertThat(new AdifRecord().call("W1AW").gridsquare("").build())
                .contains("<gridsquare:0> ");
    }

    @Test
    public void myLatLon_emittedWhenSet() {
        String out = new AdifRecord().call("W1AW")
                .myLat(AdifFormat.formatLat(40.858333))
                .myLon(AdifFormat.formatLon(-73.925))
                .build();
        assertThat(out).contains("<my_lat:11>N040 51.500 ");
        assertThat(out).contains("<my_lon:11>W073 55.500 ");
    }

    @Test
    public void myLatLon_omittedWhenLocationUnavailable() {
        String out = new AdifRecord().call("W1AW").build();
        assertThat(out).doesNotContain("my_lat");
        assertThat(out).doesNotContain("my_lon");
    }

    @Test
    public void comment_isAlwaysEmittedEvenWhenNull() {
        assertThat(new AdifRecord().call("W1AW").comment(null).build())
                .contains("<comment:0> <eor>\n");
    }

    @Test
    public void everyDeclaredLengthMatchesUtf8Bytes() {
        // Include a non-ASCII comment: "Ω" is 2 UTF-8 bytes but 1 char. The declared length
        // must be the byte count, or the following field misaligns.
        String out = new AdifRecord()
                .call("<DK4RH>")
                .gridsquare("IO91wm")
                .mode("FT4")
                .rstSent("+05")
                .rstRcvd("-03")
                .qsoDate("20231114")
                .timeOn("221320")
                .band("20m")
                .freq("14.074000")
                .stationCallsign("K1ABC")
                .myGridsquare("FN31pr")
                .mySig("POTA")
                .mySigInfo("K-1234")
                .comment("dist 5Ω km")
                .build();

        Matcher m = FIELD.matcher(out);
        int fields = 0;
        while (m.find()) {
            String name = m.group(1);
            int declared = Integer.parseInt(m.group(2));
            String value = m.group(3);
            int actual = value.getBytes(StandardCharsets.UTF_8).length;
            assertThat(actual).isEqualTo(declared);
            fields++;
        }
        assertThat(fields).isAtLeast(10);
        // The comment's Ω must be byte-counted (10 bytes for "dist 5Ω km"), not char-counted (9).
        assertThat(out).contains("<comment:11>dist 5Ω km <eor>\n");
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
