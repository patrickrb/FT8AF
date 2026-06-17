package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for the OAuth WebView's navigation classification and URL redaction —
 * the bug-prone, non-Composable logic lifted out of [PotaOAuthDialog] so it can be
 * tested without a WebView. [classifyRedirect] uses android.net.Uri, so the suite
 * runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class OAuthRedirectTest {

    private val redirect = "https://pota.app/"

    @Test
    fun `redirect carrying a code yields WithCode`() {
        val r = classifyRedirect("https://pota.app/?code=abc123&state=x", redirect)
        assertThat(r).isEqualTo(OAuthRedirect.WithCode("abc123"))
    }

    @Test
    fun `redirect with an error and no code yields NoCode`() {
        val r = classifyRedirect("https://pota.app/?error=access_denied", redirect)
        assertThat(r).isEqualTo(OAuthRedirect.NoCode)
    }

    @Test
    fun `bare redirect with no query yields NoCode`() {
        assertThat(classifyRedirect("https://pota.app/", redirect)).isEqualTo(OAuthRedirect.NoCode)
    }

    @Test
    fun `an empty code parameter is treated as NoCode`() {
        assertThat(classifyRedirect("https://pota.app/?code=", redirect)).isEqualTo(OAuthRedirect.NoCode)
    }

    @Test
    fun `provider and cognito urls are not the redirect`() {
        assertThat(classifyRedirect("https://accounts.google.com/o/oauth2/auth?x=1", redirect))
            .isEqualTo(OAuthRedirect.NotRedirect)
        assertThat(classifyRedirect("https://parksontheair.auth.us-east-2.amazoncognito.com/login", redirect))
            .isEqualTo(OAuthRedirect.NotRedirect)
    }

    @Test
    fun `a lookalike host carrying a code is not the redirect`() {
        // Raw startsWith would have matched this on the "https://pota.app" prefix
        // and tried to exchange the attacker-supplied code; parsed-host compare rejects it.
        assertThat(classifyRedirect("https://pota.app.evil.example/?code=abc123", redirect))
            .isEqualTo(OAuthRedirect.NotRedirect)
    }

    @Test
    fun `a different scheme is not the redirect`() {
        assertThat(classifyRedirect("http://pota.app/?code=abc123", redirect))
            .isEqualTo(OAuthRedirect.NotRedirect)
    }

    @Test
    fun `host comparison ignores case`() {
        assertThat(classifyRedirect("https://POTA.app/?code=abc123", redirect))
            .isEqualTo(OAuthRedirect.WithCode("abc123"))
    }

    @Test
    fun `redactUrl drops the query string`() {
        assertThat(redactUrl("https://pota.app/?code=secret&state=xyz")).isEqualTo("https://pota.app/?…")
    }

    @Test
    fun `redactUrl leaves a query-less url intact`() {
        assertThat(redactUrl("https://accounts.google.com/signin")).isEqualTo("https://accounts.google.com/signin")
    }

    @Test
    fun `redactUrl tolerates null and empty`() {
        assertThat(redactUrl(null)).isEqualTo("?")
        assertThat(redactUrl("")).isEqualTo("?")
    }
}
