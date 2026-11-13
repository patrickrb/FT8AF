package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * {@link CloudlogEndpoint#endsWithIndexPhpSegment(String)} and its effect on
 * {@link CloudlogEndpoint#candidates}: only a base whose <em>path</em> already ends in the
 * {@code index.php/} segment gets the single-candidate treatment. A hostname or directory
 * that merely contains the text must still receive the {@code index.php/} fallback,
 * otherwise a no-rewrite install at such an address can never be reached.
 */
public class CloudlogEndpointIndexPhpTest {

    @Before
    public void reset() {
        CloudlogEndpoint.forgetAll();
    }

    @Test
    public void terminalIndexPhpSegment_detected() {
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("http://192.168.1.5/wavelog/index.php/"))
                .isTrue();
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("https://log.example.org/index.php/"))
                .isTrue();
        // Case-insensitive, like the rest of the URL handling.
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("http://nas/cloudlog/INDEX.PHP/"))
                .isTrue();
    }

    @Test
    public void indexPhpInHostname_notDetected() {
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("https://index.php.example/cloudlog/"))
                .isFalse();
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("https://index.php.example/"))
                .isFalse();
    }

    @Test
    public void indexPhpAsPartOfAnotherSegment_notDetected() {
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("http://nas/index.php-old/"))
                .isFalse();
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("http://nas/index.php/wavelog/"))
                .isFalse();
    }

    @Test
    public void nullOrSchemeless_handled() {
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment(null)).isFalse();
        assertThat(CloudlogEndpoint.endsWithIndexPhpSegment("nas/wavelog/index.php/")).isTrue();
    }

    @Test
    public void candidates_hostnameContainingIndexPhp_stillGetsFallback() {
        List<String> c = CloudlogEndpoint.candidates("https://index.php.example/cloudlog/",
                "api/auth/KEY");
        assertThat(c).containsExactly(
                "https://index.php.example/cloudlog/api/auth/KEY",
                "https://index.php.example/cloudlog/index.php/api/auth/KEY").inOrder();
    }

    @Test
    public void candidates_terminalIndexPhp_singleCandidate() {
        List<String> c = CloudlogEndpoint.candidates("http://nas/wavelog/index.php/",
                "api/auth/KEY");
        assertThat(c).containsExactly("http://nas/wavelog/index.php/api/auth/KEY");
    }
}
