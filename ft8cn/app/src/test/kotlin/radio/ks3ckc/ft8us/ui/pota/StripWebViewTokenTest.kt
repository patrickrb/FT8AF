package radio.ks3ckc.ft8us.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [stripWebViewToken], which sanitizes the WebView user-agent for
 * POTA's hosted-UI OAuth flow: Google's consent screen rejects any UA carrying
 * the "; wv" token (disallowed_useragent).
 */
class StripWebViewTokenTest {

    @Test
    fun `removes the wv token from a default WebView user agent`() {
        val webViewUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240505.004; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"

        val cleaned = stripWebViewToken(webViewUa)

        assertThat(cleaned).doesNotContain("; wv")
        assertThat(cleaned).contains("Chrome/125.0.0.0")
        assertThat(cleaned).startsWith("Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240505.004)")
    }

    @Test
    fun `leaves a plain Chrome user agent unchanged`() {
        val chromeUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        assertThat(stripWebViewToken(chromeUa)).isEqualTo(chromeUa)
    }
}
