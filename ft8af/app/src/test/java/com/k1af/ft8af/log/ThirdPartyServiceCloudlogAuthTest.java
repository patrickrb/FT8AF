package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link ThirdPartyService#interpretAuthResponse(String)} — the pure half of
 * the Cloudlog "Test Connection" check (issue #756). No network, no Android types.
 */
public class ThirdPartyServiceCloudlogAuthTest {

    @Test
    public void validRw_passes() {
        ThirdPartyService.ConnectionCheck c = ThirdPartyService.interpretAuthResponse(
                "<?xml version=\"1.0\"?>\n<auth>\n  <status>Valid</status>\n"
                        + "  <rights>rw</rights>\n</auth>");
        assertThat(c.ok).isTrue();
        assertThat(c.detail).isNull();
    }

    @Test
    public void validReadOnly_failsWithReason() {
        ThirdPartyService.ConnectionCheck c = ThirdPartyService.interpretAuthResponse(
                "<auth><status>Valid</status><rights>r</rights></auth>");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).contains("read-only");
    }

    @Test
    public void invalidKey_failsWithReason() {
        ThirdPartyService.ConnectionCheck c = ThirdPartyService.interpretAuthResponse(
                "<auth><status>Invalid</status><rights></rights></auth>");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).contains("rejected");
    }

    @Test
    public void notAnApiReply_failsWithReason() {
        // A no-rewrite Wavelog answering the plain path with its HTML 404 page, or a
        // reverse proxy landing page.
        ThirdPartyService.ConnectionCheck c = ThirdPartyService.interpretAuthResponse(
                "<html><body><h1>404 Not Found</h1></body></html>");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).contains("not a Cloudlog API");
        assertThat(ThirdPartyService.interpretAuthResponse(null).ok).isFalse();
        assertThat(ThirdPartyService.interpretAuthResponse("").ok).isFalse();
    }
}
