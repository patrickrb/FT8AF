package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Unit tests for {@link CloudlogEndpoint} (issue #756): the pure URL plumbing that turns a
 * hand-typed Cloudlog / Wavelog / Nextlog address into the candidate API URLs. Pure JUnit —
 * no network, no Android types.
 */
public class CloudlogEndpointTest {

    @Before
    public void reset() {
        CloudlogEndpoint.forgetAll();
    }

    // ---- normalizeBase ------------------------------------------------------------

    @Test
    public void normalizeBase_nullOrBlank_isNull() {
        assertThat(CloudlogEndpoint.normalizeBase(null)).isNull();
        assertThat(CloudlogEndpoint.normalizeBase("")).isNull();
        assertThat(CloudlogEndpoint.normalizeBase("   \n")).isNull();
    }

    @Test
    public void normalizeBase_trimsAndAddsTrailingSlash() {
        assertThat(CloudlogEndpoint.normalizeBase("  http://192.168.186.73/wavelog \n"))
                .isEqualTo("http://192.168.186.73/wavelog/");
    }

    @Test
    public void normalizeBase_keepsSingleTrailingSlash() {
        assertThat(CloudlogEndpoint.normalizeBase("http://192.168.15.20/cloudlog/"))
                .isEqualTo("http://192.168.15.20/cloudlog/");
        assertThat(CloudlogEndpoint.normalizeBase("http://192.168.15.20/cloudlog//"))
                .isEqualTo("http://192.168.15.20/cloudlog/");
    }

    @Test
    public void normalizeBase_schemelessIpv4_getsHttp() {
        // The reporter's "ip only" attempt: new URL() used to throw "no protocol".
        assertThat(CloudlogEndpoint.normalizeBase("192.168.15.20/cloudlog"))
                .isEqualTo("http://192.168.15.20/cloudlog/");
        assertThat(CloudlogEndpoint.normalizeBase("192.168.1.3:1234"))
                .isEqualTo("http://192.168.1.3:1234/");
    }

    @Test
    public void normalizeBase_schemelessLanNames_getHttp() {
        assertThat(CloudlogEndpoint.normalizeBase("localhost:8080/wavelog"))
                .isEqualTo("http://localhost:8080/wavelog/");
        assertThat(CloudlogEndpoint.normalizeBase("nas/wavelog"))
                .isEqualTo("http://nas/wavelog/");
        assertThat(CloudlogEndpoint.normalizeBase("raspberrypi.local/cloudlog"))
                .isEqualTo("http://raspberrypi.local/cloudlog/");
        assertThat(CloudlogEndpoint.normalizeBase("shack.lan"))
                .isEqualTo("http://shack.lan/");
    }

    @Test
    public void normalizeBase_schemelessPublicHostname_getsHttps() {
        assertThat(CloudlogEndpoint.normalizeBase("log.example.com/wavelog"))
                .isEqualTo("https://log.example.com/wavelog/");
    }

    @Test
    public void normalizeBase_explicitSchemeIsNeverRewritten() {
        assertThat(CloudlogEndpoint.normalizeBase("https://192.168.1.5/cloudlog"))
                .isEqualTo("https://192.168.1.5/cloudlog/");
        assertThat(CloudlogEndpoint.normalizeBase("http://log.example.com"))
                .isEqualTo("http://log.example.com/");
    }

    @Test
    public void hostOf_stripsUserinfoPortAndPath() {
        assertThat(CloudlogEndpoint.hostOf("user:pw@192.168.1.5:8080/wavelog/"))
                .isEqualTo("192.168.1.5");
        assertThat(CloudlogEndpoint.hostOf("log.example.com/x")).isEqualTo("log.example.com");
        assertThat(CloudlogEndpoint.hostOf("[fe80::1]:80/cl")).isEqualTo("[fe80::1]");
    }

    // ---- candidates -----------------------------------------------------------------

    @Test
    public void candidates_rewrittenFormFirst_thenIndexPhp() {
        List<String> c = CloudlogEndpoint.candidates("http://192.168.186.73/wavelog/",
                "api/auth/KEY");
        assertThat(c).containsExactly(
                "http://192.168.186.73/wavelog/api/auth/KEY",
                "http://192.168.186.73/wavelog/index.php/api/auth/KEY").inOrder();
    }

    @Test
    public void candidates_stripLeadingSlashFromPath() {
        List<String> c = CloudlogEndpoint.candidates("http://h/", "/api/qso");
        assertThat(c.get(0)).isEqualTo("http://h/api/qso");
        assertThat(c.get(1)).isEqualTo("http://h/index.php/api/qso");
    }

    @Test
    public void candidates_baseAlreadyHasIndexPhp_singleCandidate() {
        List<String> c = CloudlogEndpoint.candidates("http://h/wavelog/index.php/", "api/qso");
        assertThat(c).containsExactly("http://h/wavelog/index.php/api/qso");
    }

    @Test
    public void candidates_nullBase_isEmpty() {
        assertThat(CloudlogEndpoint.candidates(null, "api/qso")).isEmpty();
    }

    @Test
    public void rememberWorking_indexPhp_movesItToFront() {
        String base = "http://192.168.186.73/wavelog/";
        CloudlogEndpoint.rememberWorking(base, base + "index.php/api/auth/KEY");
        List<String> c = CloudlogEndpoint.candidates(base, "api/qso");
        assertThat(c).containsExactly(
                base + "index.php/api/qso",
                base + "api/qso").inOrder();
    }

    @Test
    public void rememberWorking_plain_keepsPlainFirst() {
        String base = "http://192.168.186.73/wavelog/";
        CloudlogEndpoint.rememberWorking(base, base + "index.php/api/auth/KEY");
        CloudlogEndpoint.rememberWorking(base, base + "api/auth/KEY"); // server got rewriting
        List<String> c = CloudlogEndpoint.candidates(base, "api/qso");
        assertThat(c.get(0)).isEqualTo(base + "api/qso");
    }

    @Test
    public void rememberWorking_isPerBase() {
        String a = "http://a/";
        String b = "http://b/";
        CloudlogEndpoint.rememberWorking(a, a + "index.php/api/auth/K");
        assertThat(CloudlogEndpoint.candidates(b, "api/qso").get(0)).isEqualTo(b + "api/qso");
    }

    @Test
    public void rememberWorking_ignoresForeignUrl() {
        String base = "http://a/";
        CloudlogEndpoint.rememberWorking(base, "http://elsewhere/index.php/api/auth/K");
        assertThat(CloudlogEndpoint.candidates(base, "api/qso").get(0))
                .isEqualTo(base + "api/qso");
    }

    // ---- describeFailure ------------------------------------------------------------

    @Test
    public void describeFailure_httpStatus() {
        assertThat(CloudlogEndpoint.describeFailure("http://h/api/auth/***", 404, null))
                .isEqualTo("HTTP 404 from http://h/api/auth/***");
    }

    @Test
    public void describeFailure_noStatusNoException() {
        assertThat(CloudlogEndpoint.describeFailure("http://h/api/auth/***", 0, null))
                .isEqualTo("No response from http://h/api/auth/***");
        assertThat(CloudlogEndpoint.describeFailure(null, 0, null)).isEqualTo("No response");
    }

    @Test
    public void describeFailure_wellKnownNetExceptions() {
        assertThat(CloudlogEndpoint.describeFailure(null, 0, new UnknownHostException("nas")))
                .isEqualTo("Unknown host: nas");
        assertThat(CloudlogEndpoint.describeFailure(null, 0,
                new MalformedURLException("no protocol: 192.168.1.1")))
                .isEqualTo("Bad URL: no protocol: 192.168.1.1");
        assertThat(CloudlogEndpoint.describeFailure(null, 0,
                new SocketTimeoutException("connect timed out")))
                .isEqualTo("Timed out: connect timed out");
        assertThat(CloudlogEndpoint.describeFailure(null, 0,
                new ConnectException("Connection refused")))
                .isEqualTo("Connect failed: Connection refused");
    }

    @Test
    public void describeFailure_genericException_keepsClassAndMessage() {
        // This is what a cleartext block looked like before the config change — the
        // message is the whole diagnosis, and it used to be thrown away.
        assertThat(CloudlogEndpoint.describeFailure(null, 0,
                new java.io.IOException("Cleartext HTTP traffic to 192.168.186.73 not permitted")))
                .isEqualTo("IOException: Cleartext HTTP traffic to 192.168.186.73 not permitted");
        assertThat(CloudlogEndpoint.describeFailure(null, 0, new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }
}
