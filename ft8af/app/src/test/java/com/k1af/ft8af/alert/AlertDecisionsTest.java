package com.k1af.ft8af.alert;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure-JVM tests for {@link AlertDecisions} (no Android runtime needed). */
public class AlertDecisionsTest {

    @Test
    public void cqReply_firesOnlyWhenEnabledAddressedAndNotBlocked() {
        assertThat(AlertDecisions.shouldAlertCqReply(true, true, false)).isTrue();
    }

    @Test
    public void cqReply_suppressedWhenDisabled() {
        assertThat(AlertDecisions.shouldAlertCqReply(false, true, false)).isFalse();
    }

    @Test
    public void cqReply_suppressedWhenNotAddressedToMe() {
        assertThat(AlertDecisions.shouldAlertCqReply(true, false, false)).isFalse();
    }

    @Test
    public void cqReply_suppressedWhenBlocked() {
        assertThat(AlertDecisions.shouldAlertCqReply(true, true, true)).isFalse();
    }

    @Test
    public void cqReplyDedupKey_isStableAndCaseInsensitive() {
        String a = AlertDecisions.cqReplyDedupKey("k1af", " K1AF W5XYZ -12 ");
        String b = AlertDecisions.cqReplyDedupKey("K1AF", "k1af w5xyz -12");
        assertThat(a).isEqualTo(b);
        assertThat(a).startsWith("CQREPLY:");
    }

    @Test
    public void cqReplyDedupKey_differsByContent() {
        String first = AlertDecisions.cqReplyDedupKey("K1AF", "W5XYZ K1AF -12");
        String second = AlertDecisions.cqReplyDedupKey("K1AF", "W5XYZ K1AF R-09");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    public void cqReplyDedupKey_handlesNulls() {
        assertThat(AlertDecisions.cqReplyDedupKey(null, null)).isEqualTo("CQREPLY:|");
    }

    @Test
    public void qsoCompleteDedupKey_uniquePerCallAndTime() {
        String k = AlertDecisions.qsoCompleteDedupKey("w5xyz", "20231114-123456");
        assertThat(k).isEqualTo("QSO:W5XYZ|20231114-123456");
        assertThat(k).isNotEqualTo(AlertDecisions.qsoCompleteDedupKey("W5XYZ", "20231114-123457"));
    }
}
