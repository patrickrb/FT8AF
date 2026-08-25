package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@link WebNavigationPolicy} keeps the embedded WebViews HTTPS-only now that the
 * network-security base-config permits cleartext for the user's LAN logbook (issue #756).
 */
public class WebNavigationPolicyTest {

    @Test
    public void https_allowed() {
        assertThat(WebNavigationPolicy.isAllowed("https://www.qrz.com/db/K1AF")).isTrue();
        assertThat(WebNavigationPolicy.isAllowed("https://support.qq.com/product/415890")).isTrue();
    }

    @Test
    public void schemeCaseAndWhitespace_tolerated() {
        assertThat(WebNavigationPolicy.isAllowed("  HTTPS://example.org/x ")).isTrue();
    }

    @Test
    public void http_refused() {
        assertThat(WebNavigationPolicy.isAllowed("http://example.org/")).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("http://192.168.1.10/wavelog/")).isFalse();
    }

    @Test
    public void otherSchemes_refused() {
        assertThat(WebNavigationPolicy.isAllowed("file:///sdcard/x.html")).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("javascript:alert(1)")).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("intent://x#Intent;end")).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("content://com.k1af.ft8af/x")).isFalse();
    }

    @Test
    public void nullEmptyOrBareScheme_refused() {
        assertThat(WebNavigationPolicy.isAllowed(null)).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("")).isFalse();
        assertThat(WebNavigationPolicy.isAllowed("https://")).isFalse();
    }
}
