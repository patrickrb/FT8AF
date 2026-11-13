package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@link DatabaseOpr#configValueForLog}: writeConfig logs every write, so credential
 * keys must be masked before the value reaches logcat (Copilot review on PR #814).
 */
public class DatabaseOprConfigLogRedactionTest {

    @Test
    public void credentialKeysAreMaskedWithOnlyTheirLength() {
        assertThat(DatabaseOpr.configValueForLog("wrlApiKey", "wrl_live_abcdef123"))
                .isEqualTo("*** (18 chars)");
        assertThat(DatabaseOpr.configValueForLog("qrzApiKey", "ABCD-1234")).doesNotContain("ABCD");
        assertThat(DatabaseOpr.configValueForLog("cloudlogApiKey", "cl0123456789"))
                .doesNotContain("cl0123456789");
        assertThat(DatabaseOpr.configValueForLog("qrzXmlPassword", "hunter2")).doesNotContain("hunter2");
    }

    @Test
    public void ordinaryKeysAreLoggedVerbatim() {
        assertThat(DatabaseOpr.configValueForLog("wrlLogbookId", "3f1c-uuid")).isEqualTo("3f1c-uuid");
        assertThat(DatabaseOpr.configValueForLog("toModifier", "POTA")).isEqualTo("POTA");
    }

    @Test
    public void nullValueAndNullKeyAreSafe() {
        assertThat(DatabaseOpr.configValueForLog("wrlApiKey", null)).isEqualTo("null");
        assertThat(DatabaseOpr.configValueForLog(null, "x")).isEqualTo("x");
    }
}
