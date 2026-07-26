package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.MatrixCursor;

import androidx.test.core.app.ApplicationProvider;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Verifies that the web-logbook ADIF export in {@link DatabaseOpr#downQSLTable} declares every
 * field length as the UTF-8 <em>byte</em> count (what the ADIF {@code <field:len>} grammar
 * requires), not the UTF-16 {@link String#length()} char count. The two differ for any non-ASCII
 * content (an accented comment, an international POTA park name): declaring the shorter char count
 * makes a byte-correct consumer (LoTW/QRZ/Cloudlog) read fewer bytes than were written, truncating
 * the field and mis-aligning the record boundary.
 *
 * <p>{@code downQSLTable} — the built-in web logbook's download — now builds its records through
 * the canonical {@link com.k1af.ft8af.log.AdifRecord} rather than its own inline copy, so these
 * cases are covered twice over. They stay here as the guard on the cursor-to-record mapping.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprAdifLengthTest {

    /** Columns downQSLTable reads, including the optional operator/POTA columns. */
    private static final String[] COLUMNS = {
            "call", "isLotW_QSL", "isQSL", "gridsquare", "mode", "rst_sent", "rst_rcvd",
            "qso_date", "time_on", "qso_date_off", "time_off", "band", "freq",
            "station_callsign", "my_gridsquare", "operator",
            "my_sig", "my_sig_info", "sig", "sig_info", "comment"
    };

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 19);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    /** Export one row with the given comment / operator / sig_info values (nulls allowed). */
    private String export(String comment, String operator, String sigInfo) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{
                "W1AW", 0, 0, null, "FT8", "+00", "-05",
                "20260101", "1200", null, null, "20m", "14074000",
                null, null, operator,
                null, null, null, sigInfo, comment
        });
        return opr.downQSLTable(cursor, false);
    }

    /** Assert the declared length of {@code <name:LEN>value} equals value's UTF-8 byte length. */
    private static void assertDeclaredLenIsUtf8ByteLen(String adif, String name, String value) {
        Matcher m = Pattern.compile("<" + name + ":(\\d+)>").matcher(adif);
        assertThat(m.find()).isTrue();
        int declared = Integer.parseInt(m.group(1));
        assertThat(declared).isEqualTo(value.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void comment_nonAscii_declaresUtf8ByteLength() {
        // "Café QSO": 8 UTF-16 chars, 9 UTF-8 bytes (é = 2 bytes). Old code emitted <comment:8>,
        // truncating the trailing 'O' and corrupting the <eor> boundary.
        String comment = "Café QSO";
        String adif = export(comment, null, null);
        assertDeclaredLenIsUtf8ByteLen(adif, "comment", comment);
        assertThat(adif).contains("<comment:9>Café QSO <eor>");
    }

    @Test
    public void operator_nonAscii_declaresUtf8ByteLength() {
        String op = "JÜRGEN"; // Ü = 2 UTF-8 bytes -> 6 chars, 7 bytes
        String adif = export("QSO by FT8AF", op, null);
        assertDeclaredLenIsUtf8ByteLen(adif, "operator", op);
    }

    @Test
    public void potaSigInfo_nonAscii_declaresUtf8ByteLength() {
        String park = "Åland-01"; // Å = 2 UTF-8 bytes
        String adif = export("QSO by FT8AF", null, park);
        assertDeclaredLenIsUtf8ByteLen(adif, "SIG_INFO", park);
    }

    @Test
    public void asciiComment_unchanged_declaresCharLength() {
        // Pure-ASCII path must stay byte-identical to the prior format.
        String comment = "QSO by FT8AF";
        String adif = export(comment, null, null);
        assertThat(adif).contains("<comment:" + comment.length() + ">" + comment + " <eor>");
    }

    @Test
    public void nullComment_doesNotThrow_andIsOmittedEntirely() {
        // Previously comment.length() NPE'd on a NULL comment, aborting the whole download.
        // It then emitted "<comment:0> " instead; a zero-length field is not portable, so an
        // absent comment is now simply absent.
        String adif = export(null, null, null);
        assertThat(adif).doesNotContain("comment");
        assertThat(adif).endsWith("<eor>\n");
    }

    @Test
    public void emptyOptionalColumns_produceNoZeroLengthFields() {
        // Regression: a QSO logged without a grid (the other station never sent one) used to
        // export "<gridsquare:0> ", which strict ADIF importers reject.
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{
                "W1AW", 0, 0, "", "FT8", "", "",
                "20260101", "1200", "", "", "20m", "14074000",
                "", "", "",
                "", "", "", "", ""
        });
        String adif = opr.downQSLTable(cursor, false);

        assertThat(adif).doesNotContain(":0>");
        assertThat(adif).contains("<call:4>W1AW ");
        assertThat(adif).endsWith("<eor>\n");
    }

    @Test
    public void webLogbookExport_usesTheConformantManualQslFieldName() {
        // The web logbook download and the file export must agree; both go through AdifRecord.
        String adif = export("QSO by FT8AF", null, null);
        assertThat(adif).contains("<APP_FT8AF_QSL_MANUAL:1>N ");
        assertThat(adif).doesNotContain("<QSL_MANUAL:");
    }

    @Test
    public void arabicDefaultLocale_stillEmitsAsciiDigitLengths() {
        // %d under a default locale whose numbering system is Arabic-Indic (e.g. "ar")
        // emits ٠١٢٣… digits, making every <field:len> header unparseable to ADIF
        // consumers. The export must format with Locale.US regardless of device locale.
        Locale saved = Locale.getDefault();
        Locale.setDefault(new Locale("ar"));
        try {
            String adif = export("Café QSO", "JÜRGEN", null);
            assertThat(adif).contains("<comment:9>Café QSO <eor>");
            assertDeclaredLenIsUtf8ByteLen(adif, "operator", "JÜRGEN");
            // No non-ASCII digits anywhere in the export.
            assertThat(adif.matches("(?s).*[\\u0660-\\u0669].*")).isFalse();
        } finally {
            Locale.setDefault(saved);
        }
    }
}
