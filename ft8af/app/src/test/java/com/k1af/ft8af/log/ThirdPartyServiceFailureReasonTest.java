package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for the upload-failure descriptions in {@link ThirdPartyService}.
 *
 * <p>Motivation: a Cloudlog-compatible server that rejects every QSO (say, its
 * {@code contacts} table is missing a column its INSERT references) used to be
 * indistinguishable in {@code debug.log} from having nothing to upload — both printed
 * {@code cloudlog=0}. These helpers turn the server's own explanation into one short line.
 * Pure string logic, no network.
 */
public class ThirdPartyServiceFailureReasonTest {

    @Test
    public void httpFailure_includesStatusAndServerBody() {
        String body = "{\"status\":\"abort\",\"adif_errors\":1,"
                + "\"messages\":[\"column \\\"tx_pwr\\\" of relation \\\"contacts\\\" does not exist\"]}";
        String out = ThirdPartyService.describeHttpFailure(400, body);

        assertThat(out).startsWith("HTTP 400: ");
        assertThat(out).contains("tx_pwr");
        assertThat(out).contains("does not exist");
    }

    @Test
    public void httpFailure_withNoBody_isJustTheStatus() {
        assertThat(ThirdPartyService.describeHttpFailure(502, null)).isEqualTo("HTTP 502");
        assertThat(ThirdPartyService.describeHttpFailure(502, "")).isEqualTo("HTTP 502");
        assertThat(ThirdPartyService.describeHttpFailure(502, "   \n  ")).isEqualTo("HTTP 502");
    }

    @Test
    public void httpFailure_collapsesNewlinesSoTheLogStaysOneLinePerEntry() {
        String out = ThirdPartyService.describeHttpFailure(500, "line one\nline two\r\n\tline three");

        assertThat(out).doesNotContain("\n");
        assertThat(out).doesNotContain("\r");
        assertThat(out).isEqualTo("HTTP 500: line one line two line three");
    }

    @Test
    public void httpFailure_truncatesAnEnormousBody() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            huge.append("<html>error page</html>");
        }
        String out = ThirdPartyService.describeHttpFailure(500, huge.toString());

        // "HTTP 500: " + 200 chars + the ellipsis.
        assertThat(out.length()).isLessThan(240);
        assertThat(out).endsWith("…");
    }

    @Test
    public void qrzFailure_reportsResultAndReason() {
        String out = ThirdPartyService.describeQrzFailure(
                "RESULT=FAIL&REASON=Unable to add QSO to database: duplicate&EXTENDED=");

        assertThat(out).isEqualTo("RESULT=FAIL: Unable to add QSO to database: duplicate");
    }

    @Test
    public void qrzFailure_withoutReason_reportsResultAlone() {
        assertThat(ThirdPartyService.describeQrzFailure("RESULT=FAIL&COUNT=0"))
                .isEqualTo("RESULT=FAIL");
    }

    @Test
    public void qrzFailure_withUnparseableResponse_saysSo() {
        assertThat(ThirdPartyService.describeQrzFailure("")).isEqualTo("RESULT=(none)");
        assertThat(ThirdPartyService.describeQrzFailure(null)).isEqualTo("RESULT=(none)");
        assertThat(ThirdPartyService.describeQrzFailure("<html>gateway timeout</html>"))
                .isEqualTo("RESULT=(none)");
    }

    @Test
    public void qrzFailure_neverEchoesTheSubmittedAdif() {
        // The response echoes back what we sent; debug.log must not accumulate whole logbooks.
        String response = "RESULT=FAIL&REASON=bad record&ADIF=<call:4>W1AW <comment:9>secret ok <eor>";
        String out = ThirdPartyService.describeQrzFailure(response);

        assertThat(out).doesNotContain("W1AW");
        assertThat(out).doesNotContain("secret");
    }

    @Test
    public void syncResult_defaultsToNoRecordedErrors() {
        ThirdPartyService.SyncResult r =
                new ThirdPartyService.SyncResult(5, 5, 0, true, false);

        assertThat(r.cloudlogError).isNull();
        assertThat(r.qrzError).isNull();
        assertThat(r.total).isEqualTo(5);
        assertThat(r.cloudlogOk).isEqualTo(5);
    }
}
