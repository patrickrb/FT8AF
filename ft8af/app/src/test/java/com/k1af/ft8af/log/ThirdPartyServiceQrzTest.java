package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link ThirdPartyService#parseQrzResult(String)}: the QRZ logbook
 * API replies with an {@code &}-separated body of {@code KEY=VALUE} pairs, and the
 * upload/connection-test paths key off the {@code RESULT} field. Pure JUnit — no
 * network, no Android types.
 */
public class ThirdPartyServiceQrzTest {

    @Test
    public void parses_resultOk() {
        assertThat(ThirdPartyService.parseQrzResult("RESULT=OK&COUNT=1&LOGID=12345"))
                .isEqualTo("OK");
    }

    @Test
    public void parses_resultFail() {
        assertThat(ThirdPartyService.parseQrzResult("RESULT=FAIL&REASON=invalid+api+key"))
                .isEqualTo("FAIL");
    }

    @Test
    public void parses_resultReplace() {
        assertThat(ThirdPartyService.parseQrzResult("RESULT=REPLACE&COUNT=1"))
                .isEqualTo("REPLACE");
    }

    @Test
    public void parses_resultNotFirstField() {
        // RESULT can appear after other fields in the body.
        assertThat(ThirdPartyService.parseQrzResult("STATUS=AUTH&RESULT=OK&COUNT=1"))
                .isEqualTo("OK");
    }

    @Test
    public void returnsNull_whenResultFieldMissing() {
        assertThat(ThirdPartyService.parseQrzResult("STATUS=AUTH&COUNT=0")).isNull();
    }

    @Test
    public void returnsNull_forNullAndEmpty() {
        assertThat(ThirdPartyService.parseQrzResult(null)).isNull();
        assertThat(ThirdPartyService.parseQrzResult("")).isNull();
    }

    @Test
    public void handles_emptyResultValue() {
        // RESULT= with no value: split("=", 2) yields ["RESULT", ""], a present-but-empty value.
        assertThat(ThirdPartyService.parseQrzResult("RESULT=&COUNT=0")).isEqualTo("");
    }
}
