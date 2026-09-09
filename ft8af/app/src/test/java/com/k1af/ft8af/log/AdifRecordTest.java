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
            Pattern.compile("<([A-Za-z0-9_]+):(\\d+)>([^<]*?) (?=<|$)");

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
        assertThat(out).contains("<APP_FT8AF_QSL_MANUAL:1>N ");
        assertThat(out).doesNotContain("<swl:");
    }

    @Test
    public void manualQsl_usesAppPrefixNotTheBareNonStandardName() {
        // ADIF has no QSL_MANUAL field. Emitting the bare name makes a strict importer
        // reject or warn on every record; APP_<PROGRAMID>_<FIELD> is the spec's mechanism
        // for program-specific data and is safely skippable by consumers that don't know it.
        String out = new AdifRecord().call("W1AW").manualQsl(true).build();
        assertThat(out).contains("<APP_FT8AF_QSL_MANUAL:1>Y ");
        assertThat(out).doesNotContain("<QSL_MANUAL:");
    }

    @Test
    public void swl_emitsSwlFlagInsteadOfQslPair() {
        String out = new AdifRecord().call("W1AW").swl(true).lotwQsl(true).build();
        assertThat(out).contains("<swl:1>Y ");
        assertThat(out).doesNotContain("<QSL_RCVD:");
        assertThat(out).doesNotContain("QSL_MANUAL");
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
    public void nullAndEmptyFieldsAreBothOmitted() {
        // Regression: an empty grid used to emit "<gridsquare:0> ". Several ADIF importers
        // treat a length-0 field as malformed and reject the whole record, so an absent
        // optional field is the only portable way to say "no value".
        assertThat(new AdifRecord().call("W1AW").gridsquare(null).build())
                .doesNotContain("gridsquare");
        assertThat(new AdifRecord().call("W1AW").gridsquare("").build())
                .doesNotContain("gridsquare");
    }

    @Test
    public void noFieldIsEverEmittedWithZeroLength() {
        // A record where every optional field is empty must still be a valid record —
        // just a sparse one — with no "<name:0>" anywhere in it.
        String out = new AdifRecord()
                .call("W1AW")
                .gridsquare("").mode("").rstSent("").rstRcvd("")
                .qsoDate("").timeOn("").qsoDateOff("").timeOff("")
                .band("").freq("").stationCallsign("").myGridsquare("")
                .operator("").mySig("").mySigInfo("").sig("").sigInfo("")
                .comment("")
                .build();

        assertThat(out).doesNotContain(":0>");
        assertThat(out).contains("<call:4>W1AW ");
        assertThat(out).endsWith("<eor>\n");
    }

    @Test
    public void comment_isOmittedWhenNullOrEmptyButRecordStillTerminates() {
        // The <eor> used to be glued onto the comment field, so a commentless QSO got a
        // zero-length comment purely to carry the terminator. They are separate now.
        assertThat(new AdifRecord().call("W1AW").comment(null).build())
                .isEqualTo("<call:4>W1AW <QSL_RCVD:1>N <APP_FT8AF_QSL_MANUAL:1>N <eor>\n");
        assertThat(new AdifRecord().call("W1AW").comment("").build())
                .doesNotContain("comment");
        assertThat(new AdifRecord().call("W1AW").comment("hi").build())
                .contains("<comment:2>hi <eor>\n");
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
