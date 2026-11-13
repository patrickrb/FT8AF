package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives {@link ThirdPartyService#cloudlogRequest} — the candidate walk behind
 * {@link ThirdPartyService#cloudlogGet} and {@link ThirdPartyService#cloudlogPost} — with a
 * scripted {@link ThirdPartyService.CloudlogTransport}, so the 404-only fallback, the
 * stop-on-anything-else rule and the remembered-variant reordering are pinned down
 * without a network. Also covers the user-info redaction added to
 * {@link ThirdPartyService#redactUrlApiKey}.
 */
public class ThirdPartyServiceCloudlogRequestTest {

    private static final String BASE = "http://192.168.1.5/wavelog/";
    private static final String PLAIN = BASE + "api/auth/KEY";
    private static final String REWRITTEN = BASE + "index.php/api/auth/KEY";

    /** One scripted attempt: status to report, body to return (null = failure), or throw. */
    private static final class Step {
        final int status;
        final String body;
        final IOException error;
        final String why;

        Step(int status, String body, IOException error, String why) {
            this.status = status;
            this.body = body;
            this.error = error;
            this.why = why;
        }

        static Step ok(String body) { return new Step(200, body, null, null); }
        static Step status(int s) { return new Step(s, null, null, null); }
        static Step statusWithReason(int s, String why) { return new Step(s, null, null, why); }
        static Step failing(IOException e) { return new Step(0, null, e, null); }
    }

    private static final class ScriptedTransport implements ThirdPartyService.CloudlogTransport {
        final List<Step> script = new ArrayList<>();
        final List<String> urlsTried = new ArrayList<>();

        ScriptedTransport(Step... steps) {
            for (Step s : steps) script.add(s);
        }

        @Override
        public String call(String url, int[] statusOut, StringBuilder why) throws IOException {
            urlsTried.add(url);
            if (script.isEmpty()) throw new AssertionError("unexpected attempt on " + url);
            Step s = script.remove(0);
            if (s.error != null) throw s.error;
            statusOut[0] = s.status;
            if (s.why != null) why.append(s.why);
            return s.body;
        }
    }

    @Before
    public void reset() {
        CloudlogEndpoint.forgetAll();
    }

    // ---- fallback control flow ---------------------------------------------------

    @Test
    public void first404_thenSuccessOnIndexPhp() {
        ScriptedTransport t = new ScriptedTransport(Step.status(404), Step.ok("<auth/>"));
        StringBuilder why = new StringBuilder();

        String body = ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", why, t);

        assertThat(body).isEqualTo("<auth/>");
        assertThat(t.urlsTried).containsExactly(PLAIN, REWRITTEN).inOrder();
        assertThat(why.toString()).isEmpty();
    }

    @Test
    public void firstAttemptSucceeds_noSecondRequest() {
        ScriptedTransport t = new ScriptedTransport(Step.ok("<auth/>"));

        String body = ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY",
                new StringBuilder(), t);

        assertThat(body).isEqualTo("<auth/>");
        assertThat(t.urlsTried).containsExactly(PLAIN);
    }

    @Test
    public void non404Status_stopsWithoutTryingFallback() {
        ScriptedTransport t = new ScriptedTransport(Step.status(401));
        StringBuilder why = new StringBuilder();

        String body = ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", why, t);

        assertThat(body).isNull();
        assertThat(t.urlsTried).containsExactly(PLAIN);
        assertThat(why.toString()).contains("HTTP 401");
        // The logged/reported URL never carries the key.
        assertThat(why.toString()).doesNotContain("KEY");
        assertThat(why.toString()).contains("auth/***");
    }

    @Test
    public void post_serverReasonWins_andStopsOnNon404() {
        ScriptedTransport t = new ScriptedTransport(
                Step.statusWithReason(500, "HTTP 500: Unknown column 'foo'"));
        StringBuilder why = new StringBuilder();

        String body = ThirdPartyService.cloudlogRequest("POST", BASE, "api/qso", why, t);

        assertThat(body).isNull();
        assertThat(t.urlsTried).containsExactly(BASE + "api/qso");
        assertThat(why.toString()).contains("Unknown column 'foo'");
    }

    @Test
    public void both404_reportsLastFailure() {
        ScriptedTransport t = new ScriptedTransport(Step.status(404), Step.status(404));
        StringBuilder why = new StringBuilder();

        String body = ThirdPartyService.cloudlogRequest("POST", BASE, "api/qso", why, t);

        assertThat(body).isNull();
        assertThat(t.urlsTried).hasSize(2);
        assertThat(why.toString()).contains("HTTP 404");
    }

    @Test
    public void transportException_stopsImmediately() {
        ScriptedTransport t = new ScriptedTransport(
                Step.failing(new ConnectException("ECONNREFUSED")), Step.ok("never"));
        StringBuilder why = new StringBuilder();

        String body = ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", why, t);

        assertThat(body).isNull();
        assertThat(t.urlsTried).containsExactly(PLAIN);
        assertThat(why.toString()).contains("Connect failed: ECONNREFUSED");
    }

    @Test
    public void nullFailureOut_tolerated() {
        ScriptedTransport t = new ScriptedTransport(Step.status(500));
        assertThat(ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", null, t))
                .isNull();
    }

    // ---- remembered variant ------------------------------------------------------

    @Test
    public void successOnIndexPhp_isRememberedForNextRequest() {
        ScriptedTransport first = new ScriptedTransport(Step.status(404), Step.ok("ok"));
        ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", new StringBuilder(), first);

        ScriptedTransport second = new ScriptedTransport(Step.ok("{}"));
        String body = ThirdPartyService.cloudlogRequest("POST", BASE, "api/qso",
                new StringBuilder(), second);

        assertThat(body).isEqualTo("{}");
        // No 404 round-trip this time: the index.php/ shape is tried first.
        assertThat(second.urlsTried).containsExactly(BASE + "index.php/api/qso");
    }

    @Test
    public void successOnPlain_keepsPlainFirst() {
        ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", new StringBuilder(),
                new ScriptedTransport(Step.ok("ok")));

        ScriptedTransport second = new ScriptedTransport(Step.ok("{}"));
        ThirdPartyService.cloudlogRequest("POST", BASE, "api/qso", new StringBuilder(), second);

        assertThat(second.urlsTried).containsExactly(BASE + "api/qso");
    }

    @Test
    public void rememberedIndexPhp_stillFallsBackToPlainOn404() {
        ThirdPartyService.cloudlogRequest("GET", BASE, "api/auth/KEY", new StringBuilder(),
                new ScriptedTransport(Step.status(404), Step.ok("ok")));

        ScriptedTransport second = new ScriptedTransport(Step.status(404), Step.ok("{}"));
        String body = ThirdPartyService.cloudlogRequest("POST", BASE, "api/qso",
                new StringBuilder(), second);

        assertThat(body).isEqualTo("{}");
        assertThat(second.urlsTried)
                .containsExactly(BASE + "index.php/api/qso", BASE + "api/qso").inOrder();
    }

    // ---- redaction of authority user-info -----------------------------------------

    @Test
    public void redact_hidesUserInfo() {
        assertThat(ThirdPartyService.redactUrlApiKey("http://alice:s3cret@nas/wavelog/api/qso"))
                .isEqualTo("http://***@nas/wavelog/api/qso");
        assertThat(ThirdPartyService.redactUrlApiKey("https://alice@log.example.org/"))
                .isEqualTo("https://***@log.example.org/");
    }

    @Test
    public void redact_hidesUserInfoAndApiKeyTogether() {
        assertThat(ThirdPartyService.redactUrlApiKey(
                "http://alice:s3cret@nas/wavelog/index.php/api/auth/KEY123"))
                .isEqualTo("http://***@nas/wavelog/index.php/api/auth/***");
    }

    @Test
    public void redact_leavesUrlsWithoutUserInfoAlone() {
        assertThat(ThirdPartyService.redactUrlApiKey("http://nas/wavelog/api/qso"))
                .isEqualTo("http://nas/wavelog/api/qso");
        // An '@' later in the URL (query/path) is not user-info.
        assertThat(ThirdPartyService.redactUrlApiKey("http://nas/x?email=a@b.c"))
                .isEqualTo("http://nas/x?email=a@b.c");
        assertThat(ThirdPartyService.redactUrlApiKey("http://nas/path/a@b/"))
                .isEqualTo("http://nas/path/a@b/");
    }

    @Test
    public void redact_userInfoInLoggedFailureReason() {
        ScriptedTransport t = new ScriptedTransport(Step.status(401));
        StringBuilder why = new StringBuilder();
        ThirdPartyService.cloudlogRequest("GET", "http://bob:pw@nas/wavelog/", "api/auth/KEY",
                why, t);
        assertThat(why.toString()).doesNotContain("pw");
        assertThat(why.toString()).contains("http://***@nas/wavelog/api/auth/***");
    }
}
