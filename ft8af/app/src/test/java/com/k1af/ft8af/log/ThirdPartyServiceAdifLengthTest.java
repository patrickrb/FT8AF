package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Verifies that {@link ThirdPartyService#QSLRecordToADIF} declares each ADIF
 * {@code <field:LEN>} length in UTF-8 <em>bytes</em>, not UTF-16 chars — the same
 * rule {@link AdifRecord} enforces via {@link AdifFormat#utf8Length}.
 *
 * <p>The Cloudlog/QRZ upload path hand-built its records with {@code String.length()},
 * so any non-ASCII value (an accented comment, a POTA park name) declared a length
 * shorter than the bytes actually written. The receiving logger reads only that many
 * bytes and mis-aligns everything after the field, so the record is truncated/rejected.
 *
 * <p>Runs under Robolectric because {@link QSLRecord} touches Android {@code Log}.
 */
@RunWith(RobolectricTestRunner.class)
public class ThirdPartyServiceAdifLengthTest {

    private static QSLRecord recordWithComment(String comment) {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("STATION_CALLSIGN", "K1AF");
        map.put("MODE", "FT8");
        map.put("COMMENT", comment);
        return new QSLRecord(map);
    }

    /** Pull the declared length out of {@code <comment:LEN>...} in the record. */
    private static int declaredCommentLength(String adif) {
        int tag = adif.indexOf("<comment:");
        assertThat(tag).isAtLeast(0);
        int gt = adif.indexOf('>', tag);
        return Integer.parseInt(adif.substring(tag + "<comment:".length(), gt));
    }

    @Test
    public void comment_lengthIsUtf8ByteCount_notCharCount() {
        // "Café QSO" — the 'é' is one UTF-16 char but two UTF-8 bytes, so the byte
        // length (9) exceeds the char length (8). The buggy code declared 8.
        String comment = "Café QSO";
        assertThat(comment.length()).isEqualTo(8);
        assertThat(comment.getBytes(StandardCharsets.UTF_8).length).isEqualTo(9);

        String adif = ThirdPartyService.QSLRecordToADIF(recordWithComment(comment),
                ServiceType.QRZ);

        assertThat(declaredCommentLength(adif)).isEqualTo(9);
        assertThat(adif).contains("<comment:9>Café QSO <eor>");
        assertThat(adif).doesNotContain("<comment:8>");
    }

    @Test
    public void comment_multiByteCjk_declaresByteLength() {
        // Three CJK ideographs: 3 chars, 9 UTF-8 bytes (3 bytes each).
        String comment = "中文注"; // 中文注
        assertThat(comment.length()).isEqualTo(3);
        assertThat(comment.getBytes(StandardCharsets.UTF_8).length).isEqualTo(9);

        String adif = ThirdPartyService.QSLRecordToADIF(recordWithComment(comment),
                ServiceType.Cloudlog);

        assertThat(declaredCommentLength(adif)).isEqualTo(9);
    }

    @Test
    public void nullComment_emitsEmptyFieldNotLiteralNull() {
        // A COMMENT key mapped to a null value leaves getComment() null. Without coercion
        // String.format would emit "<comment:0>null <eor>" (%s renders null as "null"),
        // declaring length 0 but writing 4 bytes. It must be an empty field instead.
        String adif = ThirdPartyService.QSLRecordToADIF(recordWithComment(null),
                ServiceType.QRZ);

        assertThat(declaredCommentLength(adif)).isEqualTo(0);
        assertThat(adif).contains("<comment:0> <eor>");
        assertThat(adif).doesNotContain("null <eor>");
    }

    @Test
    public void asciiComment_lengthUnchanged() {
        // Pure-ASCII values must be byte-identical to before: bytes == chars.
        String comment = "Distance: 99 km, QSO by FT8AF";
        String adif = ThirdPartyService.QSLRecordToADIF(recordWithComment(comment),
                ServiceType.QRZ);

        assertThat(declaredCommentLength(adif)).isEqualTo(comment.length());
        assertThat(adif).contains("<comment:" + comment.length() + ">" + comment + " <eor>");
    }
}
