package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Base-URL normalization.
 *
 * The apex case is not cosmetic: `rtota.app` answers with a 308, no HTTP client
 * may auto-follow a 308 for a POST, and [isRetryableRtotaFailure] classifies a
 * 3xx as fatal — so a single wrong host turns every upload of a trip into a
 * permanent failure with a full queue behind it.
 */
class RtotaBaseUrlTest {
    @Test
    fun `the apex host is rewritten to www so POSTs are not 308ed`() {
        assertThat(normalizeRtotaBaseUrl("https://rtota.app")).isEqualTo("https://www.rtota.app")
        assertThat(normalizeRtotaBaseUrl("http://rtota.app")).isEqualTo("http://www.rtota.app")
        assertThat(normalizeRtotaBaseUrl("https://RTOTA.APP")).isEqualTo("https://www.rtota.app")
    }

    @Test
    fun `the default is already the www host`() {
        assertThat(RtotaSettings.DEFAULT_BASE_URL).isEqualTo("https://www.rtota.app")
        assertThat(normalizeRtotaBaseUrl(RtotaSettings.DEFAULT_BASE_URL))
            .isEqualTo(RtotaSettings.DEFAULT_BASE_URL)
    }

    @Test
    fun `a bare host gets https rather than throwing at request time`() {
        assertThat(normalizeRtotaBaseUrl("rtota.app")).isEqualTo("https://www.rtota.app")
        assertThat(normalizeRtotaBaseUrl("dev.example.com")).isEqualTo("https://dev.example.com")
    }

    @Test
    fun `trailing slashes and whitespace are stripped so paths append cleanly`() {
        assertThat(normalizeRtotaBaseUrl("  https://www.rtota.app/  ")).isEqualTo("https://www.rtota.app")
        assertThat(normalizeRtotaBaseUrl("https://rtota.app///")).isEqualTo("https://www.rtota.app")
    }

    @Test
    fun `a self-hosted or dev origin is left exactly as typed`() {
        // Nothing here knows how someone else's origin is fronted, so guessing
        // would be worse than leaving it alone.
        assertThat(normalizeRtotaBaseUrl("http://192.168.1.50:3000")).isEqualTo("http://192.168.1.50:3000")
        assertThat(normalizeRtotaBaseUrl("https://rtota.example.org")).isEqualTo("https://rtota.example.org")
    }

    @Test
    fun `only the apex host matches, not a lookalike or a path`() {
        assertThat(normalizeRtotaBaseUrl("https://myrtota.app")).isEqualTo("https://myrtota.app")
        assertThat(normalizeRtotaBaseUrl("https://staging.rtota.app")).isEqualTo("https://staging.rtota.app")
        assertThat(normalizeRtotaBaseUrl("https://example.com/rtota.app")).isEqualTo("https://example.com/rtota.app")
    }

    @Test
    fun `a host with a port or path keeps them while the host is fixed`() {
        assertThat(normalizeRtotaBaseUrl("https://rtota.app/base")).isEqualTo("https://www.rtota.app/base")
    }

    @Test
    fun `empty stays empty so the caller can fall back to the default`() {
        assertThat(normalizeRtotaBaseUrl("")).isEmpty()
        assertThat(normalizeRtotaBaseUrl("   ")).isEmpty()
    }
}
